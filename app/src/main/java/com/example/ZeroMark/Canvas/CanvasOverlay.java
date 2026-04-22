package com.example.zeromark.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;

import com.example.zeromark.brushes.BrushDescriptor;
import com.example.zeromark.brushes.ToolManager;

public class CanvasOverlay extends View {

    private float cursorX, cursorY, cursorPressure;
    private final Matrix viewMatrix = new Matrix();

    private float tetherAx, tetherAy, tetherAp;
    private float tetherBx, tetherBy, tetherBp;

    private boolean eraserVisible  = false;
    private boolean previewVisible = false;
    private boolean frontBufferOwnsTether = false;

    private StrokeRenderer strokeRenderer;

    private final Paint cursorPaint;
    private final Paint layerPaint = new Paint();

    public CanvasOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setColor(Color.GRAY);
        cursorPaint.setStrokeWidth(4f);
    }

    public void setViewMatrix(Matrix matrix) {
        this.viewMatrix.set(matrix);
        invalidate();
    }

    public void setStrokeRenderer(StrokeRenderer renderer) {
        this.strokeRenderer = renderer;
    }

    public void updateCursor(float x, float y, float pressure) {
        // x, y are screen coords
        cursorX = x; cursorY = y; cursorPressure = pressure;
        eraserVisible = true;
        invalidate();
    }

    public void hideCursor() {
        eraserVisible = false;
        invalidate();
    }

    public void setFrontBufferOwnsTether(boolean owns) {
        frontBufferOwnsTether = owns;
    }

    public void updateTether(float prevX, float prevY,
                             float ax, float ay, float ap,
                             float bx, float by, float bp,
                             float carry) {
        // ax, ay, bx, by are canvas coords
        tetherAx = ax; tetherAy = ay; tetherAp = ap;
        tetherBx = bx; tetherBy = by; tetherBp = bp;
        previewVisible = true;
        invalidate();
    }

    public void hideTether() {
        previewVisible = false;
        frontBufferOwnsTether = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Visual clues (cursor and tether) removed per user request.
    }
}
