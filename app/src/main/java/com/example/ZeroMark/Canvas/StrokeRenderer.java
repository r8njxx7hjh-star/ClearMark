package com.example.ZeroMark.Canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import com.example.ZeroMark.Brushes.BrushDescriptor;
import com.example.ZeroMark.Brushes.ToolManager;

import java.util.List;

public class StrokeRenderer {

    private final Paint penPaint    = createPenPaint();
    private final Paint eraserPaint = createEraserPaint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // ─── Front buffer (live, per segment) ────────────────────────

    public void drawSegmentFrontBuffer(Canvas canvas, float[] segment, BrushDescriptor brush) {
        applyBrushToPaint(brush, segment[2]); // segment[2] = pressure of point A
        if (segment.length == 6) {
            float[] a = {segment[0], segment[1], segment[2]};
            float[] b = {segment[3], segment[4], segment[5]};
            renderSegment(a, b, canvas, brush);
        }
    }

    // ─── Multi buffer (full stroke replay on commit) ──────────────

    public void drawFullCanvas(Canvas canvas, Bitmap committed, List<float[]> liveStroke, BrushDescriptor brush) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        canvas.drawColor(Color.WHITE);
        if (committed != null) canvas.drawBitmap(committed, 0, 0, bitmapPaint);

        if (liveStroke.size() > 1) {
            float[] prev = null;
            for (float[] pt : liveStroke) {
                if (prev != null) renderSegment(prev, pt, canvas, brush);
                else              renderSegment(pt,  pt, canvas, brush);
                prev = pt;
            }
        }
    }

    // ─── Commit stroke to the persistent bitmap ───────────────────

    public void commitStroke(Canvas bitmapCanvas, List<float[]> points, BrushDescriptor brush) {
        float[] prev = null;
        for (float[] pt : points) {
            if (prev != null) renderSegment(prev, pt, bitmapCanvas, brush);
            else              renderSegment(pt,  pt, bitmapCanvas, brush);
            prev = pt;
        }
    }

    // ─── Eraser ───────────────────────────────────────────────────

    public void drawEraserLine(Canvas bitmapCanvas,
                               float x0, float y0,
                               float x1, float y1,
                               float strokeWidth) {
        eraserPaint.setStrokeWidth(strokeWidth);
        bitmapCanvas.drawLine(x0, y0, x1, y1, eraserPaint);
    }

    public void drawEraserPoint(Canvas bitmapCanvas, float x, float y, float strokeWidth) {
        eraserPaint.setStrokeWidth(strokeWidth);
        bitmapCanvas.drawPoint(x, y, eraserPaint);
    }

    // ─── Core geometry ────────────────────────────────────────────

    private void renderSegment(float[] a, float[] b, Canvas target, BrushDescriptor brush) {
        applyBrushToPaint(brush, (a[2] + b[2]) / 2f); // average pressure of the two points
        float wa = BrushResolver.resolveSize(brush, a[2]) / 2f;
        float wb = BrushResolver.resolveSize(brush, b[2]) / 2f;


        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx*dx + dy*dy);
        if (len < 0.5f) return;

        float nx = -dy / len, ny = dx / len;
        Path seg = new Path();
        seg.moveTo(a[0] + nx * wa, a[1] + ny * wa);
        seg.lineTo(b[0] + nx * wb, b[1] + ny * wb);
        seg.lineTo(b[0] - nx * wb, b[1] - ny * wb);
        seg.lineTo(a[0] - nx * wa, a[1] - ny * wa);
        seg.close();
        target.drawPath(seg, penPaint);
    }

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

    private void applyBrushToPaint(BrushDescriptor brush, float pressure) {
        float alpha = BrushResolver.resolveOpacity(brush, pressure); // 0..1

        if (brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
            penPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            penPaint.setAlpha(255);
        } else {
            penPaint.setXfermode(null);
            penPaint.setColor(brush.color);
            penPaint.setAlpha((int)(alpha * 255));
        }
    }
}