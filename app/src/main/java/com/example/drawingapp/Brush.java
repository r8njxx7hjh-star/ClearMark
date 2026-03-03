package com.example.drawingapp;

import android.graphics.Color;
import android.graphics.Paint;

public class Brush {
    private float brushWidth;
    private int brushColor;
    private float smoothingPercent;
    private float pressureSensitivity;

    public Brush(float brushWidth, int brushColor, float smoothingPercent, float pressureSensitivity) {
        this.brushWidth = brushWidth;
        this.brushColor = brushColor;
        this.smoothingPercent = smoothingPercent;
        this.pressureSensitivity = pressureSensitivity;
    }

    public float getBrushWidth() {
        return brushWidth;
    }

    public void setBrushWidth(float brushWidth) {
        this.brushWidth = brushWidth;
    }

    public int getBrushColor() {
        return brushColor;
    }

    public void setBrushColor(int brushColor) {
        this.brushColor = brushColor;
    }

    public float getSmoothingPercent() {
        return smoothingPercent;
    }

    public void setSmoothingPercent(float smoothingPercent) {
        this.smoothingPercent = smoothingPercent;
    }

    public float getPressureSensitivity() {
        return pressureSensitivity;
    }

    public void setPressureSensitivity(float pressureSensitivity) {
        this.pressureSensitivity = pressureSensitivity;
    }
}
