package com.example.zeromark.canvas;

import com.example.zeromark.brushes.BrushDescriptor;

public class BrushResolver {

    public static float resolveSize(BrushDescriptor b, float pressure) {
        float t;
        if (b.pressureControlsSize && b.sizeCurveTable != null) {
            int idx = (int) (pressure * 255f);
            idx = Math.max(0, Math.min(255, idx));
            t = b.sizeCurveTable[idx];
        } else {
            t = 1f;
        }
        float minPx  = b.size * (b.sizeMin / 100f);
        float maxPx  = b.size * (b.sizeMax / 100f);
        return minPx + (maxPx - minPx) * t;
    }

    public static float resolveSpacingMultiplier(BrushDescriptor b) {
        return 0.10f + (b.spacing - 1) / 99.0f * 2.40f;
    }

    public static float evaluateCurve(float[] curve, float input) {
        float cp1x = curve[0], cp1y = curve[1];
        float cp2x = curve[2], cp2y = curve[3];
        float t = input; // good initial guess
        for (int i = 0; i < 16; i++) {
            float x  =  3*cp1x*t*(1-t)*(1-t) + 3*cp2x*t*t*(1-t) + t*t*t;
            float dx =  3*cp1x*(1-t)*(1-3*t) + 3*cp2x*t*(2-3*t) + 3*t*t;
            if (Math.abs(dx) < 1e-6f) break;
            t -= (x - input) / dx;
            t  = Math.max(0f, Math.min(1f, t));
        }
        return 3*cp1y*t*(1-t)*(1-t) + 3*cp2y*t*t*(1-t) + t*t*t;
    }

    public static float resolveLayerAlpha(BrushDescriptor b) {
        return b.opacity / 100f;
    }

    public static float resolveDabAlpha(BrushDescriptor b, float pressure) {
        if (b.pressureControlsOpacity && b.opacityCurveTable != null) {
            int idx = (int) (pressure * 255f);
            idx = Math.max(0, Math.min(255, idx));
            float t = b.opacityCurveTable[idx];
            float min = b.opacityMin / 100f;
            float max = b.opacityMax / 100f;
            return min + (max - min) * t;
        }
        return 1.0f;
    }
}