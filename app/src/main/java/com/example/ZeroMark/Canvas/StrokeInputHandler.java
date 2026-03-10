package com.example.ZeroMark.Canvas;

import android.view.MotionEvent;

import com.example.ZeroMark.Brushes.ToolManager;

import java.util.ArrayList;
import java.util.List;

public class StrokeInputHandler {

    private final List<float[]> strokePoints = new ArrayList<>();
    private float[] lastPoint = null;
    private int currentPointerId = -1;
    private float lastRawX = 0f;
    private float lastRawY = 0f;


    // Callbacks so FastDrawingView can react without this class knowing about the renderer
    public interface Listener {
        void onSegmentReady(float[] segment);   // push to frontRenderer
        void onStrokeComplete(List<float[]> points);  // commit to bitmap
    }

    private Listener listener;

    public void setListener(Listener l) { this.listener = l; }

    public void onDown(MotionEvent event) {
        currentPointerId = event.getPointerId(event.getActionIndex());
        strokePoints.clear();
        lastPoint = null;
        consumePoint(event.getX(), event.getY(), event.getPressure());
    }

    public void onMove(MotionEvent event) {
        int idx = event.findPointerIndex(currentPointerId);
        if (currentPointerId == -1 || idx < 0) return;

        int hist = event.getHistorySize();
        for (int h = 0; h < hist; h++) {
            consumePoint(
                    event.getHistoricalX(idx, h),
                    event.getHistoricalY(idx, h),
                    event.getHistoricalPressure(idx, h));
        }
        consumePoint(event.getX(idx), event.getY(idx), event.getPressure(idx));
    }

    public void onUp() {
        if (currentPointerId == -1) return;
        if (listener != null) listener.onStrokeComplete(new ArrayList<>(strokePoints));
        strokePoints.clear();
        currentPointerId = -1;
        lastPoint = null;
    }

    private void consumePoint(float x, float y, float pressure) {
        float p = Math.max(0.02f, pressure);
        float smoothing = ToolManager.getInstance().getActiveBrush().smoothing;

        if (lastPoint == null) {
            lastRawX = x;
            lastRawY = y;
            lastPoint = new float[]{x, y, p};
            strokePoints.add(lastPoint);   // PERF FIX: no .clone() — lastPoint is never mutated in place
            if (listener != null) listener.onSegmentReady(new float[]{x, y, p, x, y, p});
            return;
        }

        lastRawX = x;
        lastRawY = y;

        float sf = smoothingFactor(smoothing);
        float sx = lastPoint[0] + (lastRawX - lastPoint[0]) * sf;
        float sy = lastPoint[1] + (lastRawY - lastPoint[1]) * sf;
        float sp = lastPoint[2] + (p - lastPoint[2]) * Math.min(sf + 0.2f, 1f);

        // PERF FIX: removed newPt.clone() — newPt is freshly allocated each call
        // and never mutated after being stored in strokePoints, so the clone was
        // a pointless allocation that doubled GC pressure on long strokes.
        float[] newPt   = {sx, sy, sp};
        float[] segment = {lastPoint[0], lastPoint[1], lastPoint[2], sx, sy, sp};

        strokePoints.add(newPt);
        lastPoint = newPt;
        if (listener != null) listener.onSegmentReady(segment);
    }

    private float smoothingFactor(float smoothing) {
        return 0.95f - (smoothing / 100f * 0.90f);
    }

    public List<float[]> getStrokePoints() { return strokePoints; }
    public int getCurrentPointerId()       { return currentPointerId; }
}
