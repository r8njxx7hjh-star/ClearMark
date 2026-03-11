package com.example.ZeroMark.Canvas;

import com.example.ZeroMark.Brushes.BrushDescriptor;

public class BrushResolver {

    public static float resolveSize(BrushDescriptor b, float pressure) {
        float t      = b.pressureControlsSize
                ? evaluateCurve(b.sizePressureCurve, pressure)
                : 1f;
        float minPx  = b.size * (b.sizeMin / 100f);
        float maxPx  = b.size * (b.sizeMax / 100f);
        return minPx + (maxPx - minPx) * t;
    }

    public static float resolveOpacity(BrushDescriptor b, float pressure) {
        float t        = b.pressureControlsOpacity
                ? evaluateCurve(b.opacityPressureCurve, pressure)
                : 1f;
        float minAlpha = b.opacityMin / 100f;
        float maxAlpha = b.opacityMax / 100f;
        float base     = b.opacity    / 100f;
        return base * (minAlpha + (maxAlpha - minAlpha) * t);
    }

    /**
     * Maps brush.spacing (1–100) to a diameter multiplier (0.10–2.50).
     *   1   → 0.10  (heavily overlapping dabs — solid stroke)
     *   50  → ~1.30 (slightly separated)
     *   100 → 2.50  (2.5 brush-widths between dabs)
     *
     * Single source of truth — used by both StrokeRenderer and CanvasOverlay
     * so the tether preview is always pixel-identical to the committed stroke.
     */
    public static float resolveSpacingMultiplier(BrushDescriptor b) {
        return 0.10f + (b.spacing - 1) / 99.0f * 2.40f;
    }

    // Cubic bezier solver — no Android deps, copy to web JS verbatim
    public static float evaluateCurve(float[] curve, float input) {
        float cp1x = curve[0], cp1y = curve[1];
        float cp2x = curve[2], cp2y = curve[3];
        float t = input; // good initial guess
        for (int i = 0; i < 16; i++) {
            float x  =  3*cp1x*t*(1-t)*(1-t) + 3*cp2x*t*t*(1-t) + t*t*t;
            float dx =  3*cp1x*(1-t)*(1-3*t) + 3*cp2x*t*(2-3*t) + 3*t*t; // ← was (3*t-2), must be (2-3*t)
            if (Math.abs(dx) < 1e-6f) break;
            t -= (x - input) / dx;
            t  = Math.max(0f, Math.min(1f, t));
        }
        return 3*cp1y*t*(1-t)*(1-t) + 3*cp2y*t*t*(1-t) + t*t*t;
    }
}