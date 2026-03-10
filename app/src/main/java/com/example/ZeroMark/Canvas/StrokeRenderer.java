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

    // ─── Front buffer (live, per segment) ────────────────────────
    // O(1): only stamps the new segment's dabs onto liveBitmap, then
    // draws ONLY liveBitmap at stroke opacity onto the front buffer canvas.
    //
    // NOTE: eraser never calls beginLiveStroke so liveBitmap == null for
    // eraser strokes. This method returns early — erasing is handled
    // entirely by the eager frontRenderer.commit() calls in FastDrawingView,
    // which trigger drawFullCanvas where CLEAR is applied directly.

    public void drawSegmentFrontBuffer(Canvas canvas, float[] segment, BrushDescriptor brush) {
        if (segment.length != 6) return;
        if (liveBitmap == null) return;

        // Accumulate new segment dabs onto live bitmap at full alpha
        renderSegmentFlat(segment[0], segment[1], segment[2],
                          segment[3], segment[4], segment[5],
                          liveCanvas, brush);

        // Clear the front buffer to transparent (not white — the multi-buffer
        // layer underneath already has the committed content as background)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        // Composite live stroke at stroke opacity on top
        float pressure = (segment[2] + segment[5]) / 2f;
        float alpha = BrushResolver.resolveOpacity(brush, pressure);
        livePaint.setAlpha((int)(alpha * 255));
        canvas.drawBitmap(liveBitmap, 0, 0, livePaint);
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
            for (float[] seg : segments) {
                renderSegmentFlat(seg[0], seg[1], seg[2],
                                  seg[3], seg[4], seg[5],
                                  canvas, brush);
            }
            return;
        }

        int size = segments.size(), midIdx = size / 2, i = 0;
        float midPressure = 0.5f;
        for (float[] seg : segments) {
            if (i++ == midIdx) { midPressure = (seg[2] + seg[5]) / 2f; break; }
        }

        float alpha = BrushResolver.resolveOpacity(brush, midPressure);
        layerPaint.setAlpha((int)(alpha * 255));
        canvas.saveLayer(null, layerPaint);

        for (float[] seg : segments) {
            renderSegmentFlat(seg[0], seg[1], seg[2],
                              seg[3], seg[4], seg[5],
                              canvas, brush);
        }
        canvas.restore();
    }

    // ─── Commit stroke to the persistent bitmap ───────────────────

    public void commitStroke(Canvas bitmapCanvas, List<float[]> points, BrushDescriptor brush) {
        if (points.isEmpty()) return;

        final boolean isClear = brush.blendMode == BrushDescriptor.BlendMode.CLEAR;

        if (isClear) {
            float[] prev = null;
            for (float[] pt : points) {
                if (prev != null) {
                    renderSegmentFlat(prev[0], prev[1], prev[2],
                                      pt[0],   pt[1],   pt[2],
                                      bitmapCanvas, brush);
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

        float midPressure = points.get(points.size() / 2)[2];
        float alpha = BrushResolver.resolveOpacity(brush, midPressure);

        float[] prev = null;
        for (float[] pt : points) {
            if (prev != null) {
                renderSegmentFlat(prev[0], prev[1], prev[2],
                                  pt[0],   pt[1],   pt[2],
                                  scratchCanvas, brush);
            } else {
                stampCircle(scratchCanvas, pt[0], pt[1],
                        BrushResolver.resolveSize(brush, pt[2]) / 2f, brush);
            }
            prev = pt;
        }

        compositePaint.setAlpha((int)(alpha * 255));
        bitmapCanvas.drawBitmap(scratchBitmap, 0, 0, compositePaint);
    }

    // ─── Direct segment apply (eraser live path) ─────────────────

    public void applySegmentDirect(Canvas canvas, float[] segment, BrushDescriptor brush) {
        if (segment.length < 6 || canvas == null || brush == null) return;
        renderSegmentFlat(segment[0], segment[1], segment[2],
                          segment[3], segment[4], segment[5],
                          canvas, brush);
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

    // ─── Core splat loop ──────────────────────────────────────────

    private void renderSegmentFlat(float ax, float ay, float ap,
                                   float bx, float by, float bp,
                                   Canvas target, BrushDescriptor brush) {
        float wa = BrushResolver.resolveSize(brush, ap);
        float wb = BrushResolver.resolveSize(brush, bp);

        float dx  = bx - ax;
        float dy  = by - ay;

        float avgDiameter = (wa + wb) / 2f;
        float stepPx      = Math.max(1f, avgDiameter * (brush.spacing / 100f));

        float lenSq = dx * dx + dy * dy;
        if (lenSq < stepPx * stepPx) {
            stampCircle(target,
                    (ax + bx) / 2f,
                    (ay + by) / 2f,
                    avgDiameter / 2f,
                    brush);
            return;
        }

        float len   = (float) Math.sqrt(lenSq);
        int   count = (int) Math.ceil(len / stepPx);
        for (int i = 0; i <= count; i++) {
            float t      = (float) i / count;
            float x      = ax + dx * t;
            float y      = ay + dy * t;
            float radius = (wa + (wb - wa) * t) / 2f;
            stampCircle(target, x, y, radius, brush);
        }
    }

    private void stampCircle(Canvas target, float x, float y, float radius, BrushDescriptor brush) {
        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            penPaint.setXfermode(CLEAR_XFERMODE);
            penPaint.setAlpha(255);
        } else {
            penPaint.setXfermode(null);
            penPaint.setColor(brush.color);
            penPaint.setAlpha(255);
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
