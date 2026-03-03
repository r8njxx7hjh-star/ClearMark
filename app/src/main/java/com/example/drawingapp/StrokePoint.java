package com.example.drawingapp;

import android.graphics.PointF;

public class StrokePoint {
    float x, y;
    float width;
    int color;

    public StrokePoint(PointF point, float width, int color) {
        this.x = point.x;
        this.y = point.y;
        this.width = width;
        this.color = color;
    }
}
