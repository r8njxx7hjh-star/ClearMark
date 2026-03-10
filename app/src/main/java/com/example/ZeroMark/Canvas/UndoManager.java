package com.example.ZeroMark.Canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;

import java.util.ArrayDeque;
import java.util.Deque;

public class UndoManager {

    private final Deque<Bitmap> stack = new ArrayDeque<>();
    private static final int MAX = 120;

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