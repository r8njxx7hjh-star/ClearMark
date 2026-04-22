package com.example.zeromark.canvas;

import android.graphics.Matrix;
import android.view.MotionEvent;

import com.example.zeromark.brushes.ToolManager;

import java.util.ArrayList;
import java.util.List;

public class StrokeInputHandler {

    private final List<float[]> strokePoints = new ArrayList<>();
    private float[] lastPoint = null;
    private int currentPointerId = -1;
    private float lastRawX = 0f;
    private float lastRawY = 0f;
    private float lastRawP = 0f;

    private Matrix inverseMatrix = new Matrix();

    public void setInverseMatrix(Matrix matrix) {
        this.inverseMatrix = matrix;
    }

    private float[] mapPoint(float x, float y) {
        float[] pts = new float[]{x, y};
        inverseMatrix.mapPoints(pts);
        return pts;
    }

    // Stroke generation: embedded as data[9] in every emitted float[10] segment.
    private int currentGen = 0;

    public interface Listener {
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
        float[] pts = mapPoint(event.getX(), event.getY());
        consumePoint(pts[0], pts[1], event.getPressure());
    }

    public void onMove(MotionEvent event) {
        int idx = event.findPointerIndex(currentPointerId);
        if (currentPointerId == -1 || idx < 0) return;

        int hist = event.getHistorySize();
        for (int h = 0; h < hist; h++) {
            float[] hpts = mapPoint(event.getHistoricalX(idx, h), event.getHistoricalY(idx, h));
            consumePoint(
                    hpts[0],
                    hpts[1],
                    event.getHistoricalPressure(idx, h));
        }
        float[] pts = mapPoint(event.getX(idx), event.getY(idx));
        consumePoint(pts[0], pts[1], event.getPressure(idx));
    }

    public void onUp() {
        if (currentPointerId == -1) return;

        if (lastPoint != null) {
            float smoothing = ToolManager.getInstance().getActiveBrush().smoothing;
            float sf = smoothingFactor(smoothing);
            for (int i = 0; i < 500; i++) {
                float dx = lastRawX - lastPoint[0];
                float dy = lastRawY - lastPoint[1];
                // Threshold: only catch up if distance is > 0.5px
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

        float dx = x - lastRawX;
        float dy = y - lastRawY;
        // Threshold: ignore moves smaller than 0.5px raw to reduce noise and segment count
        if (dx * dx + dy * dy < 0.25f && Math.abs(p - lastRawP) < 0.01f) return;

        lastRawX = x;  lastRawY = y;  lastRawP = p;

        float sf = smoothingFactor(smoothing);
        float sx = lastPoint[0] + (x - lastPoint[0]) * sf;
        float sy = lastPoint[1] + (y - lastPoint[1]) * sf;
        float sp = lastPoint[2] + (p - lastPoint[2]) * Math.min(sf + 0.2f, 1f);

        // Distance check for the smoothed point vs lastPoint
        float sdx = sx - lastPoint[0];
        float sdy = sy - lastPoint[1];
        if (sdx * sdx + sdy * sdy < 0.01f) return; // Ignore effectively zero-length smoothed segments

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
