package com.example.zeromark.canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import com.example.zeromark.brushes.BrushDescriptor;
import com.example.zeromark.canvas.model.Stroke;

import java.util.ArrayList;
import java.util.List;

/**
 * StrokeRenderer — single render path for every dab, live or committed.
 *
 * Architecture
 * ────────────
 * All drawing — pen, eraser, live front-buffer, multi-buffer replay, and
 * final bitmap commit — flows through ONE call chain:
 *
 *   drawStroke / drawSegmentFrontBuffer
 *     └─ renderSegment(ax,ay,ap, bx,by,bp, …, alphaMultiplier)
 *           └─ stampDab(x, y, radius, alpha, paint)
 *
 * Paint ownership
 * ───────────────
 * Paint state (xfermode, color) is configured ONCE per stroke by the caller
 * before entering renderSegment, via configurePaint(). stampDab only sets
 * per-dab alpha — it never touches xfermode or color. This eliminates
 * O(N) redundant setXfermode/setColor calls across the dab loop.
 *
 * Opacity consistency — live vs committed
 * ────────────────────────────────────────
 * The commit path wraps each stroke in saveLayer(layerPaint) where
 * layerPaint.alpha = resolveLayerAlpha(brush) = brush.opacity/100.
 * This applies the overall brush opacity once to the whole stroke so
 * overlapping dabs don't stack beyond it.
 *
 * The live (front-buffer) path cannot use saveLayer across multiple
 * onDrawFrontBufferedLayer calls — the canvas is cumulative. Instead,
 * resolveLayerAlpha is passed as alphaMultiplier directly into stampDab
 * so each dab is pre-multiplied by brush.opacity. This gives the correct
 * visual opacity at the cost of letting overlapping dabs stack (fine for
 * normal spacing; only visible with heavy overlap at low opacity). The
 * committed stroke matches what was drawn live.
 *
 * Eraser on the live path
 * ───────────────────────
 * PorterDuff.Mode.CLEAR only works correctly when drawing into an offscreen
 * bitmap or a saveLayer — on a hardware-accelerated Canvas it punches through
 * to the window background. The commit path is always bitmap-backed so CLEAR
 * works there without a saveLayer. The live path must wrap each eraser segment
 * in its own saveLayer to isolate the CLEAR operation.
 *
 * Thread safety
 * ─────────────
 * session.record() is called on the renderer thread (via drawSegmentFrontBuffer).
 * commitStroke() is called on the main thread (via onStrokeComplete).
 *
 * The caller (FastDrawingView) sets isPenDown=false BEFORE calling commitStroke,
 * which prevents any new renderer callbacks from entering session.record().
 * However a callback already past the isPenDown check may still be mid-record
 * when commitStroke starts. We handle this with two measures:
 *   1. session.record() is synchronized on the session object.
 *   2. commitStroke() takes a synchronized snapshot of session.segments before
 *      iterating, so it never iterates a list being concurrently modified.
 */
public class StrokeRenderer {

    // ═══════════════════════════════════════════════════════════════
    // StrokeSession
    // ═══════════════════════════════════════════════════════════════

    public static final class StrokeSession {
        final List<float[]> segments = new ArrayList<>();
        float initialCarry = 0f;

        synchronized void reset() {
            segments.clear();
            initialCarry = 0f;
        }

        synchronized void record(float ax, float ay, float ap,
                                 float bx, float by, float bp) {
            segments.add(new float[]{ax, ay, ap, bx, by, bp});
        }

        synchronized List<float[]> snapshot() {
            return new ArrayList<>(segments);
        }

        synchronized boolean isEmpty() {
            return segments.isEmpty();
        }
    }

    private final StrokeSession session = new StrokeSession();

    // ─── Thread-isolated paints ──────────────────────────────────
    // We use ThreadLocal because this renderer is called from:
    // 1. Renderer Thread (Live front-buffer & Multi-buffer replay)
    // 2. Background Threads (Tile rendering via CanvasModel)
    // 3. UI Thread (Pending stroke patches during tile stalls)
    // Sharing a single Paint object causes race conditions where a Pen
    // stroke might inherit the "White fallback" color of an Eraser stroke.
    private final ThreadLocal<Paint> strokePaint = ThreadLocal.withInitial(StrokeRenderer::createStrokePaint);
    private final ThreadLocal<Paint> layerPaint  = ThreadLocal.withInitial(Paint::new);
    private final ThreadLocal<Paint> bitmapPaint = ThreadLocal.withInitial(() -> new Paint(Paint.FILTER_BITMAP_FLAG));

    private static final PorterDuffXfermode CLEAR_XFERMODE =
            new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    // ─── Spacing carry (renderer thread) ─────────────────────────
    private float spacingCarry = 0f;

    public float getSpacingCarry() { return spacingCarry; }

    // ─── Tether state (renderer thread) ──────────────────────────
    private float   tetherAx, tetherAy, tetherAp;
    private float   tetherBx, tetherBy, tetherBp;
    private boolean hasTether = false;

    public void clearTether() { hasTether = false; }

    // =================================================================
    // Stroke lifecycle
    // =================================================================

    public void beginLiveStroke() {
        session.reset();
        spacingCarry = 0f;
    }

    // =================================================================
    // Paint configuration
    // =================================================================

    /**
     * Configure paint for a brush type — xfermode and base color.
     * Called once per stroke (or per segment on the live path) so the
     * dab loop never needs to touch xfermode or color.
     */
    private static void configurePaint(Paint paint, BrushDescriptor brush, Canvas canvas) {
        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            paint.setXfermode(CLEAR_XFERMODE);
            // Alpha is always 255 for CLEAR — the mode itself handles erasure.
            // Set it here so stampDab doesn't need a branch.
            paint.setAlpha(255);
        } else {
            paint.setXfermode(null);
            paint.setColor(brush.color);
        }
    }

    // =================================================================
    // Live path  —  renderer thread
    // =================================================================

    /**
     * Draw one smoothed segment onto the hardware front-buffer Canvas and
     * record it in the session for later commit replay.
     *
     * data = float[10]: [0..5] smoothed segment (ax,ay,ap,bx,by,bp),
     *                   [6..8] raw tip (bx,by,bp),
     *                   [9]    stroke generation.
     *
     * Eraser (CLEAR) path:
     *   PorterDuff.CLEAR punches through to the window background on a
     *   hardware canvas. Wrapping in saveLayer creates an offscreen buffer
     *   where CLEAR operates correctly, then composites back normally.
     *
     * Pen path:
     *   resolveLayerAlpha is pre-multiplied per dab because the front-buffer
     *   canvas is cumulative — we cannot wrap the whole stroke in a saveLayer
     *   the way the commit path does.
     */
    public void drawSegmentFrontBuffer(Canvas canvas, float[] data, BrushDescriptor brush) {
        if (data.length < 9) return;

        float ax = data[0], ay = data[1], ap = data[2];
        float bx = data[3], by = data[4], bp = data[5];

        Paint paint = strokePaint.get();
        configurePaint(paint, brush, canvas);

        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            // Restore saveLayer for the live hardware front-buffer.
            // Since this canvas is hardware-accelerated and only contains
            // front-buffer segments, we MUST use saveLayer to prevent
            // punching to the window background.
            int count = canvas.saveLayer(null, null);
            spacingCarry = renderSegment(ax, ay, ap, bx, by, bp,
                    canvas, brush, spacingCarry, null, paint, 1.0f);
            canvas.restoreToCount(count);
        } else {
            // Pre-multiply brush opacity into each dab to match the committed stroke,
            // which achieves overall opacity via saveLayer in drawStroke.
            final float layerAlpha = BrushResolver.resolveLayerAlpha(brush);
            spacingCarry = renderSegment(ax, ay, ap, bx, by, bp,
                    canvas, brush, spacingCarry, null, paint, layerAlpha);
        }
    }

    // =================================================================
    // Multi-buffer replay  —  renderer thread
    // =================================================================

    public void drawFullCanvas(Canvas canvas, Bitmap committed,
                               float[] flatSegments,
                               BrushDescriptor brush) {
        if (committed != null) canvas.drawBitmap(committed, 0, 0, bitmapPaint.get());
        if (flatSegments.length == 0) return;

        drawStroke(canvas, flatSegments, brush, 0f, strokePaint.get());
    }

    // =================================================================
    // Commit path  —  main thread
    // =================================================================

    /**
     * Bake the stroke's session segments onto the persistent bitmap.
     * Uses a synchronized snapshot of the session to avoid any concurrent
     * modification from renderer-thread callbacks still draining.
     */
    public void commitStroke(Canvas bitmapCanvas, BrushDescriptor brush) {
        final List<float[]> snapshot = session.snapshot();
        float[] flat = new float[snapshot.size() * 6];
        for (int i = 0; i < snapshot.size(); i++) {
            System.arraycopy(snapshot.get(i), 0, flat, i * 6, 6);
        }
        commitStrokeInternal(bitmapCanvas, brush, flat, false);
    }

    public void commitStrokeForTile(Canvas canvas, Stroke stroke) {
        commitStrokeInternal(canvas, stroke.getBrush(), stroke.getPoints(), false);
    }

    public void commitStrokeForTileSimplified(Canvas canvas, Stroke stroke) {
        commitStrokeInternal(canvas, stroke.getBrush(), stroke.getPoints(), true);
    }

    private void commitStrokeInternal(Canvas canvas, BrushDescriptor brush,
                                      float[] points, boolean simplified) {
        if (points.length == 0) return;
        Paint paint = strokePaint.get();
        if (simplified && brush.blendMode != BrushDescriptor.BlendMode.CLEAR) {
            drawStrokeSimplified(canvas, points, brush, paint);
        } else {
            drawStroke(canvas, points, brush, 0f, paint);
        }
    }

    private void drawStrokeSimplified(Canvas target, float[] points,
                                      BrushDescriptor brush, Paint paint) {
        configurePaint(paint, brush, target);
        paint.setAlpha(255); // Full opacity for performance mode lines
        paint.setStrokeWidth(brush.size);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStyle(Paint.Style.STROKE);

        for (int i = 0; i < points.length; i += 6) {
            target.drawLine(points[i], points[i + 1], points[i + 3], points[i + 4], paint);
        }

        // Reset paint style for normal dab rendering
        paint.setStyle(Paint.Style.FILL);
    }

    // =================================================================
    // Tether / preview helpers
    // =================================================================

    public void drawPreviewSegment(Canvas canvas,
                                   float ax, float ay, float ap,
                                   float bx, float by, float bp,
                                   BrushDescriptor brush) {
        // Preview is drawn inside CanvasOverlay's own saveLayer(layerPaint),
        // so alphaMultiplier=1.0f here — the layer handles opacity.
        Paint paint = strokePaint.get();
        configurePaint(paint, brush, canvas);
        renderSegment(ax, ay, ap, bx, by, bp, canvas, brush, spacingCarry, null, paint, 1.0f);
    }

    // =================================================================
    // Core render pipeline
    // =================================================================

    /**
     * Draw a full stroke, wrapping it in a saveLayer at resolveLayerAlpha so
     * overlapping dabs don't stack beyond the brush opacity.
     * configurePaint() is called here so the dab loop in renderSegment never
     * needs to touch xfermode or color.
     *
     * Used by the commit path and multi-buffer replay.
     */
    private void drawStroke(Canvas target, float[] segments, BrushDescriptor brush,
                            float initialCarry, Paint paint) {
        drawStroke(target, segments, brush, initialCarry, paint, null);
    }

    private void drawStroke(Canvas target, float[] segments, BrushDescriptor brush,
                            float initialCarry, Paint paint, RectF dirtyOut) {
        if (segments.length == 0) return;

        // Configure paint once — xfermode and base color.
        // stampDab only updates per-dab alpha from here on.
        configurePaint(paint, brush, target);

        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            // Erasers (CLEAR) MUST draw directly to the canvas. Using saveLayer
            // creates an empty buffer, isolating the CLEAR operation and making
            // it a no-op on bitmap-backed canvases.
            float carry = initialCarry;
            for (int i = 0; i < segments.length; i += 6) {
                carry = renderSegment(
                        segments[i],     segments[i + 1], segments[i + 2],
                        segments[i + 3], segments[i + 4], segments[i + 5],
                        target, brush, carry, dirtyOut, paint, 1.0f);
            }
        } else {
            Paint lp = layerPaint.get();
            lp.setAlpha((int) (BrushResolver.resolveLayerAlpha(brush) * 255));
            int layerCount = target.saveLayer(null, lp);

            float carry = initialCarry;
            for (int i = 0; i < segments.length; i += 6) {
                carry = renderSegment(
                        segments[i],     segments[i + 1], segments[i + 2],
                        segments[i + 3], segments[i + 4], segments[i + 5],
                        target, brush, carry, dirtyOut, paint, 1.0f);
            }

            target.restoreToCount(layerCount);
        }
    }

    /**
     * Stamp dabs along one segment (a→b). Returns the updated spacing carry.
     *
     * Paint xfermode and color must already be set by the caller via
     * configurePaint(). This method only mutates paint.alpha per dab.
     *
     * @param alphaMultiplier  multiplied into each dab's alpha.
     *                         Pass resolveLayerAlpha for the live front-buffer path;
     *                         pass 1.0f for the commit/replay path (saveLayer handles it).
     */
    private float renderSegment(float ax, float ay, float ap,
                                float bx, float by, float bp,
                                Canvas target, BrushDescriptor brush,
                                float carry, RectF dirtyOut, Paint paint,
                                float alphaMultiplier) {
        float wa = BrushResolver.resolveSize(brush, ap);
        float wb = BrushResolver.resolveSize(brush, bp);

        // --- Early exit if segment is outside clip bounds ---
        float maxR  = Math.max(wa, wb) / 2f;
        float sLeft = Math.min(ax, bx) - maxR;
        float sTop  = Math.min(ay, by) - maxR;
        float sRight  = Math.max(ax, bx) + maxR;
        float sBottom = Math.max(ay, by) + maxR;

        Rect clip = target.getClipBounds();
        if (sLeft > clip.right || sRight < clip.left || sTop > clip.bottom || sBottom < clip.top) {
            float dx  = bx - ax;
            float dy  = by - ay;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len == 0) return carry;
            float startStep    = Math.max(1f, wa * BrushResolver.resolveSpacingMultiplier(brush));
            float distAlongSeg = startStep - carry;
            if (distAlongSeg > len) return carry + len;
            float lastStep  = Math.max(1f, wb * BrushResolver.resolveSpacingMultiplier(brush));
            float remaining = (len - distAlongSeg) % lastStep;
            return remaining;
        }

        float dx    = bx - ax;
        float dy    = by - ay;
        float lenSq = dx * dx + dy * dy;

        final float spacingMult = BrushResolver.resolveSpacingMultiplier(brush);
        float len = (float) Math.sqrt(lenSq);

        if (len == 0f) {
            if (carry == 0f) {
                float r  = wa / 2f;
                float oa = BrushResolver.resolveDabAlpha(brush, ap) * alphaMultiplier;
                stampDab(target, ax, ay, r, oa, brush, paint);
                if (dirtyOut != null) dirtyOut.union(ax - r, ay - r, ax + r, ay + r);
            }
            return carry;
        }

        // --- Per-dab adaptive stepping -----------------------------------
        // stepPx is recomputed at each dab from the local radius at that t,
        // so the overlap ratio stays constant even as pressure (and therefore
        // size) changes across the segment.
        float startStep    = Math.max(1f, wa * spacingMult);
        float distAlongSeg = startStep - carry;
        if (distAlongSeg > len) return carry + len; // no dab fits — accumulate

        float lastStep = startStep;
        while (distAlongSeg <= len) {
            float t        = distAlongSeg / len;
            float x        = ax + dx * t;
            float y        = ay + dy * t;
            float radius   = (wa + (wb - wa) * t) / 2f;
            float pressure = ap + (bp - ap) * t;
            float alpha    = BrushResolver.resolveDabAlpha(brush, pressure) * alphaMultiplier;
            stampDab(target, x, y, radius, alpha, brush, paint);
            if (dirtyOut != null) dirtyOut.union(x - radius, y - radius, x + radius, y + radius);

            lastStep       = Math.max(1f, radius * 2f * spacingMult);
            distAlongSeg  += lastStep;
        }

        return len - (distAlongSeg - lastStep);
    }

    /**
     * Draw a single dab circle.
     *
     * Paint xfermode and color are already configured by the caller.
     * This method ONLY sets alpha (which varies per dab by pressure) and draws.
     *
     * For CLEAR strokes, alpha is already 255 from configurePaint() and
     * alphaMultiplier=1.0f, so the setAlpha call is a no-op in that branch.
     */
    private static void stampDab(Canvas target, float x, float y, float radius,
                                 float alpha, BrushDescriptor brush, Paint paint) {
        paint.setAlpha((int) (alpha * 255));
        target.drawCircle(x, y, radius, paint);
    }

    // =================================================================
    // Internals
    // =================================================================

    private static Paint createStrokePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    public StrokeSession getSession() {
        return session;
    }
}