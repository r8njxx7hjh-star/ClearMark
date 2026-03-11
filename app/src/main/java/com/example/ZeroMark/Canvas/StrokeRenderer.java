package com.example.ZeroMark.Canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import com.example.ZeroMark.Brushes.BrushDescriptor;

import java.util.Collection;
import java.util.List;

public class StrokeRenderer {

    // ─── Scratch bitmap (commit path only, software canvas) ───────
    private Bitmap scratchBitmap;
    private Canvas scratchCanvas;

    // ─── Live stroke accumulation bitmap ─────────────────────────
    // Receives each segment's dabs at full alpha, incrementally.
    // The front buffer composites this at stroke opacity — O(1) per segment.
    // The multi-buffer layer (committed + full stroke replay) sits underneath
    // via SurfaceControl GPU compositing, so the front buffer does NOT need
    // to re-blit the committed bitmap — that's already there.
    private Bitmap liveBitmap;
    private Canvas liveCanvas;

    private final Paint penPaint    = createPenPaint();
    private final Paint eraserPaint = createEraserPaint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Reused every segment — never allocate Paint in the hot path
    private final Paint livePaint      = new Paint(Paint.FILTER_BITMAP_FLAG);

    // PERF FIX: promoted from local variables in drawFullCanvas / commitStroke
    private final Paint layerPaint     = new Paint();
    private final Paint compositePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Allocated once — PorterDuffXfermode is immutable, safe to share
    private static final PorterDuffXfermode CLEAR_XFERMODE =
            new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    // ─── Live stroke lifecycle ────────────────────────────────────

    public void beginLiveStroke(int w, int h) {
        if (liveBitmap == null || liveBitmap.getWidth() != w || liveBitmap.getHeight() != h) {
            if (liveBitmap != null) liveBitmap.recycle();
            liveBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            liveCanvas = new Canvas(liveBitmap);
        }
        liveCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        spacingCarry = 0f;
    }

    public void endLiveStroke() {
        // FLICKER FIX: do NOT clear liveBitmap here.
        //
        // Previously this eagerly cleared liveBitmap on the main thread, but
        // the renderer thread can still have an in-flight onDrawFrontBufferedLayer
        // queued that reads liveBitmap. Clearing it here races with that read:
        //
        //   Main thread:               Renderer thread:
        //   endLiveStroke() → clear    [in-flight] onDrawFrontBufferedLayer
        //   commit()                     reads liveBitmap → transparent → FLASH
        //
        // The suppress mechanism in onDrawFrontBufferedLayer already handles
        // the post-commit frame correctly. liveBitmap is cleared there, on the
        // renderer thread, after the suppress is consumed — no race possible.
        //
        // liveBitmap is also cleared at the start of the next beginLiveStroke(),
        // so stale content never leaks into a subsequent stroke.
    }

    // ─── Tether state (ghost tip → raw touch) ───────────────────
    // Stored so drawSegmentFrontBuffer can include the tether in the same
    // composite layer as liveBitmap. This prevents opacity doubling at the
    // seam: liveBitmap and the tether both get brush.opacity applied once,
    // together, inside a single saveLayer — identical to how commitStroke works.
    private float tetherAx, tetherAy, tetherAp;
    private float tetherBx, tetherBy, tetherBp;
    private boolean hasTether = false;

    /** Called each frame with the current ghost-tip and raw-touch positions. */
    public void setTether(float ax, float ay, float ap,
                          float bx, float by, float bp) {
        tetherAx = ax; tetherAy = ay; tetherAp = ap;
        tetherBx = bx; tetherBy = by; tetherBp = bp;
        hasTether = true;
    }

    /** Called on pen-lift so the tether is no longer drawn. */
    public void clearTether() {
        hasTether = false;
    }

    // ─── Front buffer (live, per segment) ────────────────────────
    // data = float[9]: [0..5] smoothed segment, [6..8] raw touch tip (x, y, pressure)
    //
    // Step 1: accumulate the smoothed segment onto liveBitmap (persistent)
    // Step 2: inside a single saveLayer at brush opacity:
    //           a) composite liveBitmap (smoothed ink so far)
    //           b) draw the tether (ghost tip → raw touch) on top
    //
    // By compositing both inside one saveLayer, the tether and the smoothed
    // stroke share a single opacity pass — no double-opacity at their junction
    // even when brush.opacity is < 1. The result is one seamless stroke.

    public void drawSegmentFrontBuffer(Canvas canvas, float[] data, BrushDescriptor brush) {
        if (liveBitmap == null) return;
        if (data.length != 9) return;

        // Incrementally accumulate only the new smoothed segment onto liveBitmap.
        spacingCarry = renderSegmentFlat(data[0], data[1], data[2],
                data[3], data[4], data[5],
                liveCanvas, brush, spacingCarry);

        // Update tether from the latest data packet.
        tetherAx = data[3]; tetherAy = data[4]; tetherAp = data[5];
        tetherBx = data[6]; tetherBy = data[7]; tetherBp = data[8];
        hasTether = true;

        // Composite liveBitmap + tether together inside one saveLayer at brush opacity.
        // This is the key fix: both parts of the visible stroke (smoothed + tether)
        // are treated as a single layer — brush.opacity is applied exactly once,
        // matching how the committed stroke looks. No seam, no double-opacity.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;
        if (isClear) {
            // Eraser: draw liveBitmap at full alpha, then tether at full alpha.
            canvas.drawBitmap(liveBitmap, 0, 0, bitmapPaint);
            if (hasTether) {
                float dx = tetherBx - tetherAx;
                float dy = tetherBy - tetherAy;
                if (dx * dx + dy * dy >= 1f) {
                    renderSegmentFlat(tetherAx, tetherAy, tetherAp,
                            tetherBx, tetherBy, tetherBp,
                            canvas, brush, spacingCarry);
                }
            }
        } else {
            // Normal brush: saveLayer at layer-alpha so the whole visible stroke
            // (liveBitmap + tether) composites at the correct brush opacity.
            layerPaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
            canvas.saveLayer(null, layerPaint);

            // Draw liveBitmap at full alpha — dabs already have resolveDabAlpha baked in.
            canvas.drawBitmap(liveBitmap, 0, 0, bitmapPaint);

            // Draw tether (ghost tip → raw touch) at full alpha on top.
            // Together with liveBitmap they form one continuous stroke.
            if (hasTether) {
                float dx = tetherBx - tetherAx;
                float dy = tetherBy - tetherAy;
                if (dx * dx + dy * dy >= 1f) {
                    renderSegmentFlat(tetherAx, tetherAy, tetherAp,
                            tetherBx, tetherBy, tetherBp,
                            canvas, brush, spacingCarry);
                }
            }

            canvas.restore();
        }
    }

    // ─── Multi buffer (full stroke replay) ───────────────────────

    public void drawFullCanvas(Canvas canvas, Bitmap committed,
                               Collection<? extends float[]> segments,
                               BrushDescriptor brush) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawColor(Color.WHITE);
        if (committed != null) canvas.drawBitmap(committed, 0, 0, bitmapPaint);
        if (segments.isEmpty()) return;

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;

        if (isClear) {
            float c = 0f;
            for (float[] seg : segments) {
                c = renderSegmentFlat(seg[0], seg[1], seg[2],
                        seg[3], seg[4], seg[5],
                        canvas, brush, c);
            }
            return;
        }

        // saveLayer at layer opacity so the replayed dabs (using resolveDabAlpha)
        // composite onto the canvas at the correct brush opacity.
        layerPaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
        canvas.saveLayer(null, layerPaint);

        float c = 0f;
        for (float[] seg : segments) {
            c = renderSegmentFlat(seg[0], seg[1], seg[2],
                    seg[3], seg[4], seg[5],
                    canvas, brush, c);
        }
        canvas.restore();
    }

    // ─── Commit stroke to the persistent bitmap ───────────────────

    public void commitStroke(Canvas bitmapCanvas, List<float[]> points, BrushDescriptor brush) {
        if (points.isEmpty()) return;

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;

        // commitSpacingCarry always starts at 0 — the commit is a clean independent
        // replay of the stroke, not a continuation of the live carry. This is what
        // prevents dab positions shifting when the stroke is finalised.
        commitSpacingCarry = 0f;

        if (isClear) {
            float[] prev = null;
            for (float[] pt : points) {
                if (prev != null) {
                    commitSpacingCarry = renderSegmentFlat(
                            prev[0], prev[1], prev[2],
                            pt[0],   pt[1],   pt[2],
                            bitmapCanvas, brush, commitSpacingCarry);
                } else {
                    stampCircle(bitmapCanvas, pt[0], pt[1],
                            BrushResolver.resolveSize(brush, pt[2]) / 2f, brush);
                }
                prev = pt;
            }
            return;
        }

        int w = bitmapCanvas.getWidth();
        int h = bitmapCanvas.getHeight();
        ensureScratch(w, h);

        scratchCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // Per-dab opacity is baked by stampCircleWithAlpha — composite at full alpha.
        float[] prev = null;
        for (float[] pt : points) {
            if (prev != null) {
                commitSpacingCarry = renderSegmentFlat(
                        prev[0], prev[1], prev[2],
                        pt[0],   pt[1],   pt[2],
                        scratchCanvas, brush, commitSpacingCarry);
            } else {
                float oa = BrushResolver.resolveDabAlpha(brush, pt[2]);
                stampCircleWithAlpha(scratchCanvas, pt[0], pt[1],
                        BrushResolver.resolveSize(brush, pt[2]) / 2f, oa, brush);
            }
            prev = pt;
        }

        // Composite scratch at layer opacity — this is where brush.opacity takes effect.
        compositePaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
        bitmapCanvas.drawBitmap(scratchBitmap, 0, 0, compositePaint);
        spacingCarry = 0f; // live carry reset — next stroke starts fresh
    }

    // ─── Direct segment apply (eraser live path) ─────────────────

    public void applySegmentDirect(Canvas canvas, float[] segment, BrushDescriptor brush) {
        if (segment.length < 6 || canvas == null || brush == null) return;
        // Eraser uses its own isolated carry — reuse commitSpacingCarry since
        // the eraser path never overlaps with the commit path temporally.
        commitSpacingCarry = renderSegmentFlat(segment[0], segment[1], segment[2],
                segment[3], segment[4], segment[5],
                canvas, brush, commitSpacingCarry);
    }

    // ─── Eraser ───────────────────────────────────────────────────

    public void drawEraserLine(Canvas bitmapCanvas, float x0, float y0, float x1, float y1, float strokeWidth) {
        eraserPaint.setStrokeWidth(strokeWidth);
        bitmapCanvas.drawLine(x0, y0, x1, y1, eraserPaint);
    }

    public void drawEraserPoint(Canvas bitmapCanvas, float x, float y, float strokeWidth) {
        eraserPaint.setStrokeWidth(strokeWidth);
        bitmapCanvas.drawPoint(x, y, eraserPaint);
    }

    // ─── Carry-over distance ──────────────────────────────────────
    // Tracks how far into the current step interval we are between segments,
    // so dab spacing is consistent regardless of input chunking or draw speed.
    //
    // Two separate carries:
    //   spacingCarry      — used during the live front-buffer stroke
    //   commitSpacingCarry — used exclusively inside commitStroke(), always
    //                        starts at 0 so the committed replay is independent
    //                        of whatever state the live carry was in. This is
    //                        what prevents dabs from shifting after lift-off.
    private float spacingCarry       = 0f;
    private float commitSpacingCarry = 0f;

    /** Exposes the live-stroke carry so the preview segment starts from the exact
     *  position the committed stroke will next place a dab — dab alignment is
     *  therefore identical between the preview and the final committed ink. */
    public float getSpacingCarry() { return spacingCarry; }

    // ─── Preview segment (Procreate-style StreamLine) ─────────────
    // Renders the gap from the smoothed ghost tip to the raw finger position
    // directly onto the provided canvas (the CanvasOverlay hardware canvas).
    // Uses the same renderSegmentFlat + resolveDabAlpha as the committed stroke
    // so it looks pixel-identical — the user sees a seamless continuation of
    // their ink that reaches all the way to the finger, with no visible lag.
    //
    // carry is passed in (= current spacingCarry) so the first preview dab lands
    // exactly where the committed stroke will place its next dab when the ghost
    // catches up. This is what makes the transition invisible on pen-lift.
    //
    // NOTE: this method does NOT mutate spacingCarry — the preview is read-only
    // from the carry perspective; only the committed path advances it.
    public void drawPreviewSegment(Canvas canvas,
                                   float ax, float ay, float ap,
                                   float bx, float by, float bp,
                                   BrushDescriptor brush) {
        renderSegmentFlat(ax, ay, ap, bx, by, bp, canvas, brush, spacingCarry);
    }

    // ─── Core splat loop ──────────────────────────────────────────
    // carry: how far into the current step interval we already are.
    // Returns the updated carry to use for the next segment.

    private float renderSegmentFlat(float ax, float ay, float ap,
                                    float bx, float by, float bp,
                                    Canvas target, BrushDescriptor brush,
                                    float carry) {
        float wa = BrushResolver.resolveSize(brush, ap);
        float wb = BrushResolver.resolveSize(brush, bp);

        float dx    = bx - ax;
        float dy    = by - ay;
        float lenSq = dx * dx + dy * dy;

        float avgDiameter = (wa + wb) / 2f;
        // Spacing formula lives in BrushResolver.resolveSpacingMultiplier — shared with
        // CanvasOverlay so the tether preview is pixel-identical to the committed stroke.
        float stepPx = Math.max(1f, avgDiameter * BrushResolver.resolveSpacingMultiplier(brush));

        // Dot / tap — no length to walk, stamp once if this is the very first dab
        if (lenSq < 0.01f) {
            if (carry == 0f) {
                float oa = BrushResolver.resolveDabAlpha(brush, ap);
                stampCircleWithAlpha(target, ax, ay, wa / 2f, oa, brush);
            }
            return carry;
        }

        float len = (float) Math.sqrt(lenSq);

        // distAlongSeg = distance from segment start to where the next dab lands
        float distAlongSeg = stepPx - carry;

        if (distAlongSeg > len) {
            // No dab lands in this segment — accumulate and move on
            return carry + len;
        }

        while (distAlongSeg <= len) {
            float t        = distAlongSeg / len;
            float x        = ax + dx * t;
            float y        = ay + dy * t;
            float radius   = (wa + (wb - wa) * t) / 2f;
            float pressure = ap + (bp - ap) * t;
            float alpha    = BrushResolver.resolveDabAlpha(brush, pressure);
            stampCircleWithAlpha(target, x, y, radius, alpha, brush);
            distAlongSeg  += stepPx;
        }

        // Return how far past the last dab we ended up
        return len - (distAlongSeg - stepPx);
    }

    private void stampCircle(Canvas target, float x, float y, float radius, BrushDescriptor brush) {
        stampCircleWithAlpha(target, x, y, radius, 1f, brush);
    }

    // Per-dab stamp with explicit alpha (0..1). Used when pressureControlsOpacity is true
    // so each dab carries its own opacity rather than the whole stroke layer.
    private void stampCircleWithAlpha(Canvas target, float x, float y, float radius,
                                      float alpha, BrushDescriptor brush) {
        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            penPaint.setXfermode(CLEAR_XFERMODE);
            penPaint.setAlpha(255);
        } else {
            penPaint.setXfermode(null);
            penPaint.setColor(brush.color);
            penPaint.setAlpha((int)(alpha * 255));
        }
        target.drawCircle(x, y, radius, penPaint);
    }

    // ─── Scratch bitmap helpers ───────────────────────────────────

    private void ensureScratch(int w, int h) {
        if (scratchBitmap == null || scratchBitmap.getWidth() != w || scratchBitmap.getHeight() != h) {
            if (scratchBitmap != null) scratchBitmap.recycle();
            scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            scratchCanvas = new Canvas(scratchBitmap);
        }
    }

    // ─── Paint factories ──────────────────────────────────────────

    private static Paint createPenPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static Paint createEraserPaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        return p;
    }
}
