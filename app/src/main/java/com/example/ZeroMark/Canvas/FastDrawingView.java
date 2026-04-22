package com.example.zeromark.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer;

import com.example.zeromark.brushes.BrushDescriptor;
import com.example.zeromark.brushes.ToolManager;
import com.example.zeromark.canvas.model.CanvasModel;
import com.example.zeromark.canvas.model.Stroke;
import com.example.zeromark.model.CanvasSettings;
import com.example.zeromark.model.CanvasType;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FastDrawingView extends SurfaceView implements SurfaceHolder.Callback {

    private CanvasFrontBufferedRenderer<float[]> frontRenderer;
    private CanvasOverlay canvasOverlay;
    
    private final CanvasModel canvasModel;
    private final Matrix viewMatrix = new Matrix();
    private final Matrix inverseMatrix = new Matrix();
    
    private final StrokeInputHandler inputHandler = new StrokeInputHandler();
    private final StrokeRenderer     renderer     = new StrokeRenderer();
    private final ToolManager        toolManager  = ToolManager.getInstance();
    private final CanvasSettings     settings;

    private final AtomicBoolean isPenDown = new AtomicBoolean(false);
    private final AtomicInteger strokeGen = new AtomicInteger(0);

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    public FastDrawingView(Context context, CanvasSettings settings) {
        super(context);
        this.settings = settings;
        this.canvasModel = new CanvasModel(settings);
        this.canvasModel.setOnTileUpdatedListener(() -> {
            post(() -> {
                if (frontRenderer != null) frontRenderer.commit();
            });
        });
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
        wireInputHandler();

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new PanListener());
        
        // Initial zoom/position for A4
        if (settings.getType() == CanvasType.A4_VERTICAL || settings.getType() == CanvasType.A4_HORIZONTAL) {
            viewMatrix.postTranslate(100, 100);
            viewMatrix.postScale(0.5f, 0.5f, 0, 0);
        }
        viewMatrix.invert(inverseMatrix);
        inputHandler.setInverseMatrix(inverseMatrix);
    }

    private void wireInputHandler() {
        inputHandler.setListener(new StrokeInputHandler.Listener() {
            @Override
            public void onSegmentReady(float[] data) {
                // Record session on UI thread immediately to guarantee it's captured
                // even if front-buffer rendering is skipped or delayed.
                renderer.getSession().record(data[0], data[1], data[2], data[3], data[4], data[5]);

                if (frontRenderer == null) return;

                BrushDescriptor brush = toolManager.getActiveBrush();
                if (brush != null && brush.blendMode == BrushDescriptor.BlendMode.CLEAR) {
                    // For erasers, we force a commit to the multi-buffered layer on every
                    // segment. This allows the eraser to "punch through" existing strokes
                    // to reveal the grid in real-time.
                    frontRenderer.commit();
                } else {
                    frontRenderer.renderFrontBufferedLayer(data);
                }
            }

            @Override
            public void onStrokeComplete(List<float[]> points) {
                isPenDown.set(false);

                BrushDescriptor brush = toolManager.getActiveBrush();
                if (brush != null) {
                    List<float[]> segments = renderer.getSession().snapshot();
                    if (!segments.isEmpty()) {
                        // Flatten segments for efficient storage
                        float[] flat = new float[segments.size() * 6];
                        for (int i = 0; i < segments.size(); i++) {
                            System.arraycopy(segments.get(i), 0, flat, i * 6, 6);
                        }
                        
                        android.graphics.RectF bounds = canvasModel.calculateStrokeBounds(flat, brush);
                        canvasModel.addStroke(new Stroke(flat, brush.copy(), bounds));
                    }
                }
                if (frontRenderer != null) frontRenderer.commit();
            }
        });
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        frontRenderer = new CanvasFrontBufferedRenderer<>(this, rendererCallbacks);
        requestFocus();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int w, int h) {
    }

    public void release() {
        if (frontRenderer != null) {
            frontRenderer.release(true);
            frontRenderer = null;
        }
        canvasModel.release();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (frontRenderer != null) {
            frontRenderer.release(true);
            frontRenderer = null;
        }
    }

    private final CanvasFrontBufferedRenderer.Callback<float[]> rendererCallbacks =
            new CanvasFrontBufferedRenderer.Callback<float[]>() {

                @Override
                public void onDrawFrontBufferedLayer(
                        @NonNull Canvas canvas,
                        int bufferWidth, int bufferHeight,
                        @NonNull float[] segment) {

                    if (!isPenDown.get()) return;
                    if (segment.length < 10 || (int) segment[9] != strokeGen.get()) return;

                    BrushDescriptor brush = toolManager.getActiveBrush();
                    if (brush == null) return;

                    canvas.save();
                    canvas.concat(viewMatrix);
                    renderer.drawSegmentFrontBuffer(canvas, segment, brush);
                    canvas.restore();
                }

                @Override
                public void onDrawMultiBufferedLayer(
                        @NonNull Canvas canvas,
                        int w, int h,
                        @NonNull Collection<? extends float[]> collection) {

                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    canvas.drawColor(Color.WHITE);

                    // 1. Draw the grid first directly onto the background
                    Matrix inverse = new Matrix();
                    viewMatrix.invert(inverse);
                    RectF visibleRect = new RectF(0, 0, w, h);
                    inverse.mapRect(visibleRect);
                    float[] matrixValues = new float[9];
                    viewMatrix.getValues(matrixValues);
                    float scale = matrixValues[Matrix.MSCALE_X];

                    canvas.save();
                    canvas.concat(viewMatrix);
                    canvasModel.drawGrid(canvas, visibleRect, scale);
                    canvas.restore();

                    // 2. Open a saveLayer to isolate all stroke drawing.
                    // This is essential for the eraser (CLEAR) to reveal the grid
                    // instead of punching to the window background.
                    int count = canvas.saveLayer(null, null);

                    // 3. Draw persistent strokes without grid
                    canvasModel.draw(canvas, viewMatrix, w, h, false);

                    // 4. Draw the live stroke
                    if (isPenDown.get()) {
                        BrushDescriptor brush = toolManager.getActiveBrush();
                        if (brush != null) {
                            List<float[]> segments = renderer.getSession().snapshot();

                            if (!segments.isEmpty()) {
                                // Flatten segments for drawing
                                float[] flat = new float[segments.size() * 6];
                                for (int i = 0; i < segments.size(); i++) {
                                    System.arraycopy(segments.get(i), 0, flat, i * 6, 6);
                                }

                                // These segments are already mapped to canvas space by inputHandler
                                canvas.save();
                                canvas.concat(viewMatrix);
                                renderer.drawFullCanvas(canvas, null, flat, brush);
                                canvas.restore();
                            }
                        }
                    }

                    // 5. Restore — composite the strokes layer onto the grid
                    canvas.restoreToCount(count);
                }
            };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) requestFocus();
        
        boolean isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS;

        if (isStylus) {
            handleStylusTouch(event);
            return true;
        } else {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);
            return true;
        }
    }

    private void handleStylusTouch(MotionEvent event) {
        if (frontRenderer == null) return;
        
        float[] values = new float[9];
        viewMatrix.getValues(values);
        float scale = values[Matrix.MSCALE_X];
        boolean isZoomedOutFar = scale < 0.35f;

        // Navigation mode: if zoomed out far, stylus pans instead of drawing
        if (isZoomedOutFar) {
            gestureDetector.onTouchEvent(event);
            if (canvasOverlay != null) canvasOverlay.hideCursor();
            return;
        }

        if (toolManager.getActiveBrush() == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int gen = strokeGen.incrementAndGet();
                isPenDown.set(true);
                renderer.beginLiveStroke();
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
                break;
            }
        }
    }

    public interface ZoomListener {
        void onZoomChanged(float zoom);
    }
    
    private ZoomListener zoomListener;

    public void setZoomListener(ZoomListener listener) {
        this.zoomListener = listener;
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scale = detector.getScaleFactor();
            float[] values = new float[9];
            viewMatrix.getValues(values);
            float currentScale = values[Matrix.MSCALE_X];
            
            float targetScale = currentScale * scale;
            if (targetScale < 0.2f) scale = 0.2f / currentScale;
            if (targetScale > 16.0f) scale = 16.0f / currentScale;

            viewMatrix.postScale(scale, scale, detector.getFocusX(), detector.getFocusY());
            viewMatrix.invert(inverseMatrix);
            inputHandler.setInverseMatrix(inverseMatrix);
            if (canvasOverlay != null) canvasOverlay.setViewMatrix(viewMatrix);
            if (frontRenderer != null) frontRenderer.commit();
            
            if (zoomListener != null) {
                viewMatrix.getValues(values);
                zoomListener.onZoomChanged(values[Matrix.MSCALE_X]);
            }
            return true;
        }
    }

    private class PanListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            float dx = -distanceX;
            float dy = -distanceY;
            if (settings.getType() == CanvasType.A4_VERTICAL) dx = 0;
            if (settings.getType() == CanvasType.A4_HORIZONTAL) dy = 0;

            viewMatrix.postTranslate(dx, dy);
            viewMatrix.invert(inverseMatrix);
            inputHandler.setInverseMatrix(inverseMatrix);
            if (canvasOverlay != null) canvasOverlay.setViewMatrix(viewMatrix);
            if (frontRenderer != null) frontRenderer.commit();
            return true;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }

    public void setCanvasOverlay(CanvasOverlay overlay) {
        this.canvasOverlay = overlay;
        overlay.setStrokeRenderer(renderer);
        overlay.setViewMatrix(viewMatrix);
    }

    private void overlayUpdateCursor(float x, float y, float pressure) {
        if (canvasOverlay != null) canvasOverlay.updateCursor(x, y, pressure);
    }
    private void overlayHideCursor() {
        if (canvasOverlay != null) canvasOverlay.hideCursor();
    }
}
