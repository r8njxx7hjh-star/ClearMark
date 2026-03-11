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

import java.util.Collection;
import java.util.List;

public class StrokeRenderer {

    // Scratch bitmap (commit path only, software canvas)
    private Bitmap scratchBitmap;
    private Canvas scratchCanvas;

    // Live stroke accumulation bitmap
    private Bitmap liveBitmap;
    private Canvas liveCanvas;

    // Thread-split paints:
    //   penPaint    -> renderer thread only (drawSegmentFrontBuffer, live path)
    //   commitPaint -> main thread only     (commitStroke, applySegmentDirect)
    //
    // Root cause of the "black dot on pen-lift" bug when pressureControlsOpacity
    // is true and smoothing is high:
    //
    //   Paint is not thread-safe. penPaint was mutated (setColor, setAlpha) from
    //   both the renderer thread (live dabs) and the main thread (commit dabs)
    //   concurrently with no synchronisation.
    //
    //   Race sequence:
    //     Main thread:     commitStroke stamps low-pressure dab -> setAlpha(lowValue)
    //     Renderer thread: tether dab fires concurrently       -> setAlpha(255)
    //     Main thread:     next commit dab reads penPaint.alpha = 255 -> black dot
    //
    //   The dots are opaque black because the color was set to brush.color (black
    //   in the inkPen preset) and alpha was 255 due to the race.
    //
    //   Fix: two independent Paint objects, one owned exclusively by each thread.
    //   No locking required — strict ownership is sufficient.
    private final Paint penPaint    = createPenPaint();   // renderer thread
    private final Paint commitPaint = createPenPaint();   // main thread
    private final Paint eraserPaint = createEraserPaint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint livePaint   = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Pre-allocated paints for layer compositing
    private final Paint layerPaint     = new Paint();
    private final Paint compositePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private static final PorterDuffXfermode CLEAR_XFERMODE =
            new PorterDuffXfermode(PorterDuff.Mode.CLEAR);

    // Dirty-region tracking (commit path only).
    // Pre-allocated fields reused across strokes to avoid per-stroke allocation.
    private final RectF commitDirty  = new RectF();
    private final Rect  dirtyIntRect = new Rect();

    // Live stroke lifecycle

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
    }

    // Tether state (ghost tip -> raw touch)

    private float tetherAx, tetherAy, tetherAp;
    private float tetherBx, tetherBy, tetherBp;
    private boolean hasTether = false;

    public void setTether(float ax, float ay, float ap,
                          float bx, float by, float bp) {
        tetherAx = ax; tetherAy = ay; tetherAp = ap;
        tetherBx = bx; tetherBy = by; tetherBp = bp;
        hasTether = true;
    }

    public void clearTether() { hasTether = false; }

    // Front buffer (live, per segment) - renderer thread
    // data = float[10]: [0..5] smoothed segment, [6..8] raw tip, [9] gen

    public void drawSegmentFrontBuffer(Canvas canvas, float[] data, BrushDescriptor brush) {
        if (liveBitmap == null || data.length < 9) return;

        // Live path uses penPaint (renderer thread)
        spacingCarry = renderSegmentFlat(data[0], data[1], data[2],
                data[3], data[4], data[5],
                liveCanvas, brush, spacingCarry, null, false);

        tetherAx = data[3]; tetherAy = data[4]; tetherAp = data[5];
        tetherBx = data[6]; tetherBy = data[7]; tetherBp = data[8];
        hasTether = true;

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;
        if (isClear) {
            canvas.drawBitmap(liveBitmap, 0, 0, bitmapPaint);
            if (hasTether) {
                float dx = tetherBx - tetherAx;
                float dy = tetherBy - tetherAy;
                if (dx * dx + dy * dy >= 1f) {
                    renderSegmentFlat(tetherAx, tetherAy, tetherAp,
                            tetherBx, tetherBy, tetherBp,
                            canvas, brush, spacingCarry, null, false);
                }
            }
        } else {
            layerPaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
            canvas.saveLayer(null, layerPaint);
            canvas.drawBitmap(liveBitmap, 0, 0, bitmapPaint);
            if (hasTether) {
                float dx = tetherBx - tetherAx;
                float dy = tetherBy - tetherAy;
                if (dx * dx + dy * dy >= 1f) {
                    renderSegmentFlat(tetherAx, tetherAy, tetherAp,
                            tetherBx, tetherBy, tetherBp,
                            canvas, brush, spacingCarry, null, false);
                }
            }
            canvas.restore();
        }
    }

    // Multi buffer (full stroke replay) - renderer thread

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
                        canvas, brush, c, null, false);
            }
            return;
        }

        layerPaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
        canvas.saveLayer(null, layerPaint);
        float c = 0f;
        for (float[] seg : segments) {
            c = renderSegmentFlat(seg[0], seg[1], seg[2],
                    seg[3], seg[4], seg[5],
                    canvas, brush, c, null, false);
        }
        canvas.restore();
    }

    // Commit stroke to the persistent bitmap - main thread

    public void commitStroke(Canvas bitmapCanvas, List<float[]> points, BrushDescriptor brush) {
        if (points.isEmpty()) return;

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;
        commitSpacingCarry = 0f;

        if (isClear) {
            float[] prev = null;
            for (float[] pt : points) {
                if (prev != null) {
                    // Eraser commit: uses commitPaint (main thread)
                    commitSpacingCarry = renderSegmentFlat(
                            prev[0], prev[1], prev[2],
                            pt[0],   pt[1],   pt[2],
                            bitmapCanvas, brush, commitSpacingCarry, null, true);
                } else {
                    stampCircleCommit(bitmapCanvas, pt[0], pt[1],
                            BrushResolver.resolveSize(brush, pt[2]) / 2f, brush);
                }
                prev = pt;
            }
            return;
        }

        int w = bitmapCanvas.getWidth();
        int h = bitmapCanvas.getHeight();
        ensureScratch(w, h);

        commitDirty.setEmpty();
        float[] prev = null;
        for (float[] pt : points) {
            if (prev != null) {
                // All commit-path stamps go through commitPaint (main thread)
                commitSpacingCarry = renderSegmentFlat(
                        prev[0], prev[1], prev[2],
                        pt[0],   pt[1],   pt[2],
                        scratchCanvas, brush, commitSpacingCarry, commitDirty, true);
            } else {
                float oa = BrushResolver.resolveDabAlpha(brush, pt[2]);
                float r  = BrushResolver.resolveSize(brush, pt[2]) / 2f;
                stampCircleWithAlphaCommit(scratchCanvas, pt[0], pt[1], r, oa, brush);
                commitDirty.union(pt[0] - r, pt[1] - r, pt[0] + r, pt[1] + r);
            }
            prev = pt;
        }

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

        spacingCarry = 0f;
    }

    // Direct segment apply (eraser live path) - main thread

    public void applySegmentDirect(Canvas canvas, float[] segment, BrushDescriptor brush) {
        if (segment.length < 6 || canvas == null || brush == null) return;
        // Eraser live path runs on main thread -> commitPaint
        commitSpacingCarry = renderSegmentFlat(segment[0], segment[1], segment[2],
                segment[3], segment[4], segment[5],
                canvas, brush, commitSpacingCarry, null, true);
    }

    // Eraser helpers

    public void drawEraserLine(Canvas bitmapCanvas, float x0, float y0, float x1, float y1, float strokeWidth) {
        eraserPaint.setStrokeWidth(strokeWidth);
        bitmapCanvas.drawLine(x0, y0, x1, y1, eraserPaint);
    }

    public void drawEraserPoint(Canvas bitmapCanvas, float x, float y, float strokeWidth) {
        eraserPaint.setStrokeWidth(strokeWidth);
        bitmapCanvas.drawPoint(x, y, eraserPaint);
    }

    // Carry-over distance

    private float spacingCarry       = 0f;
    private float commitSpacingCarry = 0f;

    public float getSpacingCarry() { return spacingCarry; }

    // Preview segment (renderer thread -> penPaint)

    public void drawPreviewSegment(Canvas canvas,
                                   float ax, float ay, float ap,
                                   float bx, float by, float bp,
                                   BrushDescriptor brush) {
        renderSegmentFlat(ax, ay, ap, bx, by, bp, canvas, brush, spacingCarry, null, false);
    }

    // Core splat loop.
    //   dirtyOut:      if non-null, expanded with the bounding box of every dab.
    //   useCommitPaint: true  -> stampCircleWithAlphaCommit (main thread, commitPaint)
    //                   false -> stampCircleWithAlpha        (renderer thread, penPaint)

    private float renderSegmentFlat(float ax, float ay, float ap,
                                    float bx, float by, float bp,
                                    Canvas target, BrushDescriptor brush,
                                    float carry, RectF dirtyOut,
                                    boolean useCommitPaint) {
        float wa = BrushResolver.resolveSize(brush, ap);
        float wb = BrushResolver.resolveSize(brush, bp);

        float dx    = bx - ax;
        float dy    = by - ay;
        float lenSq = dx * dx + dy * dy;

        float avgDiameter = (wa + wb) / 2f;
        float stepPx = Math.max(1f, avgDiameter * BrushResolver.resolveSpacingMultiplier(brush));

        // Dot / tap
        if (lenSq < 0.01f) {
            if (carry == 0f) {
                float oa = BrushResolver.resolveDabAlpha(brush, ap);
                float r  = wa / 2f;
                if (useCommitPaint) stampCircleWithAlphaCommit(target, ax, ay, r, oa, brush);
                else                stampCircleWithAlpha      (target, ax, ay, r, oa, brush);
                if (dirtyOut != null) dirtyOut.union(ax - r, ay - r, ax + r, ay + r);
            }
            return carry;
        }

        float len = (float) Math.sqrt(lenSq);

        float distAlongSeg = stepPx - carry;
        if (distAlongSeg > len) return carry + len;

        while (distAlongSeg <= len) {
            float t        = distAlongSeg / len;
            float x        = ax + dx * t;
            float y        = ay + dy * t;
            float radius   = (wa + (wb - wa) * t) / 2f;
            float pressure = ap + (bp - ap) * t;
            float alpha    = BrushResolver.resolveDabAlpha(brush, pressure);
            if (useCommitPaint) stampCircleWithAlphaCommit(target, x, y, radius, alpha, brush);
            else                stampCircleWithAlpha      (target, x, y, radius, alpha, brush);
            if (dirtyOut != null) dirtyOut.union(x - radius, y - radius, x + radius, y + radius);
            distAlongSeg += stepPx;
        }

        return len - (distAlongSeg - stepPx);
    }

    // Renderer thread stamp (penPaint)
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

    // Main thread stamp (commitPaint) - never touches penPaint
    private void stampCircleWithAlphaCommit(Canvas target, float x, float y, float radius,
                                            float alpha, BrushDescriptor brush) {
        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            commitPaint.setXfermode(CLEAR_XFERMODE);
            commitPaint.setAlpha(255);
        } else {
            commitPaint.setXfermode(null);
            commitPaint.setColor(brush.color);
            commitPaint.setAlpha((int)(alpha * 255));
        }
        target.drawCircle(x, y, radius, commitPaint);
    }

    // Helper used only by the eraser's first-point stamp (main thread)
    private void stampCircleCommit(Canvas target, float x, float y, float radius, BrushDescriptor brush) {
        stampCircleWithAlphaCommit(target, x, y, radius, 1f, brush);
    }

    // Scratch bitmap helpers

    private void ensureScratch(int w, int h) {
        if (scratchBitmap == null || scratchBitmap.getWidth() != w || scratchBitmap.getHeight() != h) {
            if (scratchBitmap != null) scratchBitmap.recycle();
            scratchBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            scratchCanvas = new Canvas(scratchBitmap);
        }
    }

    // Paint factories

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
