package com.example.ZeroMark.Canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import com.example.ZeroMark.Brushes.BrushDescriptor;

import java.util.ArrayList;
import java.util.Collection;
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
 *           └─ stampDab(x, y, radius, alpha, alphaMultiplier, brush, paint)
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

    // ─── Scratch bitmap (commit path, NORMAL blend only) ─────────
    private Bitmap scratchBitmap;
    private Canvas scratchCanvas;

    // ─── Thread-split paints ──────────────────────────────────────
    //   penPaint    → renderer thread (drawSegmentFrontBuffer, drawFullCanvas)
    //   commitPaint → main thread     (commitStroke)
    private final Paint penPaint    = createStrokePaint();
    private final Paint commitPaint = createStrokePaint();

    private final Paint bitmapPaint    = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint layerPaint     = new Paint();
    private final Paint compositePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static final PorterDuffXfermode CLEAR_XFERMODE =
            new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    // ─── Dirty-region tracking (commit path only) ────────────────
    private final RectF commitDirty  = new RectF();
    private final Rect  dirtyIntRect = new Rect();

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
     * alphaMultiplier = resolveLayerAlpha(brush) is applied per-dab here
     * because the front-buffer canvas is cumulative — we cannot wrap the
     * whole stroke in a single saveLayer the way drawStroke (commit path)
     * does. Pre-multiplying achieves the same overall opacity.
     */
    public void drawSegmentFrontBuffer(Canvas canvas, float[] data, BrushDescriptor brush) {
        if (data.length < 9) return;

        float ax = data[0], ay = data[1], ap = data[2];
        float bx = data[3], by = data[4], bp = data[5];

        // Apply brush.opacity to live dabs so they match the committed stroke.
        // Commit path achieves this via saveLayer; live path pre-multiplies here.
        final float layerAlpha = BrushResolver.resolveLayerAlpha(brush);

        session.record(ax, ay, ap, bx, by, bp);

        spacingCarry = renderSegment(ax, ay, ap, bx, by, bp,
                canvas, brush, spacingCarry, null, penPaint, layerAlpha);

        tetherAx = bx; tetherAy = by; tetherAp = bp;
        tetherBx = data[6]; tetherBy = data[7]; tetherBp = data[8];
        hasTether = true;

        float dx = tetherBx - tetherAx;
        float dy = tetherBy - tetherAy;
        if (dx * dx + dy * dy >= 1f) {
            renderSegment(tetherAx, tetherAy, tetherAp,
                    tetherBx, tetherBy, tetherBp,
                    canvas, brush, spacingCarry, null, penPaint, layerAlpha);
        }
    }

    // =================================================================
    // Multi-buffer replay  —  renderer thread
    // =================================================================

    public void drawFullCanvas(Canvas canvas, Bitmap committed,
                               Collection<? extends float[]> segments,
                               BrushDescriptor brush) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawColor(Color.WHITE);
        if (committed != null) canvas.drawBitmap(committed, 0, 0, bitmapPaint);
        if (segments.isEmpty()) return;

        drawStroke(canvas, segments, brush, 0f, penPaint);
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
        if (snapshot.isEmpty()) return;

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;

        if (isClear) {
            drawStroke(bitmapCanvas, snapshot, brush, 0f, commitPaint);
            return;
        }

        int w = bitmapCanvas.getWidth();
        int h = bitmapCanvas.getHeight();
        ensureScratch(w, h);

        commitDirty.setEmpty();
        drawStroke(scratchCanvas, snapshot, brush, 0f, commitPaint, commitDirty);

        if (!commitDirty.isEmpty()) {
            int l = Math.max(0, (int) Math.floor(commitDirty.left)   - 1);
            int t = Math.max(0, (int) Math.floor(commitDirty.top)    - 1);
            int r = Math.min(w, (int) Math.ceil (commitDirty.right)  + 1);
            int b = Math.min(h, (int) Math.ceil (commitDirty.bottom) + 1);
            if (l < r && t < b) {
                dirtyIntRect.set(l, t, r, b);
                compositePaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
                bitmapCanvas.drawBitmap(scratchBitmap, dirtyIntRect, dirtyIntRect, compositePaint);
                scratchCanvas.save();
                scratchCanvas.clipRect(dirtyIntRect);
                scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                scratchCanvas.restore();
            }
        }
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
        renderSegment(ax, ay, ap, bx, by, bp, canvas, brush, spacingCarry, null, penPaint, 1.0f);
    }

    // =================================================================
    // Core render pipeline
    // =================================================================

    /**
     * Draw a stroke by wrapping segments in a saveLayer at resolveLayerAlpha.
     * Used by commit path and multi-buffer replay.
     * alphaMultiplier inside renderSegment is 1.0f — the saveLayer handles opacity.
     */
    private void drawStroke(Canvas target,
                             Collection<? extends float[]> segments,
                             BrushDescriptor brush,
                             float initialCarry,
                             Paint paint) {
        drawStroke(target, segments, brush, initialCarry, paint, null);
    }

    private void drawStroke(Canvas target,
                             Collection<? extends float[]> segments,
                             BrushDescriptor brush,
                             float initialCarry,
                             Paint paint,
                             RectF dirtyOut) {
        if (segments.isEmpty()) return;

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;

        if (isClear) {
            float carry = initialCarry;
            for (float[] seg : segments)
                carry = renderSegment(seg[0], seg[1], seg[2],
                        seg[3], seg[4], seg[5],
                        target, brush, carry, dirtyOut, paint, 1.0f);
        } else {
            // saveLayer applies brush.opacity once to the whole stroke so
            // overlapping dabs don't stack beyond it. alphaMultiplier=1.0f
            // inside because the layer itself carries the opacity.
            layerPaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
            target.saveLayer(null, layerPaint);
            float carry = initialCarry;
            for (float[] seg : segments)
                carry = renderSegment(seg[0], seg[1], seg[2],
                        seg[3], seg[4], seg[5],
                        target, brush, carry, dirtyOut, paint, 1.0f);
            target.restore();
        }
    }

    /**
     * Stamp dabs along one segment (a→b). Returns the updated spacing carry.
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

        float dx    = bx - ax;
        float dy    = by - ay;
        float lenSq = dx * dx + dy * dy;

        float avgDiameter = (wa + wb) / 2f;
        float stepPx = Math.max(1f, avgDiameter * BrushResolver.resolveSpacingMultiplier(brush));

        if (lenSq < 0.01f) {
            if (carry == 0f) {
                float r  = wa / 2f;
                float oa = BrushResolver.resolveDabAlpha(brush, ap) * alphaMultiplier;
                stampDab(target, ax, ay, r, oa, brush, paint);
                if (dirtyOut != null) dirtyOut.union(ax - r, ay - r, ax + r, ay + r);
            }
            return carry;
        }

        float len          = (float) Math.sqrt(lenSq);
        float distAlongSeg = stepPx - carry;
        if (distAlongSeg > len) return carry + len;

        while (distAlongSeg <= len) {
            float t        = distAlongSeg / len;
            float x        = ax + dx * t;
            float y        = ay + dy * t;
            float radius   = (wa + (wb - wa) * t) / 2f;
            float pressure = ap + (bp - ap) * t;
            float alpha    = BrushResolver.resolveDabAlpha(brush, pressure) * alphaMultiplier;
            stampDab(target, x, y, radius, alpha, brush, paint);
            if (dirtyOut != null) dirtyOut.union(x - radius, y - radius, x + radius, y + radius);
            distAlongSeg += stepPx;
        }

        return len - (distAlongSeg - stepPx);
    }

    private void stampDab(Canvas target, float x, float y, float radius,
                          float alpha, BrushDescriptor brush, Paint paint) {
        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            paint.setXfermode(CLEAR_XFERMODE);
            paint.setAlpha(255);
        } else {
            paint.setXfermode(null);
            paint.setColor(brush.color);
            paint.setAlpha((int) (alpha * 255));
        }
        target.drawCircle(x, y, radius, paint);
    }

    // =================================================================
    // Internals
    // =================================================================

    private void ensureScratch(int w, int h) {
        if (scratchBitmap == null
                || scratchBitmap.getWidth()  != w
                || scratchBitmap.getHeight() != h) {
            if (scratchBitmap != null) scratchBitmap.recycle();
            scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            scratchCanvas = new Canvas(scratchBitmap);
        }
    }

    private static Paint createStrokePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }
}
