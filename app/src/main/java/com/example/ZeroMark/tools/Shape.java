package com.example.ZeroMark.tools;

public class Shape {
    public enum ShapeType { LINE, RECTANGLE, ELLIPSE}

    private int color;
    private ShapeType shapeType;

    public Shape(int color, ShapeType shapeType) {
        this.color = color;
        this.shapeType = shapeType;
    }

    public int getColor() {
        return color;
    }

    public ShapeType getShapeType() {
        return shapeType;
    }
}
