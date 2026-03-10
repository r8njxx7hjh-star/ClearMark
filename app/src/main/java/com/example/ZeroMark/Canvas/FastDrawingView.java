package com.example.ZeroMark.Canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer;

import com.example.ZeroMark.Brushes.ToolManager;

import java.util.Collection;
import java.util.List;

public class FastDrawingView extends SurfaceView implements SurfaceHolder.Callback {

    private CanvasFrontBufferedRenderer<float[]> frontRenderer;
    private Bitmap        canvasBitmap;
    private Canvas        bitmapCanvas;
    private CanvasOverlay canvasOverlay;
    private final Paint   bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final UndoManager        undoManager  = new UndoManager();
    private final StrokeInputHandler inputHandler = new StrokeInputHandler();
    private final StrokeRenderer     renderer     = new StrokeRenderer();
    private final ToolManager        toolManager  = ToolManager.getInstance();

    private volatile boolean pendingCommit  = false;
    private long             lastCommitTime = 0;

    // =================================================================

    public FastDrawingView(android.content.Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
        wireInputHandler();
    }

    // ─── Wire StrokeInputHandler callbacks ───────────────────────

    private void wireInputHandler() {
        inputHandler.setListener(new StrokeInputHandler.Listener() {

            @Override
            public void onSegmentReady(float[] segment) {
                // Front buffer — drawn immediately for low latency
                if (frontRenderer != null) {
                    frontRenderer.renderFrontBufferedLayer(segment);
                }
            }

            @Override
            public void onStrokeComplete(List<float[]> points) {
                // Bake the finished stroke into the persistent bitmap
                renderer.commitStroke(
                        bitmapCanvas,
                        points,
                        toolManager.getActiveBrush()
                );
                if (frontRenderer != null) frontRenderer.commit();
            }
        });
    }

    // ─── Surface lifecycle ────────────────────────────────────────

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        int w = Math.max(getWidth(), 1);
        int h = Math.max(getHeight(), 1);
        if (canvasBitmap == null) {
            canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmapCanvas = new Canvas(canvasBitmap);
        }
        frontRenderer = new CanvasFrontBufferedRenderer<>(this, rendererCallbacks);
        requestFocus();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) {
        Bitmap fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (canvasBitmap != null) {
            new Canvas(fresh).drawBitmap(canvasBitmap, null,
                    new android.graphics.RectF(0, 0, w, h), bitmapPaint);
            canvasBitmap.recycle();
        }
        canvasBitmap = fresh;
        bitmapCanvas = new Canvas(canvasBitmap);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (frontRenderer != null) {
            frontRenderer.release(true);
            frontRenderer = null;
        }
    }

    // ─── Front buffered renderer callbacks ───────────────────────

    private final CanvasFrontBufferedRenderer.Callback<float[]> rendererCallbacks =
            new CanvasFrontBufferedRenderer.Callback<float[]>() {

                @Override
                public void onDrawFrontBufferedLayer(
                        @NonNull Canvas canvas,
                        int bufferWidth, int bufferHeight,
                        @NonNull float[] segment) {

                    renderer.drawSegmentFrontBuffer(
                            canvas,
                            segment,
                            toolManager.getActiveBrush()
                    );
                }

                @Override
                public void onDrawMultiBufferedLayer(
                        @NonNull Canvas canvas,
                        int w, int h,
                        @NonNull Collection<? extends float[]> collection) {

                    renderer.drawFullCanvas(
                            canvas,
                            canvasBitmap,
                            inputHandler.getStrokePoints(),
                            toolManager.getActiveBrush()
                    );
                }
            };

    // ─── Touch dispatch ───────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) requestFocus();
        if (event.getToolType(event.getActionIndex()) != MotionEvent.TOOL_TYPE_STYLUS) return true;

        switch (toolManager.getCurrentToolType()) {
            case PEN:    handlePenTouch(event);    break;
            case ERASER: handleEraserTouch(event); break;
        }
        return true;
    }

    // ─── Pen touch ────────────────────────────────────────────────
    // StrokeInputHandler does all the heavy lifting —
    // this is just translating MotionEvent actions into handler calls

    private void handlePenTouch(MotionEvent event) {
        if (frontRenderer == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                undoManager.push(canvasBitmap);
                inputHandler.onDown(event);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                inputHandler.onMove(event);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                inputHandler.onUp();
                overlayHideLinePreview();
                break;
            }
        }
    }

    // ─── Eraser touch ─────────────────────────────────────────────
    // Eraser is just a brush with BlendMode.CLEAR —
    // same input pipeline, StrokeRenderer handles the xfermode difference

    private void handleEraserTouch(MotionEvent event) {
        if (frontRenderer == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                undoManager.push(canvasBitmap);
                inputHandler.onDown(event);
                overlayUpdateCursor(event.getX(), event.getY(), event.getPressure());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                inputHandler.onMove(event);
                overlayUpdateCursor(event.getX(), event.getY(), event.getPressure());

                // Eraser commits more eagerly than pen so cleared areas
                // appear immediately without waiting for ACTION_UP
                long now = SystemClock.elapsedRealtime();
                if (!pendingCommit && (now - lastCommitTime) > 32) {
                    pendingCommit = true;
                    lastCommitTime = now;
                    post(() -> {
                        if (frontRenderer != null) frontRenderer.commit();
                        pendingCommit = false;
                    });
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                inputHandler.onUp();
                overlayHideCursor();
                break;
            }
        }
    }

    // ─── Undo ─────────────────────────────────────────────────────

    public void undo() {
        undoManager.pop(bitmapCanvas);
        if (frontRenderer != null) frontRenderer.commit();
    }

    // ─── Hardware keys ────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP)  { undo(); return true; }
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return true;
        return super.onKeyUp(keyCode, event);
    }

    // ─── Overlay helpers ──────────────────────────────────────────

    public void setCanvasOverlay(CanvasOverlay overlay) { this.canvasOverlay = overlay; }

    private void overlayUpdateCursor(float x, float y, float pressure) {
        if (canvasOverlay != null) canvasOverlay.updateCursor(x, y, pressure);
    }
    private void overlayHideCursor() {
        if (canvasOverlay != null) canvasOverlay.hideCursor();
    }
    private void overlayHideLinePreview() {
        if (canvasOverlay != null) canvasOverlay.hideLinePreview();
    }
}
