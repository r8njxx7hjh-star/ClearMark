package com.example.drawingapp.tools;

public class Eraser {
    float width, smoothing, pressureSensitivity;

    public Eraser(float width, float smoothing, float pressureSensitivity) {
        this.width = width;
        this.smoothing = smoothing;
        this.pressureSensitivity = pressureSensitivity;
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

    public float getPressureSensitivity() {
        return pressureSensitivity;
    }

    public void setPressureSensitivity(float pressureSensitivity) {
        this.pressureSensitivity = pressureSensitivity;
    }
}
