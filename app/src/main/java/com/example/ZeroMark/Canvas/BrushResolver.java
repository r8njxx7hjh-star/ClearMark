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

    // Cubic bezier solver — no Android deps, copy to web JS verbatim
    public static float evaluateCurve(float[] curve, float input) {
        float cp1x = curve[0], cp1y = curve[1];
        float cp2x = curve[2], cp2y = curve[3];
        float t = input;
        for (int i = 0; i < 16; i++) {
            float x  =  3*cp1x*t*(1-t)*(1-t) + 3*cp2x*t*t*(1-t) + t*t*t;
            float dx =  3*cp1x*(1-t)*(1-3*t) + 3*cp2x*t*(3*t-2)  + 3*t*t;
            if (Math.abs(dx) < 1e-6f) break;
            t -= (x - input) / dx;
            t  = Math.max(0f, Math.min(1f, t));
        }
        return 3*cp1y*t*(1-t)*(1-t) + 3*cp2y*t*t*(1-t) + t*t*t;
    }
}