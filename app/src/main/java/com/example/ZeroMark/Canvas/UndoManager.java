package com.example.ZeroMark.Canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoManager {

    private final Deque<Bitmap> stack = new ArrayDeque<>();

    // PERF FIX: reduced from 25 to 10.
    // At 2560×1600 (Redmi Pad 2 Pro native resolution) each ARGB_8888 bitmap
    // is ~16 MB. 25 snapshots = up to 400 MB held in memory continuously,
    // which causes heavy GC pauses and potential OOM after extended sessions.
    // 10 levels = ~160 MB — enough undo depth for typical use without
    // degrading sustained performance over a long drawing session.
    private static final int MAX = 10;

    public void push(Bitmap current) {
        if (current == null) return;
        if (stack.size() >= MAX) stack.pollFirst().recycle();
        stack.push(current.copy(Bitmap.Config.ARGB_8888, false));
    }

    public void pop(Canvas bitmapCanvas) {
        if (stack.isEmpty() || bitmapCanvas == null) return;
        Bitmap prev = stack.pop();
        bitmapCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        bitmapCanvas.drawBitmap(prev, 0, 0, null);
        prev.recycle();
    }

    public boolean canUndo() { return !stack.isEmpty(); }
}
