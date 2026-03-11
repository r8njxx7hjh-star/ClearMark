package com.example.ZeroMark.Canvas;

import android.view.MotionEvent;

import com.example.ZeroMark.Brushes.ToolManager;

import java.util.ArrayList;
import java.util.List;

public class StrokeInputHandler {

    private final List<float[]> strokePoints = new ArrayList<>();
    private float[] lastPoint = null;  // last smoothed point
    private int currentPointerId = -1;
    private float lastRawX = 0f;
    private float lastRawY = 0f;
    private float lastRawP = 0f;


    // Callbacks so FastDrawingView can react without this class knowing about the renderer
    public interface Listener {
        // Smoothed segment [ax,ay,ap, bx,by,bp] + raw tip [rawX, rawY, rawP] packed as float[9].
        // The renderer draws the smoothed segment onto liveBitmap, then draws a straight
        // tether from the smoothed tip to the raw touch position on top — this makes the
        // stroke feel connected and instant while the committed stroke is fully smoothed.
        void onSegmentReady(float[] data); // float[9]: smoothSeg(6) + rawTip(3)
        void onStrokeComplete(List<float[]> points);
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

        // Flush the smoothing lag so the tether tail becomes part of the committed stroke.
        //
        // When the pen lifts, lastPoint (smoothed tip) is still lagging behind lastRaw*
        // (where the finger actually lifted). Without this flush that entire tail is
        // discarded and the stroke visibly snaps short on high-smoothing brushes.
        //
        // We run the same EMA loop toward the final raw position, emitting each catch-up
        // segment to the front buffer (so it animates in) AND to strokePoints (so
        // commitStroke bakes it permanently). Loop stops when remaining distance < 0.5 px.
        if (lastPoint != null) {
            float smoothing = ToolManager.getInstance().getActiveBrush().smoothing;
            float sf = smoothingFactor(smoothing);
            for (int i = 0; i < 500; i++) {
                float dx = lastRawX - lastPoint[0];
                float dy = lastRawY - lastPoint[1];
                if (dx * dx + dy * dy < 0.25f) break; // 0.5 px — imperceptible at any zoom
                float sx = lastPoint[0] + dx * sf;
                float sy = lastPoint[1] + dy * sf;
                float sp = lastPoint[2] + (lastRawP - lastPoint[2]) * Math.min(sf + 0.2f, 1f);
                float[] newPt = {sx, sy, sp};
                float[] data  = {lastPoint[0], lastPoint[1], lastPoint[2],
                        sx, sy, sp,
                        lastRawX, lastRawY, lastRawP};
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
            // First point: tether is zero-length, raw tip == smoothed tip
            if (listener != null)
                listener.onSegmentReady(new float[]{x, y, p, x, y, p, x, y, p});
            return;
        }

        // Always update raw tip — this is what drives the tether
        lastRawX = x;  lastRawY = y;  lastRawP = p;

        // Smoothed point via exponential moving average
        float sf = smoothingFactor(smoothing);
        float sx = lastPoint[0] + (x - lastPoint[0]) * sf;
        float sy = lastPoint[1] + (y - lastPoint[1]) * sf;
        float sp = lastPoint[2] + (p - lastPoint[2]) * Math.min(sf + 0.2f, 1f);

        float[] newPt = {sx, sy, sp};
        // float[9]: [0..5] smoothed segment from last to new, [6..8] current raw tip
        float[] data  = {lastPoint[0], lastPoint[1], lastPoint[2],
                sx, sy, sp,
                x, y, p};

        strokePoints.add(newPt);
        lastPoint = newPt;
        if (listener != null) listener.onSegmentReady(data);
    }

    private float smoothingFactor(float smoothing) {
        // Old range: sf = 0.95 (at 0) down to 0.05 (at 100).
        // Extended range: sf = 0.95 (at 0) down to 0.033 (at 100).
        // sf@100 = 0.033 is 50% weaker than the old 0.05, meaning the brush
        // trails the raw pointer noticeably more at max smoothing.
        return 0.95f - (smoothing / 100f * 0.917f);
    }

    public List<float[]> getStrokePoints() { return strokePoints; }
    public int getCurrentPointerId()       { return currentPointerId; }
}