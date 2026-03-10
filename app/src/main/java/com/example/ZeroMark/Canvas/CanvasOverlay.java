package com.example.ZeroMark.Canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.example.ZeroMark.Brushes.ToolManager;

public class CanvasOverlay extends View {

    private float cursorX, cursorY, cursorPressure;
    private float lineX, lineY, smoothX, smoothY, lineWidth;
    private int lineColor;
    private boolean eraserVisible = false;
    private boolean linePreviewVisible = false;

    private final Paint cursorPaint;
    private final Paint linePreviewPaint;

    public CanvasOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setColor(Color.GRAY);
        cursorPaint.setStrokeWidth(4f);

        linePreviewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePreviewPaint.setStyle(Paint.Style.STROKE);
    }

    public void updateCursor(float x, float y, float pressure) {
        cursorX = x;
        cursorY = y;
        cursorPressure = pressure;
        eraserVisible = true;
        invalidate();
    }

    public void hideCursor() {
        eraserVisible = false;
        invalidate();
    }

    public void updateLinePreview(float x, float y, float sx, float sy, float width, int color) {
        linePreviewVisible = true;
        lineX = x;
        lineY = y;
        smoothX = sx;
        smoothY = sy;
        lineWidth = width;
        lineColor = color;
        invalidate();
    }

    public void hideLinePreview() {
        linePreviewVisible = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (eraserVisible) {
            float r = BrushResolver.resolveSize(ToolManager.getInstance().getActiveBrush(), cursorPressure);
            canvas.drawCircle(cursorX, cursorY, r / 2f, cursorPaint);
        }
        if (linePreviewVisible) {
            linePreviewPaint.setStrokeWidth(lineWidth);
            linePreviewPaint.setColor(lineColor);
            canvas.drawLine(lineX, lineY, smoothX, smoothY, linePreviewPaint);
        }
    }
}