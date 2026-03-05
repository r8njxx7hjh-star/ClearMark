package com.example.ZeroMark.tools;

public class Pen {
    float width, smoothing, pressureSensitivity;
    int color;

    public Pen(float width, float smoothing, float pressureSensitivity, int color) {
        this.width = width;
        this.smoothing = smoothing;
        this.pressureSensitivity = pressureSensitivity;
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

    public float getPressureSensitivity() {
        return pressureSensitivity;
    }

    public void setPressureSensitivity(float pressureSensitivity) {
        this.pressureSensitivity = pressureSensitivity;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }
}
