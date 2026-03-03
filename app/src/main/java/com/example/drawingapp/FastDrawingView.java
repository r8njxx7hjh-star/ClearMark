package com.example.drawingapp;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collection;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer;

import com.example.drawingapp.tools.Eraser;
import com.example.drawingapp.tools.Pen;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class FastDrawingView extends SurfaceView implements SurfaceHolder.Callback {

    // ─── Renderer ─────────────────────────────────────────────────
    private CanvasFrontBufferedRenderer<float[]> frontRenderer;

    // ─── Committed canvas (multi-buffered layer) ──────────────────
    private Bitmap canvasBitmap;
    private Canvas bitmapCanvas;
    private CanvasOverlay canvasOverlay;

    // ─── Live stroke accumulator ──────────────────────────────────
    private final List<float[]> strokePoints = new CopyOnWriteArrayList<>();

    // ─── Undo ─────────────────────────────────────────────────────
    private final Deque<Bitmap> undoStack = new ArrayDeque<>();
    private static final int MAX_UNDO = 120;

    // ─── Active stroke tracking ───────────────────────────────────
    private int currentPointerId = -1;
    private float[] lastPoint = null; // [x, y, pressure]

    // ─── Eraser state & throttling ────────────────────────────────
    private volatile float eraserX, eraserY, eraserPressure;
    private float lastEraserX, lastEraserY;
    private long lastCommitTime = 0;

    // ─── Tool manager ─────────────────────────────────────────────
    private final ToolManager toolManager = ToolManager.getInstance();

    // ─── Paints ───────────────────────────────────────────────────
    private final Paint penPaint   = createPenPaint();
    private final Paint eraserPaint = createEraserPaint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private volatile boolean pendingCommit = false;

    // =================================================================

    public FastDrawingView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
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

    public void setCanvasOverlay(CanvasOverlay overlay) {
        this.canvasOverlay = overlay;
    }

    // ─── CanvasOverlay helpers ────────────────────────────────────

    private void overlayUpdateLinePreview(float x, float y, float sx, float sy, float width, int color) {
        if (canvasOverlay != null) canvasOverlay.updateLinePreview(x, y, sx, sy, width, color);
    }
    private void overlayUpdateCursor(float x, float y, float pressure) {
        if (canvasOverlay != null) canvasOverlay.updateCursor(x, y, pressure);
    }
    private void overlayHideCursor() {
        if (canvasOverlay != null) canvasOverlay.hideCursor();
    }
    private void overlayHideLinePreview() {
        if (canvasOverlay != null) canvasOverlay.hideLinePreview();
    }

    // ─── CanvasFrontBufferedRenderer callbacks ────────────────────

    private final CanvasFrontBufferedRenderer.Callback<float[]> rendererCallbacks =
            new CanvasFrontBufferedRenderer.Callback<float[]>() {

                @Override
                public void onDrawMultiBufferedLayer(
                        @NonNull Canvas canvas,
                        int i, int i1,
                        @NonNull Collection<? extends float[]> collection) {

                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    canvas.drawColor(Color.WHITE);
                    if (canvasBitmap != null) canvas.drawBitmap(canvasBitmap, 0, 0, bitmapPaint);

                    if (toolManager.getCurrentToolType() == ToolManager.ToolType.PEN && strokePoints.size() > 1) {
                        penPaint.setColor(toolManager.getActivePen().getColor());
                        float[] prev = null;
                        for (float[] pt : strokePoints) {
                            if (prev != null) renderSegment(prev, pt, canvas);
                            else renderSegment(pt, pt, canvas);
                            prev = pt;
                        }
                    }
                }

                @Override
                public void onDrawFrontBufferedLayer(
                        @NonNull Canvas canvas,
                        int bufferWidth,
                        int bufferHeight,
                        @NonNull float[] segment) {

                    penPaint.setColor(toolManager.getActivePen().getColor());
                    if (segment.length == 6) {
                        float[] a = {segment[0], segment[1], segment[2]};
                        float[] b = {segment[3], segment[4], segment[5]};
                        renderSegment(a, b, canvas);
                    }
                }
            };

    // ─── Touch dispatch ───────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
        int idx = event.findPointerIndex(currentPointerId);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pushUndo();
                currentPointerId = event.getPointerId(event.getActionIndex());
                strokePoints.clear();
                lastPoint = null;
                consumePoint(event.getX(), event.getY(), event.getPressure());
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (currentPointerId == -1 || idx < 0) break;
                int hist = event.getHistorySize();
                for (int h = 0; h < hist; h++) {
                    consumePoint(
                            event.getHistoricalX(idx, h),
                            event.getHistoricalY(idx, h),
                            event.getHistoricalPressure(idx, h));
                }
                consumePoint(event.getX(idx), event.getY(idx), event.getPressure(idx));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (currentPointerId == -1) break;
                commitStrokeToBitmap();
                overlayHideLinePreview();
                frontRenderer.commit();
                strokePoints.clear();
                currentPointerId = -1;
                lastPoint = null;
                break;
            }
        }
    }

    // ─── Stroke helpers ───────────────────────────────────────────

    private float calculateSmoothingFactor(float smoothing) {
        return 1f - (smoothing / 100f * 0.95f);
    }

    private void consumePoint(float x, float y, float pressure) {
        float p = Math.max(0.02f, pressure);
        Pen pen = toolManager.getActivePen();

        if (lastPoint == null) {
            lastPoint = new float[]{x, y, p};
            strokePoints.add(lastPoint.clone());
            frontRenderer.renderFrontBufferedLayer(new float[]{x, y, p, x, y, p});
            return;
        }

        float sf = calculateSmoothingFactor(pen.getSmoothing());
        float sx = lastPoint[0] + (x - lastPoint[0]) * sf;
        float sy = lastPoint[1] + (y - lastPoint[1]) * sf;
        float sp = lastPoint[2] + (p - lastPoint[2]) * Math.min(sf + 0.2f, 1f);

        float[] newPt   = {sx, sy, sp};
        float[] segment = {lastPoint[0], lastPoint[1], lastPoint[2], newPt[0], newPt[1], newPt[2]};

        overlayUpdateLinePreview(x, y, sx, sy, calculatePressureStrokeWidth(p, pen), penPaint.getColor());

        strokePoints.add(newPt.clone());
        lastPoint = newPt;
        frontRenderer.renderFrontBufferedLayer(segment);
    }

    private void commitStrokeToBitmap() {
        if (bitmapCanvas == null) return;
        penPaint.setColor(toolManager.getActivePen().getColor());
        float[] prev = null;
        for (float[] pt : strokePoints) {
            if (prev != null) renderSegment(prev, pt, bitmapCanvas);
            else renderSegment(pt, pt, bitmapCanvas);
            prev = pt;
        }
    }

    // ─── Eraser touch ─────────────────────────────────────────────

    private void handleEraserTouch(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        float pressure = event.getPressure();
        Eraser eraser = toolManager.getActiveEraser();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pushUndo();
                eraserX = x; eraserY = y;
                lastEraserX = x; lastEraserY = y;
                eraserPressure = pressure;
                lastCommitTime = SystemClock.elapsedRealtime();

                eraserPaint.setStrokeWidth(calculateEraserStrokeWidth(eraserPressure, eraser));
                if (bitmapCanvas != null) bitmapCanvas.drawPoint(x, y, eraserPaint);
                if (frontRenderer != null) frontRenderer.commit();
                overlayUpdateCursor(eraserX, eraserY, eraserPressure);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float sf = calculateSmoothingFactor(eraser.getSmoothing());
                eraserX += (x - eraserX) * sf;
                eraserY += (y - eraserY) * sf;
                eraserPressure += (pressure - eraserPressure) * 0.15f;

                eraserPaint.setStrokeWidth(calculateEraserStrokeWidth(eraserPressure, eraser));
                if (bitmapCanvas != null)
                    bitmapCanvas.drawLine(lastEraserX, lastEraserY, eraserX, eraserY, eraserPaint);

                lastEraserX = eraserX;
                lastEraserY = eraserY;

                long now = SystemClock.elapsedRealtime();
                if (frontRenderer != null && !pendingCommit && (now - lastCommitTime) > 32) {
                    pendingCommit = true;
                    lastCommitTime = now;
                    post(() -> {
                        if (frontRenderer != null) frontRenderer.commit();
                        pendingCommit = false;
                    });
                }
                overlayUpdateCursor(eraserX, eraserY, eraserPressure);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (frontRenderer != null) frontRenderer.commit();
                overlayHideCursor();
                break;
            }
        }
    }

    // ─── Stroke renderer ──────────────────────────────────────────

    public void renderSegment(float[] a, float[] b, Canvas target) {
        Pen pen = toolManager.getActivePen();
        float wa = calculatePressureStrokeWidth(a[2], pen) / 2f;
        float wb = calculatePressureStrokeWidth(b[2], pen) / 2f;

        target.drawCircle(a[0], a[1], wa, penPaint);
        target.drawCircle(b[0], b[1], wb, penPaint);

        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
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

    // ─── Width calculations ───────────────────────────────────────

    public static float calculatePressureStrokeWidth(float pressure, Pen pen) {
        float base        = pen.getWidth();
        float sensitivity = pen.getPressureSensitivity() / 100f;
        float clamped     = Math.max(0f, Math.min(1f, pressure < 0.01f ? 0.5f : pressure));
        float minW        = Math.max(base * (1f - sensitivity), base * 0.05f);
        return minW + (base - minW) * clamped;
    }

    public static float calculateEraserStrokeWidth(float pressure, Eraser eraser) {
        float base        = eraser.getWidth();
        float sensitivity = eraser.getPressureSensitivity() / 100f;
        float clamped     = Math.max(0f, Math.min(1f, pressure < 0.01f ? 0.5f : pressure));
        float minW        = Math.max(base * (1f - sensitivity), base * 0.05f);
        return minW + (base - minW) * clamped;
    }

    // ─── Undo ─────────────────────────────────────────────────────

    private void pushUndo() {
        if (canvasBitmap == null) return;
        if (undoStack.size() >= MAX_UNDO) undoStack.pollFirst().recycle();
        undoStack.push(canvasBitmap.copy(Bitmap.Config.ARGB_8888, false));
    }

    public void undo() {
        if (undoStack.isEmpty() || bitmapCanvas == null) return;
        Bitmap prev = undoStack.pop();
        bitmapCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        bitmapCanvas.drawBitmap(prev, 0, 0, null);
        prev.recycle();
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
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP)   {
            undo();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN)  return true;
        return super.onKeyUp(keyCode, event);
    }
}