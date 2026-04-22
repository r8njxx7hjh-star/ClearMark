package com.example.zeromark.brushes;

import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ToolManager {

    // ─── Thread-safe singleton (initialization-on-demand holder) ──
    // The JVM guarantees that Holder.INSTANCE is initialized exactly once,
    // lazily, without any explicit synchronization needed at call sites.
    private static final class Holder {
        static final ToolManager INSTANCE = new ToolManager();
    }

    public static ToolManager getInstance() {
        return Holder.INSTANCE;
    }

    // ─────────────────────────────────────────────────────────────

    public enum ToolType { PEN, ERASER, HIGHLIGHTER, SHAPE, NONE }

    // AtomicReference/AtomicInteger so renderer-thread reads of these fields
    // are always coherent with main-thread writes — no data race on ARM/JIT.
    private final AtomicReference<ToolType> currentToolType =
            new AtomicReference<>(ToolType.PEN);
    private final AtomicInteger activeIndex = new AtomicInteger(0);

    private final List<BrushDescriptor> brushList  = new ArrayList<>();
    private final List<BrushDescriptor> eraserList = new ArrayList<>();

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

    }

    // ─── Active tool access ───────────────────────────────────────

    public ToolType getCurrentToolType()              { return currentToolType.get(); }

    public void setCurrentTool(ToolType type) {
        currentToolType.set(type);
        activeIndex.set(0);
    }

    public void setCurrentTool(ToolType type, int i) {
        currentToolType.set(type);
        activeIndex.set(i);
    }

    public BrushDescriptor getActiveBrush() {
        ToolType type = currentToolType.get();
        int idx = activeIndex.get();
        if (type == ToolType.NONE) return null;
        if (type == ToolType.ERASER) {
            if (idx < 0 || idx >= eraserList.size()) return null;
            return eraserList.get(idx);
        } else {
            if (idx < 0 || idx >= brushList.size()) return null;
            return brushList.get(idx);
        }
    }


    // ─── List access (for UI) ─────────────────────────────────────

    public List<BrushDescriptor> getBrushList()  { return brushList; }
    public List<BrushDescriptor> getEraserList() { return eraserList; }
}
