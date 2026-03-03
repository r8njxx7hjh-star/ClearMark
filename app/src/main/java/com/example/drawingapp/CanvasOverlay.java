package com.example.drawingapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public class CanvasOverlay extends View {

    private float cursorX, cursorY, cursorPressure;
    private float lineX, lineY, smoothX, smoothY, linePressure;
    private int lineColor;
    private boolean EraserVisible = false;
    private boolean LinePreviewVisible = false;

    private final Paint cursorPaint, linePreview;

    public CanvasOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setColor(Color.GRAY);
        cursorPaint.setStrokeWidth(4f);

        linePreview = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePreview.setStyle(Paint.Style.STROKE);
    }

    public void updateCursor(float x, float y, float pressure) {
        cursorX = x;
        cursorY = y;
        cursorPressure = pressure;
        EraserVisible = true;
        invalidate();
    }

    public void hideCursor() {
        EraserVisible = false;
        invalidate();
    }

    public void updateLinePreview(float x, float y, float sx, float sy, float pressure, int color) {
        LinePreviewVisible = true;
        lineX = x;
        lineY = y;
        smoothX = sx;
        smoothY = sy;
        linePressure = pressure;
        lineColor = color;
        invalidate();
    }

    public void hideLinePreview() {
        LinePreviewVisible = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (EraserVisible) {
            float r = 20f + cursorPressure * 30f;
            canvas.drawCircle(cursorX, cursorY, r / 2, cursorPaint);
        }
        if (LinePreviewVisible) {
            linePreview.setStrokeWidth(linePressure);
            linePreview.setColor(lineColor);
            canvas.drawLine(lineX, lineY, smoothX, smoothY, linePreview);
        }
    }


}