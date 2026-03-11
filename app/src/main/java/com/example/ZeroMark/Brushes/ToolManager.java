package com.example.ZeroMark.Brushes;

import android.graphics.Color;

import com.example.ZeroMark.tools.Shape;

import java.util.ArrayList;
import java.util.List;

public class ToolManager {

    // ─── Thread-safe singleton (initialization-on-demand holder) ──
    // The JVM guarantees that Holder.INSTANCE is initialized exactly once,
    // lazily, without any explicit synchronization needed at call sites.
    // This replaces the previous unsynchronized "if (instance == null)" check,
    // which could produce two instances if called from multiple threads.
    private static final class Holder {
        static final ToolManager INSTANCE = new ToolManager();
    }

    public static ToolManager getInstance() {
        return Holder.INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────

    public enum ToolType { PEN, ERASER, HIGHLIGHTER, SHAPE, NONE }

    private ToolType currentToolType = ToolType.NONE;
    private int activeIndex = 0;

    private final List<BrushDescriptor> brushList  = new ArrayList<>();
    private final List<BrushDescriptor> eraserList = new ArrayList<>();
    private final List<Shape>           shapeList  = new ArrayList<>();

    private ToolManager() {
        brushList.add(BrushDescriptor.inkPen());
        brushList.add(BrushDescriptor.softPencil());

        // Eraser is just a BrushDescriptor with CLEAR blend mode.
        // BUG FIX: opacity must be set to 100. The Builder default for int
        // fields is 0, so without .opacity(100) BrushResolver.resolveOpacity
        // returns 0.0f → the eraser layer has alpha = 0 → completely invisible
        // in both the multi-buffer (drawFullCanvas saveLayer) and commitStroke
        // paths. Setting opacity to 100 makes the eraser fully opaque.
        eraserList.add(new BrushDescriptor.Builder("Eraser")
                .size(25)
                .sizeRange(50, 150)
                .pressureControlsSize(true)
                .opacity(100)                               // ← FIX: was missing, defaulted to 0
                .opacityRange(100, 100)                     // eraser is always full-strength
                .smoothing(50)
                .spacing(4)
                .blendMode(BrushDescriptor.BlendMode.CLEAR)
                .build());

        shapeList.add(new Shape(Color.BLACK, Shape.ShapeType.RECTANGLE));
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
