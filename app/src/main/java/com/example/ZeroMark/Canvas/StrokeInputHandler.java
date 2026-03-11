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
    private float lastRawP = 0f;

    // Stroke generation: embedded as data[9] in every emitted float[10] segment.
    // FastDrawingView increments this on each ACTION_DOWN and checks it in
    // onDrawFrontBufferedLayer. Callbacks queued for stroke N are silently dropped
    // once stroke N+1 has started (generation mismatch), which eliminates the
    // race where old renderer callbacks fire after liveBitmap has been cleared
    // for the new stroke and cause a transparent-frame flicker.
    private int currentGen = 0;

    public interface Listener {
        // float[10]: [0..5] smoothed segment, [6..8] raw tip, [9] stroke generation
        void onSegmentReady(float[] data);
        void onStrokeComplete(List<float[]> points);
    }

    private Listener listener;

    public void setListener(Listener l) { this.listener = l; }

    public void onDown(MotionEvent event, int gen) {
        currentGen = gen;
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

        // Flush smoothing lag: run EMA toward the final raw position, emitting
        // each catch-up segment to the front buffer AND to strokePoints.
        if (lastPoint != null) {
            float smoothing = ToolManager.getInstance().getActiveBrush().smoothing;
            float sf = smoothingFactor(smoothing);
            for (int i = 0; i < 500; i++) {
                float dx = lastRawX - lastPoint[0];
                float dy = lastRawY - lastPoint[1];
                if (dx * dx + dy * dy < 0.25f) break;
                float sx = lastPoint[0] + dx * sf;
                float sy = lastPoint[1] + dy * sf;
                float sp = lastPoint[2] + (lastRawP - lastPoint[2]) * Math.min(sf + 0.2f, 1f);
                float[] newPt = {sx, sy, sp};
                float[] data  = {lastPoint[0], lastPoint[1], lastPoint[2],
                        sx, sy, sp,
                        lastRawX, lastRawY, lastRawP,
                        (float) currentGen};
                strokePoints.add(newPt);
                lastPoint = newPt;
                if (listener != null) listener.onSegmentReady(data);
            }
        }

        if (listener != null) listener.onStrokeComplete(new ArrayList<>(strokePoints));
        strokePoints.clear();
        currentPointerId = -1;
        lastPoint = null;
    }

    private void consumePoint(float x, float y, float pressure) {
        float p = Math.max(0.02f, pressure);
        float smoothing = ToolManager.getInstance().getActiveBrush().smoothing;

        if (lastPoint == null) {
            lastRawX = x;  lastRawY = y;  lastRawP = p;
            lastPoint = new float[]{x, y, p};
            strokePoints.add(lastPoint);
            if (listener != null)
                listener.onSegmentReady(new float[]{x, y, p, x, y, p, x, y, p, (float) currentGen});
            return;
        }

        lastRawX = x;  lastRawY = y;  lastRawP = p;

        float sf = smoothingFactor(smoothing);
        float sx = lastPoint[0] + (x - lastPoint[0]) * sf;
        float sy = lastPoint[1] + (y - lastPoint[1]) * sf;
        float sp = lastPoint[2] + (p - lastPoint[2]) * Math.min(sf + 0.2f, 1f);

        float[] newPt = {sx, sy, sp};
        float[] data  = {lastPoint[0], lastPoint[1], lastPoint[2],
                sx, sy, sp,
                x, y, p,
                (float) currentGen};

        strokePoints.add(newPt);
        lastPoint = newPt;
        if (listener != null) listener.onSegmentReady(data);
    }

    private float smoothingFactor(float smoothing) {
        return 0.95f - (smoothing / 100f * 0.917f);
    }

    public List<float[]> getStrokePoints() { return strokePoints; }
    public int getCurrentPointerId()       { return currentPointerId; }
}
