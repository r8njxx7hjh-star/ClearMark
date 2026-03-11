package com.example.ZeroMark.Canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;

public class UndoManager {

    private static final int MAX = 10;

    // ─── Pool-based ring buffer ───────────────────────────────────
    // The original Deque implementation allocated a fresh ~16 MB bitmap on
    // every push() and recycled the oldest one once the stack was full. With
    // 5+ quick strokes per second that is 80+ MB/sec of large-object
    // alloc/free, causing continuous ART GC pauses mid-drawing.
    //
    // This implementation pre-allocates MAX bitmap slots lazily (first time
    // a slot is used) and reuses them forever. In steady state push() does
    // zero allocation — it just copies pixels into an existing bitmap.
    // pop() similarly never frees memory; the slot stays in the pool for the
    // next push(). Total held memory is the same (MAX × ~16 MB), but the
    // GC sees no churn after the pool is warm.
    private final Bitmap[] pool       = new Bitmap[MAX];
    private final Canvas[] poolCanvas = new Canvas[MAX]; // cached, one per slot
    private int head = 0;  // index of the oldest (bottom) entry
    private int size = 0;  // number of valid entries currently in the stack

    public void push(Bitmap current) {
        if (current == null) return;
        int w = current.getWidth();
        int h = current.getHeight();

        // Target slot: next empty position, or overwrite the oldest if full.
        int slot = (head + size) % MAX;
        if (size < MAX) {
            size++;
        } else {
            // Stack is full — oldest entry is evicted (overwritten).
            head = (head + 1) % MAX;
        }

        // Lazy allocation: first time a slot is used, or if the canvas size
        // changed (e.g. device rotation).
        if (pool[slot] == null
                || pool[slot].getWidth()  != w
                || pool[slot].getHeight() != h) {
            if (pool[slot] != null) pool[slot].recycle();
            pool[slot]       = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            poolCanvas[slot] = new Canvas(pool[slot]);
        }

        // Copy pixels — no allocation, just a CPU memcopy into the existing slot.
        poolCanvas[slot].drawBitmap(current, 0, 0, null);
    }

    public void pop(Canvas bitmapCanvas) {
        if (size == 0 || bitmapCanvas == null) return;
        // Top of stack is the most recently pushed slot.
        int slot = (head + size - 1) % MAX;
        size--;
        // pool[slot] stays allocated for future push() reuse — do NOT recycle.
        bitmapCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        bitmapCanvas.drawBitmap(pool[slot], 0, 0, null);
    }

    public boolean canUndo() { return size > 0; }
}
