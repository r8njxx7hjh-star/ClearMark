package com.example.ZeroMark.Brushes;

import android.graphics.Color;

import com.example.ZeroMark.tools.Shape;
import com.example.ZeroMark.Brushes.*;

import java.util.ArrayList;
import java.util.List;

public class ToolManager {
    private static ToolManager instance;

    public enum ToolType { PEN, ERASER, HIGHLIGHTER, SHAPE, NONE }

    private ToolType currentToolType = ToolType.NONE;
    private int activeIndex = 0;

    private final List<BrushDescriptor> brushList  = new ArrayList<>();
    private final List<BrushDescriptor> eraserList = new ArrayList<>();
    private final List<Shape>           shapeList  = new ArrayList<>();

    private ToolManager() {
        brushList.add(BrushDescriptor.inkPen());
        brushList.add(BrushDescriptor.softPencil());

        // Eraser is just a BrushDescriptor with CLEAR blend mode
        brushList.add(new BrushDescriptor.Builder("Eraser")
                .size(25)
                .sizeRange(50, 150)
                .pressureControlsSize(true)
                .smoothing(50)
                .blendMode(BrushDescriptor.BlendMode.CLEAR)  // ← this is what makes it an eraser
                .build());

        shapeList.add(new Shape(Color.BLACK, Shape.ShapeType.RECTANGLE));
    }

    public static ToolManager getInstance() {
        if (instance == null) instance = new ToolManager();
        return instance;
    }

    // ─── Active tool access ───────────────────────────────────────

    public ToolType getCurrentToolType()              { return currentToolType; }

    public void setCurrentTool(ToolType type)         { currentToolType = type; activeIndex = 0; }
    public void setCurrentTool(ToolType type, int i)  { currentToolType = type; activeIndex = i; }

    public BrushDescriptor getActiveBrush() {
        if (currentToolType == ToolType.NONE) return null;
        if (currentToolType == ToolType.ERASER) {
            return eraserList.get(activeIndex);
        } else {
            return brushList.get(activeIndex);
        }
    }

    public Shape getActiveShape() { return shapeList.get(activeIndex); }

    // ─── List access (for UI) ─────────────────────────────────────

    public List<BrushDescriptor> getBrushList()  { return brushList; }
    public List<BrushDescriptor> getEraserList() { return eraserList; }
    public List<Shape> getShapeList()  { return shapeList; }
}