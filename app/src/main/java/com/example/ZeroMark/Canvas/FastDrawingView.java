package com.example.ZeroMark.Canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer;

import com.example.ZeroMark.Brushes.ToolManager;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ─── isPenDown ────────────────────────────────────────────────
    // True only while a pen stroke is actively in progress.
    // Written on the main thread; read on the renderer thread.
    private final AtomicBoolean isPenDown = new AtomicBoolean(false);

    // ─── strokeGen ────────────────────────────────────────────────
    // Incremented on every ACTION_DOWN. Each segment carries the generation
    // at emit time (data[9]). onDrawFrontBufferedLayer rejects any segment
    // whose generation doesn't match the current value.
    //
    // This eliminates the flicker race that occurs when a new stroke starts
    // before the renderer has drained the previous stroke's callback queue:
    //
    //   Main thread (stroke N+1 ACTION_DOWN):
    //     strokeGen++          ← N+1
    //     isPenDown = true
    //     beginLiveStroke()    ← clears liveBitmap
    //
    //   Renderer thread (draining stroke N callbacks):
    //     onDrawFrontBufferedLayer(segmentN)
    //       isPenDown = true  ← passes, would draw
    //       segmentN[9] = N  != strokeGen (N+1) ← REJECTED ✓
    //       liveBitmap is clean, nothing drawn, no flicker
    //
    // Without this guard the renderer reads isPenDown=true (set for the new
    // stroke) and draws stroke N's dabs into the freshly cleared liveBitmap,
    // producing a transparent flash visible on screen.
    private final AtomicInteger strokeGen = new AtomicInteger(0);

    // ─── Opacity double-draw guard ───────────────────────────────
    private final AtomicBoolean strokeJustCommitted = new AtomicBoolean(false);

    // ─── Eraser debounce ─────────────────────────────────────────
    private boolean eraserCommitPending = false;

    // =================================================================

    public FastDrawingView(Context context) {
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
            public void onSegmentReady(float[] data) {
                // data = float[10]: smoothSeg(6) + rawTip(3) + gen(1)
                if (frontRenderer == null) return;

                if (toolManager.getCurrentToolType() == ToolManager.ToolType.ERASER) {
                    float[] seg = new float[]{data[0], data[1], data[2], data[3], data[4], data[5]};
                    renderer.applySegmentDirect(bitmapCanvas, seg, toolManager.getActiveBrush());
                    if (!eraserCommitPending) {
                        eraserCommitPending = true;
                        post(() -> {
                            eraserCommitPending = false;
                            if (frontRenderer != null) frontRenderer.commit();
                        });
                    }
                } else {
                    frontRenderer.renderFrontBufferedLayer(data);
                    if (canvasOverlay != null) {
                        canvasOverlay.updateTether(
                                data[0], data[1],
                                data[3], data[4], data[5],
                                data[6], data[7], data[8],
                                renderer.getSpacingCarry());
                    }
                }
            }

            @Override
            public void onStrokeComplete(List<float[]> points) {
                if (toolManager.getCurrentToolType() == ToolManager.ToolType.ERASER) {
                    if (frontRenderer != null) frontRenderer.commit();
                } else {
                    renderer.clearTether();
                    renderer.commitStroke(bitmapCanvas, points, toolManager.getActiveBrush());
                    isPenDown.set(false);
                    strokeJustCommitted.set(true);
                    if (frontRenderer != null) frontRenderer.commit();
                }
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

                    if (!isPenDown.get()) {
                        // Pen is up — front buffer retains its last content.
                        // The framework atomically transitions to the multi-buffer
                        // on commit(), so we must NOT clear here or a transparent
                        // gap appears between the front buffer going blank and the
                        // multi-buffer taking over.
                        return;
                    }

                    // Generation guard: reject callbacks from the previous stroke.
                    // segment[9] carries the gen at emit time; strokeGen holds the
                    // current gen (incremented at ACTION_DOWN). A mismatch means
                    // this is a stale callback from stroke N draining after stroke
                    // N+1 has already started and cleared liveBitmap.
                    if (segment.length < 10 || (int) segment[9] != strokeGen.get()) {
                        return;
                    }

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

                    Collection<? extends float[]> segments =
                            (strokeJustCommitted.getAndSet(false) || isPenDown.get())
                                    ? Collections.emptyList()
                                    : collection;

                    renderer.drawFullCanvas(
                            canvas,
                            canvasBitmap,
                            segments,
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

    private void handlePenTouch(MotionEvent event) {
        if (frontRenderer == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // Increment generation BEFORE isPenDown=true.
                // Any renderer callbacks still draining for the previous stroke
                // will now see a generation mismatch and return early, even if
                // isPenDown has already been set back to true for this new stroke.
                int gen = strokeGen.incrementAndGet();
                isPenDown.set(true);
                undoManager.push(canvasBitmap);
                renderer.beginLiveStroke(
                        Math.max(getWidth(), 1),
                        Math.max(getHeight(), 1)
                );
                if (canvasOverlay != null) canvasOverlay.setFrontBufferOwnsTether(true);
                inputHandler.onDown(event, gen);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                inputHandler.onMove(event);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                inputHandler.onUp();
                renderer.clearTether();
                if (canvasOverlay != null) canvasOverlay.hideTether();
                break;
            }
        }
    }

    // ─── Eraser touch ─────────────────────────────────────────────

    private void handleEraserTouch(MotionEvent event) {
        if (frontRenderer == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                undoManager.push(canvasBitmap);
                inputHandler.onDown(event, strokeGen.get()); // gen unused by eraser path
                overlayUpdateCursor(event.getX(), event.getY(), event.getPressure());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                inputHandler.onMove(event);
                overlayUpdateCursor(event.getX(), event.getY(), event.getPressure());
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

    public void setCanvasOverlay(CanvasOverlay overlay) {
        this.canvasOverlay = overlay;
        overlay.setStrokeRenderer(renderer);
    }

    private void overlayUpdateCursor(float x, float y, float pressure) {
        if (canvasOverlay != null) canvasOverlay.updateCursor(x, y, pressure);
    }
    private void overlayHideCursor() {
        if (canvasOverlay != null) canvasOverlay.hideCursor();
    }
}
