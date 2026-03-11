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
    // True only while a pen stroke is actively in progress (ACTION_DOWN
    // through ACTION_UP/CANCEL). This is the single gate that controls
    // whether onDrawFrontBufferedLayer renders anything.
    //
    // When false (i.e. after the pen lifts and commit() is called),
    // every queued front-buffer callback — however many the framework
    // fires — just clears to transparent and returns. The multi-buffer
    // layer already has the fully baked stroke, so clearing the front
    // buffer is seamless. No suppress signal, no generation counter,
    // no one-shot race window.
    //
    // Written on the main thread only; read on the renderer thread.
    // AtomicBoolean for cross-thread visibility without locks.
    private final AtomicBoolean isPenDown = new AtomicBoolean(false);

    // ─── Opacity double-draw guard ───────────────────────────────
    // After commitStroke bakes the stroke into canvasBitmap, the next
    // onDrawMultiBufferedLayer must skip segment replay — the stroke is
    // already in the bitmap and replaying would double the opacity.
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
                // data = float[9]: smoothSeg(6) + rawTip(3)
                if (frontRenderer == null) return;

                if (toolManager.getCurrentToolType() == ToolManager.ToolType.ERASER) {
                    // Eraser only needs the smoothed segment — extract first 6 floats
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
                    // Push smoothed segment to the front buffer (incremental, no flicker)
                    frontRenderer.renderFrontBufferedLayer(data);
                    // Update the overlay tether:
                    //   data[0,1]   = previous smoothed point (gives stroke direction for the Bézier)
                    //   data[3,4,5] = current smoothed tip (tether start)
                    //   data[6,7,8] = raw touch position  (tether end)
                    //   getSpacingCarry() ensures tether dabs land where the committed stroke extends,
                    //                    so there is no visual shift when the pen lifts.
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
                    renderer.commitStroke(bitmapCanvas, points, toolManager.getActiveBrush());

                    // Signal that the pen is up BEFORE calling commit().
                    // Every front-buffer callback the framework fires after this
                    // point — for this stroke or any queued remnants — will see
                    // isPenDown=false and just clear to transparent, harmlessly.
                    // The multi-buffer layer renders the fully baked result instead.
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
                        // Pen is up. Do NOT explicitly clear the front buffer here.
                        //
                        // The commit() call in onStrokeComplete resets the front buffer
                        // at the SurfaceControl layer atomically alongside the multi-buffer
                        // update — we don't need to do it manually.
                        //
                        // If we clear early (canvas.drawColor TRANSPARENT), the front
                        // buffer goes transparent before the multi-buffer has rendered the
                        // committed bitmap. For a quick stroke all segments are still queued
                        // when commit() fires, so all their onDrawFrontBufferedLayer callbacks
                        // run before onDrawMultiBufferedLayer — clearing the front buffer for
                        // every one creates a multi-frame window where the stroke is invisible.
                        // That's the flicker, and it's worse the faster the stroke.
                        //
                        // By returning without drawing, the front buffer retains whatever
                        // content it last rendered (the full live stroke). The framework then
                        // atomically transitions: front buffer hidden + multi-buffer shows the
                        // committed bitmap. No gap, no flicker.
                        //
                        // liveBitmap doesn't need explicit clearing here — beginLiveStroke()
                        // clears it at the start of every new stroke.
                        return;
                    }

                    // segment = float[9]: smoothSeg[0..5] + rawTip[6..8]
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

                    // ─── Flicker fix: skip segment replay during active strokes ───
                    //
                    // CanvasFrontBufferedRenderer can flush buffered segments to the
                    // multi-buffer automatically mid-stroke (not just on commit()). When
                    // that happens, both layers would show the same dabs simultaneously:
                    //
                    //   Multi-buffer: committed bitmap + replayed segments at opacity
                    //   Front buffer: liveBitmap (same dabs)          at opacity
                    //
                    // They composite to roughly double opacity. Every flush then causes
                    // a visible jump between single- and double-opacity — the flicker.
                    //
                    // Fix: while the pen is down, skip segment replay entirely. The
                    // front buffer's liveBitmap handles all live-stroke display. The
                    // multi-buffer only needs to show the committed (baked) background.
                    // At stroke end, isPenDown is false and strokeJustCommitted is true,
                    // so we also skip replay there — commitStroke already baked the
                    // stroke into canvasBitmap.
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
                isPenDown.set(true);
                undoManager.push(canvasBitmap);
                renderer.beginLiveStroke(
                        Math.max(getWidth(), 1),
                        Math.max(getHeight(), 1)
                );
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
                inputHandler.onDown(event);
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

    public void setCanvasOverlay(CanvasOverlay overlay) { this.canvasOverlay = overlay; }

    private void overlayUpdateCursor(float x, float y, float pressure) {
        if (canvasOverlay != null) canvasOverlay.updateCursor(x, y, pressure);
    }
    private void overlayHideCursor() {
        if (canvasOverlay != null) canvasOverlay.hideCursor();
    }

}
