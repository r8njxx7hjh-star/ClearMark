package com.example.drawingapp.tools;

import android.graphics.Color;

public class Highlighter {
    float width, smoothing;
    int color;

    public Highlighter(float width, float smoothing, int color) {
        this.width = width;
        this.smoothing = smoothing;
        this.color = color;
    }

    public float getWidth() {
        return width;
    }

    public void setWidth(float width) {
        this.width = width;
    }

    public float getSmoothing() {
        return smoothing;
    }

    public void setSmoothing(float smoothing) {
        this.smoothing = smoothing;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }
}
