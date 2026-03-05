package com.example.ZeroMark;

import android.graphics.Color;

import com.example.ZeroMark.tools.Eraser;
import com.example.ZeroMark.tools.Highlighter;
import com.example.ZeroMark.tools.Pen;
import com.example.ZeroMark.tools.Shape;

import java.util.ArrayList;
import java.util.List;

public class ToolManager {
    private static ToolManager instance;

    public enum ToolType { PEN, ERASER, HIGHLIGHTER, SHAPE, NONE }

    private ToolType currentToolType = ToolType.NONE;
    private int activeIndex = 0; // active slot index within the current tool list

    private final List<Pen>         penList         = new ArrayList<>();
    private final List<Eraser>      eraserList      = new ArrayList<>();
    private final List<Highlighter> highlighterList = new ArrayList<>();
    private final List<Shape>       shapeList       = new ArrayList<>();

    private ToolManager() {
        // Default slots — one of each to start
        penList.add(new Pen(14f, 50f, 35f, Color.BLACK));
        eraserList.add(new Eraser(25f, 0f, 50f));
        highlighterList.add(new Highlighter(20f, 30f, Color.YELLOW));
        shapeList.add(new Shape(Color.BLACK, Shape.ShapeType.RECTANGLE));
    }

    public static ToolManager getInstance() {
        if (instance == null) instance = new ToolManager();
        return instance;
    }

    // ─── Active tool access ───────────────────────────────────────

    public ToolType getCurrentToolType() { return currentToolType; }

    public void setCurrentTool(ToolType type) {
        this.currentToolType = type;
        this.activeIndex = 0;
    }

    public void setCurrentTool(ToolType type, int index) {
        this.currentToolType = type;
        this.activeIndex = index;
    }

    public Pen getActivePen() {
        return penList.get(activeIndex);
    }

    public Eraser getActiveEraser() {
        return eraserList.get(activeIndex);
    }

    public Highlighter getActiveHighlighter() {
        return highlighterList.get(activeIndex);
    }

    public Shape getActiveShape() {
        return shapeList.get(activeIndex);
    }

    // ─── List access (for preset UI) ─────────────────────────────

    public List<Pen>         getPenList()         { return penList; }
    public List<Eraser>      getEraserList()      { return eraserList; }
    public List<Highlighter> getHighlighterList() { return highlighterList; }
    public List<Shape>       getShapeList()       { return shapeList; }
}