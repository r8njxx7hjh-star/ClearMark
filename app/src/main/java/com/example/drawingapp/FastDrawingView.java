// FastDrawingView.java
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class FastDrawingView extends SurfaceView implements SurfaceHolder.Callback {

    // ─── Tool modes ───────────────────────────────────────────────
    public enum ToolMode { BRUSH, ERASER, EMPTY }
    private ToolMode toolMode = ToolMode.BRUSH;

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
    private static final int MAX_UNDO = 20;

    // ─── Active stroke tracking ───────────────────────────────────
    private int currentPointerId = -1;
    private float[] lastPoint = null;   // [x, y, pressure]

    // ─── Eraser state & throttling ────────────────────────────────
    private volatile boolean eraserTouching;
    private volatile float eraserX, eraserY, eraserPressure;
    private float lastEraserX, lastEraserY;
    private long lastCommitTime = 0; // Used to prevent queue jamming

    // ─── Brush config ─────────────────────────────────────────────
    private Brush activeBrush;
    private Brush eraserBrush;
    private int inkColor = Color.BLACK;

    // ─── Paints (re-used; never allocate in draw callbacks) ───────
    private final Paint ribbonPaint;
    private final Paint capPaint;
    private final Paint eraserPaint;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private volatile boolean pendingCommit = false;

    // =================================================================

    public FastDrawingView(Context context) {
        super(context);

        activeBrush = new Brush(14f, Color.BLACK, 30f, 100f);
        eraserBrush = new Brush(14f, Color.BLACK, 0f, 25f);

        // Ribbon / cap paints
        ribbonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ribbonPaint.setStyle(Paint.Style.FILL);
        ribbonPaint.setColor(inkColor);

        capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        capPaint.setStyle(Paint.Style.FILL);
        capPaint.setColor(inkColor);

        // Eraser paints - TRUE erase cutting through the bitmap
        eraserPaint = new Paint();
        eraserPaint.setStyle(Paint.Style.STROKE);
        eraserPaint.setStrokeCap(Paint.Cap.ROUND);
        eraserPaint.setStrokeJoin(Paint.Join.ROUND);
        eraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        setFocusable(true);
        setFocusableInTouchMode(true);
        getHolder().addCallback(this);
    }

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

    public void setcanvasOverlay(CanvasOverlay overlay) {
        this.canvasOverlay = overlay;
    }

    // ─── CanvasFrontBufferedRenderer callbacks ────────────────────

    private final CanvasFrontBufferedRenderer.Callback<float[]> rendererCallbacks =
            new CanvasFrontBufferedRenderer.Callback<float[]>() {

                @Override
                public void onDrawMultiBufferedLayer(
                        @NonNull Canvas canvas,
                        int i, int i1,
                        @NonNull Collection<? extends float[]> collection) {

                    // Clear and draw the base layer
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                    canvas.drawColor(Color.WHITE);
                    if (canvasBitmap != null) {
                        canvas.drawBitmap(canvasBitmap, 0, 0, bitmapPaint);
                    }

                    // Re-draw any in-progress brush stroke that hasn't been
                    // committed to canvasBitmap yet. Without this, a commit()
                    // call (e.g. from the eraser) clears the front buffer and
                    // the multi-buffer shows a stale canvas — causing the
                    // "stuttery / disappearing stroke" bug when switching tools.
                    if (toolMode == ToolMode.BRUSH && strokePoints.size() > 1) {
                        ribbonPaint.setColor(inkColor);
                        capPaint.setColor(inkColor);
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

                    // BRUSH MODE: Accumulate ink on the front buffer
                    ribbonPaint.setColor(inkColor);
                    capPaint.setColor(inkColor);

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
        if (toolMode == ToolMode.EMPTY) return true;
        if (toolMode == ToolMode.BRUSH) handleBrushTouch(event);
        else handleEraserTouch(event);
        return true;
    }

    // ─── Brush touch ──────────────────────────────────────────────

    private void handleBrushTouch(MotionEvent event) {
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
                canvasOverlay.hideLinePreview();
                frontRenderer.commit(); // Only committed ONCE at the end for Brush
                strokePoints.clear();
                currentPointerId = -1;
                lastPoint = null;
                break;
            }
        }
    }

    private float CalculateSmoothingFactor(float smoothing) {
        return 1f - (smoothing / 100f * 0.95f);
    }

    private void consumePoint(float x, float y, float pressure) {
        float p = pressure < 0.01f ? 0.5f : Math.min(pressure, 1f);

        if (lastPoint == null) {
            lastPoint = new float[]{x, y, p};
            strokePoints.add(lastPoint.clone());
            frontRenderer.renderFrontBufferedLayer(new float[]{x, y, p, x, y, p});
            return;
        }

        float sf = CalculateSmoothingFactor(activeBrush.getSmoothingPercent());
        float sx = lastPoint[0] + (x - lastPoint[0]) * sf;
        float sy = lastPoint[1] + (y - lastPoint[1]) * sf;
        float sp = lastPoint[2] + (p - lastPoint[2]) * Math.min(sf + 0.2f, 1f);

        float[] newPt = {sx, sy, sp};
        float[] segment = {lastPoint[0], lastPoint[1], lastPoint[2], newPt[0], newPt[1], newPt[2]};

        if (canvasOverlay != null) canvasOverlay.updateLinePreview(x, y, sx, sy, halfWidth(p) * 2f, ribbonPaint.getColor());

        strokePoints.add(newPt.clone());
        lastPoint = newPt;

        frontRenderer.renderFrontBufferedLayer(segment);
    }

    private void commitStrokeToBitmap() {
        if (bitmapCanvas == null) return;
        ribbonPaint.setColor(inkColor);
        capPaint.setColor(inkColor);
        float[] prev = null;
        for (float[] pt : strokePoints) {
            if (prev != null) renderSegment(prev, pt, bitmapCanvas);
            else renderSegment(pt, pt, bitmapCanvas);
            prev = pt;
        }
    }

    // ─── Eraser touch (Throttled TRUE Erase) ──────────────────────
    private void handleEraserTouch(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        float pressure = event.getPressure();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                pushUndo();
                eraserTouching = true;
                eraserX = x; eraserY = y;
                lastEraserX = x; lastEraserY = y;
                eraserPressure = pressure;
                lastCommitTime = SystemClock.elapsedRealtime();

                eraserPaint.setStrokeWidth(8f + eraserPressure * 60f);
                if (bitmapCanvas != null) {
                    bitmapCanvas.drawPoint(x, y, eraserPaint);
                }
                if (frontRenderer != null) {
                    frontRenderer.commit();
                }
                if (canvasOverlay != null) canvasOverlay.updateCursor(eraserX, eraserY, eraserPressure);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                float sf = CalculateSmoothingFactor(eraserBrush.getSmoothingPercent());
                eraserX += (x - eraserX) * sf;
                eraserY += (y - eraserY) * sf;
                eraserPressure += (pressure - eraserPressure) * 0.15f;

                eraserPaint.setStrokeWidth(8f + eraserPressure * 60f);
                if (bitmapCanvas != null) {
                    bitmapCanvas.drawLine(lastEraserX, lastEraserY, eraserX, eraserY, eraserPaint);
                }

                lastEraserX = eraserX;
                lastEraserY = eraserY;

                long now = SystemClock.elapsedRealtime();
                if (frontRenderer != null && !pendingCommit && (now - lastCommitTime) > 32) { // ~30fps cap
                    pendingCommit = true;
                    lastCommitTime = now;
                    post(() -> {
                        if (frontRenderer != null) frontRenderer.commit();
                        pendingCommit = false;
                    });
                }
                if (canvasOverlay != null) canvasOverlay.updateCursor(eraserX, eraserY, eraserPressure);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                eraserTouching = false;
                if (frontRenderer != null) {
                    frontRenderer.commit();
                }
                if (canvasOverlay != null) canvasOverlay.hideCursor();
                break;
            }
        }
    }

    // ─── Custom stroke renderer ───────────────────────────────────

    public void renderSegment(float[] a, float[] b, Canvas target) {
        float ax = a[0], ay = a[1], ap = a[2];
        float bx = b[0], by = b[1], bp = b[2];

        float wa = halfWidth(ap);
        float wb = halfWidth(bp);

        target.drawCircle(ax, ay, wa, capPaint);
        target.drawCircle(bx, by, wb, capPaint);

        float dx = bx - ax, dy = by - ay;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5f) return;

        float nx = -dy / len, ny = dx / len;

        float l0x = ax + nx * wa, l0y = ay + ny * wa;
        float r0x = ax - nx * wa, r0y = ay - ny * wa;
        float l1x = bx + nx * wb, l1y = by + ny * wb;
        float r1x = bx - nx * wb, r1y = by - ny * wb;

        Path seg = new Path();
        seg.moveTo(l0x, l0y);
        seg.lineTo(l1x, l1y);
        seg.lineTo(r1x, r1y);
        seg.lineTo(r0x, r0y);
        seg.close();
        target.drawPath(seg, ribbonPaint);
    }

    private float halfWidth(float pressure) {
        float base = activeBrush.getBrushWidth();
        float sensitivity = activeBrush.getPressureSensitivity() / 100f;
        float clamped = Math.max(0f, Math.min(1f, pressure < 0.01f ? 0.5f : pressure));
        float minW = base * (1f - sensitivity * 0.75f);
        return (minW + (base - minW) * clamped) * 0.5f;
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

    // ─── Public API ───────────────────────────────────────────────

    public void setToolMode(ToolMode mode) {
        this.toolMode = mode;
        pendingCommit = false; // discard any queued eraser commits
        removeCallbacks(null); // cancel pending post() runnables
    }
    public ToolMode getToolMode() { return toolMode; }
    public void setInkColor(int c) { inkColor = c; }
    public void setInkSize(float size) {
        activeBrush = new Brush(size, inkColor,
                activeBrush.getSmoothingPercent(), activeBrush.getPressureSensitivity());
    }
    public void setSmoothing(float s) {
        activeBrush = new Brush(activeBrush.getBrushWidth(), inkColor,
                s, activeBrush.getPressureSensitivity());
    }
    public void setPressureSensitivity(float s) {
        activeBrush = new Brush(activeBrush.getBrushWidth(), inkColor,
                activeBrush.getSmoothingPercent(), s);
    }
    public Brush getActiveBrush() { return activeBrush; }

    // ─── Hardware keys ────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            event.startTracking();
            toolMode = (toolMode == ToolMode.ERASER) ? ToolMode.BRUSH : ToolMode.ERASER;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP) { undo(); return true; }
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) { return true; }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP ||
                keyCode == KeyEvent.KEYCODE_PAGE_DOWN) return true;
        return super.onKeyLongPress(keyCode, event);
    }
}