package com.example.ZeroMark.Canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.example.ZeroMark.Brushes.BrushDescriptor;
import com.example.ZeroMark.Brushes.ToolManager;

public class CanvasOverlay extends View {

    private float cursorX, cursorY, cursorPressure;

    // Procreate StreamLine model:
    //   tetherAx/Ay/Ap = smoothed ghost tip  (committed ink ends here)
    //   tetherBx/By/Bp = raw finger position (preview ends here)
    // The preview fills this gap with real brush dabs — identical to committed ink.
    private float tetherAx, tetherAy, tetherAp;
    private float tetherBx, tetherBy, tetherBp;

    private boolean eraserVisible  = false;
    private boolean previewVisible = false;

    // When true the front buffer is rendering the tether directly, so
    // CanvasOverlay must NOT also draw it (would double the opacity).
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

    public void setStrokeRenderer(StrokeRenderer renderer) {
        this.strokeRenderer = renderer;
    }

    public void updateCursor(float x, float y, float pressure) {
        cursorX = x; cursorY = y; cursorPressure = pressure;
        eraserVisible = true;
        invalidate();
    }

    public void hideCursor() {
        eraserVisible = false;
        invalidate();
    }

    /**
     * Signal that the front buffer is actively rendering the tether.
     * While true, CanvasOverlay skips its own tether draw to avoid
     * double-compositing at reduced brush opacity.
     */
    public void setFrontBufferOwnsTether(boolean owns) {
        frontBufferOwnsTether = owns;
    }

    // prevX/prevY and carry kept for call-site compatibility — unused by new renderer.
    public void updateTether(float prevX, float prevY,
                             float ax, float ay, float ap,
                             float bx, float by, float bp,
                             float carry) {
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

        if (eraserVisible) {
            BrushDescriptor brush = ToolManager.getInstance().getActiveBrush();
            if (brush != null) {
                float r = BrushResolver.resolveSize(brush, cursorPressure);
                canvas.drawCircle(cursorX, cursorY, r / 2f, cursorPaint);
            }
        }

        // If the front buffer is rendering the tether (normal pen strokes), skip
        // drawing it here — the front buffer already composites liveBitmap + tether
        // in one saveLayer so there is no opacity seam and no double-compositing.
        if (frontBufferOwnsTether || !previewVisible || strokeRenderer == null) return;

        BrushDescriptor brush = ToolManager.getInstance().getActiveBrush();
        if (brush == null) return;

        float dx = tetherBx - tetherAx;
        float dy = tetherBy - tetherAy;
        if (dx * dx + dy * dy < 1f) return;

        // Draw the gap (ghost tip to raw finger) using the real brush dab engine.
        // saveLayer at layer-alpha applies brush.opacity once to the whole preview,
        // matching how commitStroke composites — visually indistinguishable from ink.
        layerPaint.setAlpha((int)(BrushResolver.resolveLayerAlpha(brush) * 255));
        canvas.saveLayer(null, layerPaint);
        strokeRenderer.drawPreviewSegment(
                canvas,
                tetherAx, tetherAy, tetherAp,
                tetherBx, tetherBy, tetherBp,
                brush);
        canvas.restore();
    }
}
