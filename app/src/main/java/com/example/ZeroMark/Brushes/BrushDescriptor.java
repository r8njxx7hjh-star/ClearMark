package com.example.zeromark.brushes;

import java.util.UUID;

public class BrushDescriptor {

    // ─── Identity ─────────────────────────────────────────────────
    public String id;
    public String name;

    // ─── Color ────────────────────────────────────────────────────
    public int color;                          // ARGB — opacity baked in at paint time

    // ─── Size ─────────────────────────────────────────────────────
    public int   size;                         // px — base midpoint size
    public int   sizeMin;                      // % of size — floor  (e.g. 20 = can shrink to 20%)
    public int   sizeMax;                      // % of size — ceiling (e.g. 150 = can grow to 150%)
    public boolean pressureControlsSize;
    public float[] sizePressureCurve;          // [cp1x, cp1y, cp2x, cp2y] in 0..1 space
    public boolean tiltControlsSize;
    public float[] sizeTiltCurve;              // same format

    // ─── Opacity ──────────────────────────────────────────────────
    public int   opacity;                      // 0–100 base opacity
    public int   opacityMin;                   // % — floor
    public int   opacityMax;                   // % — ceiling
    public boolean pressureControlsOpacity;
    public float[] opacityPressureCurve;       // same format

    // ─── Smoothing ────────────────────────────────────────────────
    public int smoothing;                      // 0–100

    // ─── Stamp engine ─────────────────────────────────────────────
    public int    spacing;                     // 1–100: 1 = solid stroke (10% of diameter), 100 = 250% of diameter apart
    public String shapeTipAssetId;             // brush tip bitmap reference — null = circle
    public String grainAssetId;               // paper/fiber texture reference — null = none

    // ─── Per-stamp jitter ─────────────────────────────────────────
    public int     jitterSize;                 // % random size variance per stamp
    public int     jitterOpacity;              // % random opacity variance per stamp
    public int     jitterRotation;             // degrees random rotation per stamp
    public boolean followStrokeAngle;          // tip rotates to match stroke direction

    // ─── Tilt ─────────────────────────────────────────────────────
    public boolean tiltControlsAngle;          // stylus azimuth rotates the tip
    public float[] angleTiltCurve;             // same format

    // ─── Flow ─────────────────────────────────────────────────────
    // Flow controls how much paint each individual dab deposits within a stroke.
    // Unlike Opacity (which is applied once at layer-composite time), Flow is a
    // per-dab multiplier: low Flow = many overlapping dabs needed to reach full
    // density; high Flow = each dab immediately deposits close to full color.
    // Separating the two prevents the "opacity stacks when you retrace" problem:
    // Opacity caps the whole stroke; Flow controls buildup inside it.
    public int   flow;                         // 0–100 base flow
    public int   flowMin;                      // % — floor  (applied to flow)
    public int   flowMax;                      // % — ceiling
    public boolean pressureControlsFlow;       // pressure modulates flow
    public float[] flowPressureCurve;          // [cp1x, cp1y, cp2x, cp2y] in 0..1 space

    // ─── Blend mode ───────────────────────────────────────────────
    public BlendMode blendMode;

    // ─── Pre-calculated tables ────────────────────────────────────
    public transient float[] sizeCurveTable;
    public transient float[] opacityCurveTable;
    public transient float[] flowCurveTable;
    private static final int TABLE_SIZE = 256;

    public void updateLookupTables() {
        sizeCurveTable    = createTable(sizePressureCurve);
        opacityCurveTable = createTable(opacityPressureCurve);
        flowCurveTable    = createTable(flowPressureCurve);
    }

    private float[] createTable(float[] curve) {
        if (curve == null) return null;
        float[] table = new float[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            table[i] = com.example.zeromark.canvas.BrushResolver.evaluateCurve(curve, i / (float)(TABLE_SIZE - 1));
        }
        return table;
    }

    public enum BlendMode {
        NORMAL,
        MULTIPLY,
        SCREEN,
        OVERLAY,
        CLEAR,
    }

    public BrushDescriptor copy() {
        BrushDescriptor b = new BrushDescriptor();
        b.id = this.id;
        b.name = this.name;
        b.color = this.color;
        b.size = this.size;
        b.sizeMin = this.sizeMin;
        b.sizeMax = this.sizeMax;
        b.pressureControlsSize = this.pressureControlsSize;
        b.sizePressureCurve = this.sizePressureCurve != null ? this.sizePressureCurve.clone() : null;
        b.tiltControlsSize = this.tiltControlsSize;
        b.sizeTiltCurve = this.sizeTiltCurve != null ? this.sizeTiltCurve.clone() : null;
        b.opacity = this.opacity;
        b.opacityMin = this.opacityMin;
        b.opacityMax = this.opacityMax;
        b.pressureControlsOpacity = this.pressureControlsOpacity;
        b.opacityPressureCurve = this.opacityPressureCurve != null ? this.opacityPressureCurve.clone() : null;
        b.smoothing = this.smoothing;
        b.spacing = this.spacing;
        b.shapeTipAssetId = this.shapeTipAssetId;
        b.grainAssetId = this.grainAssetId;
        b.jitterSize = this.jitterSize;
        b.jitterOpacity = this.jitterOpacity;
        b.jitterRotation = this.jitterRotation;
        b.followStrokeAngle = this.followStrokeAngle;
        b.tiltControlsAngle = this.tiltControlsAngle;
        b.angleTiltCurve = this.angleTiltCurve != null ? this.angleTiltCurve.clone() : null;
        b.flow = this.flow;
        b.flowMin = this.flowMin;
        b.flowMax = this.flowMax;
        b.pressureControlsFlow = this.pressureControlsFlow;
        b.flowPressureCurve = this.flowPressureCurve != null ? this.flowPressureCurve.clone() : null;
        b.blendMode = this.blendMode;
        
        // Copy pre-calculated tables to avoid re-calculation
        b.sizeCurveTable = this.sizeCurveTable;
        b.opacityCurveTable = this.opacityCurveTable;
        b.flowCurveTable = this.flowCurveTable;
        
        return b;
    }

    // =================================================================
    // Constructor
    // =================================================================

    private BrushDescriptor() {}

    // =================================================================
    // Presets
    // =================================================================

    public static BrushDescriptor inkPen() {
        BrushDescriptor b         = new BrushDescriptor();
        b.id                      = UUID.randomUUID().toString();
        b.name                    = "Ink Pen";
        b.color                   = 0xFF000000;

        b.size                    = 4;
        b.sizeMin                 = 50;
        b.sizeMax                 = 200;
        b.pressureControlsSize    = false; //works
        b.sizePressureCurve       = new float[]{0.25f, 0f, 0.75f, 1f};
        b.tiltControlsSize        = false; //doesn't appear to work or my pen does not have it
        b.sizeTiltCurve           = LINEAR_CURVE; //haven't tried but linear works

        b.opacity                 = 100; //works fine
        b.opacityMin              = 100; //works fine
        b.opacityMax              = 100; //works fine
        b.pressureControlsOpacity = true; // per-dab opacity — stamped individually with gradient interpolation between segment endpoints so there is no steep jump
        b.opacityPressureCurve    = LINEAR_CURVE; //haven't tried but linear works

        b.smoothing               = 13; // maximum smoothing — curve extended so sf@100 = 0.033 (50% more lag than the old sf@100 = 0.05)

        b.flow                    = 20; // full flow — ink pen deposits immediately at full density
        b.flowMin                 = 100; // flat: pressure doesn't vary flow on an ink pen
        b.flowMax                 = 100;
        b.pressureControlsFlow    = false;
        b.flowPressureCurve       = LINEAR_CURVE;

        b.spacing                 = 3; //does work but not how it should, only works when drawing super fast and the spacing is inconsistant on the brush. I tried 5 and 4000 5 is good for normal strokes and 4000 is required for me to realy see the difference
        b.shapeTipAssetId         = null;

        b.grainAssetId            = null;

        b.jitterSize              = 0; //does not work
        b.jitterOpacity           = 0; //haven't tried
        b.jitterRotation          = 0; //haven't tried
        b.followStrokeAngle       = false; //doesn't work with a round brush, haven't tried

        b.tiltControlsAngle       = false; //doesn't work with a round brush, haven't tried
        b.angleTiltCurve          = LINEAR_CURVE; //doesn't work with a round brush, haven't tried

        b.blendMode               = BlendMode.SCREEN; //doesn't work
        b.updateLookupTables();
        return b;
    }

    public static BrushDescriptor softPencil() {
        BrushDescriptor b         = new BrushDescriptor();
        b.id                      = UUID.randomUUID().toString();
        b.name                    = "Soft Pencil";
        b.color                   = 0xFF1A1A1A;

        b.size                    = 8;
        b.sizeMin                 = 30;
        b.sizeMax                 = 120;
        b.pressureControlsSize    = true;
        b.sizePressureCurve       = new float[]{0.5f, 0f, 1f, 0.5f};
        b.tiltControlsSize        = true;
        b.sizeTiltCurve           = new float[]{0f, 0f, 0.5f, 1f};

        b.opacity                 = 80;
        b.opacityMin              = 20;
        b.opacityMax              = 100;
        b.pressureControlsOpacity = true;
        b.opacityPressureCurve    = new float[]{0f, 0f, 0.5f, 1f};

        b.smoothing               = 55;

        b.flow                    = 40;  // low flow = gradual pencil-like buildup per dab
        b.flowMin                 = 10;  // very light touch barely deposits
        b.flowMax                 = 80;  // hard press approaches but never hits 100 — pencil-like
        b.pressureControlsFlow    = true;
        b.flowPressureCurve       = new float[]{0f, 0f, 0.5f, 1f};

        b.spacing                 = 8;
        b.shapeTipAssetId         = null;
        b.grainAssetId            = "grain_paper";

        b.jitterSize              = 5;
        b.jitterOpacity           = 10;
        b.jitterRotation          = 180;
        b.followStrokeAngle       = true;

        b.tiltControlsAngle       = true;
        b.angleTiltCurve          = LINEAR_CURVE;

        b.blendMode               = BlendMode.MULTIPLY;
        b.updateLookupTables();
        return b;
    }

    public static BrushDescriptor marker() {
        BrushDescriptor b         = new BrushDescriptor();
        b.id                      = UUID.randomUUID().toString();
        b.name                    = "Marker";
        b.color                   = 0xFFFFEB3B;

        b.size                    = 24;
        b.sizeMin                 = 80;
        b.sizeMax                 = 110;
        b.pressureControlsSize    = false;
        b.sizePressureCurve       = LINEAR_CURVE;
        b.tiltControlsSize        = false;
        b.sizeTiltCurve           = LINEAR_CURVE;

        b.opacity                 = 60;
        b.opacityMin              = 60;
        b.opacityMax              = 60;           // flat opacity — marker doesn't vary
        b.pressureControlsOpacity = false;
        b.opacityPressureCurve    = LINEAR_CURVE;

        b.smoothing               = 20;

        b.flow                    = 80;  // marker floods color quickly
        b.flowMin                 = 80;  // flat — marker flow doesn't vary with pressure
        b.flowMax                 = 80;
        b.pressureControlsFlow    = false;
        b.flowPressureCurve       = LINEAR_CURVE;

        b.spacing                 = 3;
        b.shapeTipAssetId         = "tip_flat";   // wide flat chisel tip
        b.grainAssetId            = null;

        b.jitterSize              = 0;
        b.jitterOpacity           = 0;
        b.jitterRotation          = 0;
        b.followStrokeAngle       = true;         // flat tip rotates with stroke

        b.tiltControlsAngle       = false;
        b.angleTiltCurve          = LINEAR_CURVE;

        b.blendMode               = BlendMode.MULTIPLY;
        b.updateLookupTables();
        return b;
    }

    // =================================================================
    // Curve constants
    // =================================================================

    public static final float[] LINEAR_CURVE      = {0f,    0f,    1f,    1f   };
    public static final float[] HEAVY_TIP_CURVE   = {0f,    0f,    0.25f, 1f   };
    public static final float[] PENCIL_CURVE      = {0.5f,  0f,    1f,    0.5f };
    public static final float[] SOFT_CURVE        = {0f,    0.5f,  0.75f, 1f   };
    public static final float[] HARD_SNAP_CURVE   = {0.8f,  0f,    1f,    1f   }; // nearly binary

    // =================================================================
    // Builder — for user-created/edited brushes
    // =================================================================

    public static class Builder {
        private final BrushDescriptor b = new BrushDescriptor();

        public Builder(String name) {
            b.id   = UUID.randomUUID().toString();
            b.name = name;
            // safe defaults so nothing is ever null
            b.sizePressureCurve    = LINEAR_CURVE;
            b.sizeTiltCurve        = LINEAR_CURVE;
            b.opacityPressureCurve = LINEAR_CURVE;
            b.flowPressureCurve    = LINEAR_CURVE;
            b.angleTiltCurve       = LINEAR_CURVE;
            b.blendMode            = BlendMode.NORMAL;
            b.sizeMin              = 5;
            b.sizeMax              = 100;
            b.opacityMin           = 100;
            b.opacityMax           = 100;
            b.flow                 = 100;
            b.flowMin              = 0;
            b.flowMax              = 100;
            b.spacing              = 5;
        }

        public Builder color(int argb)                    { b.color = argb;                       return this; }
        public Builder size(int px)                       { b.size  = px;                         return this; }
        public Builder sizeRange(int min, int max)        { b.sizeMin = min; b.sizeMax = max;      return this; }
        public Builder pressureControlsSize(boolean v)   { b.pressureControlsSize = v;            return this; }
        public Builder sizeCurve(float[] curve)           { b.sizePressureCurve = curve;           return this; }
        public Builder tiltControlsSize(boolean v)        { b.tiltControlsSize = v;               return this; }
        public Builder opacity(int pct)                   { b.opacity = pct;                       return this; }
        public Builder opacityRange(int min, int max)     { b.opacityMin = min; b.opacityMax = max; return this; }
        public Builder pressureControlsOpacity(boolean v){ b.pressureControlsOpacity = v;         return this; }
        public Builder opacityCurve(float[] curve)        { b.opacityPressureCurve = curve;        return this; }
        public Builder smoothing(int pct)                 { b.smoothing = pct;                     return this; }
        public Builder spacing(int pct)                   { b.spacing  = pct;                      return this; }
        public Builder shapeTip(String assetId)           { b.shapeTipAssetId = assetId;           return this; }
        public Builder grain(String assetId)              { b.grainAssetId    = assetId;           return this; }
        public Builder jitter(int size, int opacity, int rotation) {
            b.jitterSize = size; b.jitterOpacity = opacity; b.jitterRotation = rotation;
            return this;
        }
        public Builder followStrokeAngle(boolean v)       { b.followStrokeAngle = v;              return this; }
        public Builder tiltControlsAngle(boolean v)       { b.tiltControlsAngle = v;              return this; }
        public Builder blendMode(BlendMode mode)          { b.blendMode = mode;                   return this; }
        public Builder flow(int pct)                      { b.flow = pct;                         return this; }
        public Builder flowRange(int min, int max)        { b.flowMin = min; b.flowMax = max;      return this; }
        public Builder pressureControlsFlow(boolean v)   { b.pressureControlsFlow = v;           return this; }
        public Builder flowCurve(float[] curve)           { b.flowPressureCurve = curve;          return this; }

        public BrushDescriptor build() {
            b.updateLookupTables();
            return b;
        }



    }

    // =================================================================
// Setters — for runtime mutation (UI sliders, color picker, etc.)
// =================================================================

    public BrushDescriptor setName(String name)        { this.name = name;               return this; }
    public BrushDescriptor setColor(int argb)          { this.color = argb;              return this; }

    // ─── Size ─────────────────────────────────────────────────────
    public BrushDescriptor setSize(int px)             { this.size = px;                 return this; }
    public BrushDescriptor setSizeMin(int pct)         { this.sizeMin = pct;             return this; }
    public BrushDescriptor setSizeMax(int pct)         { this.sizeMax = pct;             return this; }
    public BrushDescriptor setPressureControlsSize(boolean v)    { this.pressureControlsSize = v;    return this; }
    public BrushDescriptor setSizeCurve(float[] curve)           { this.sizePressureCurve = curve;   return this; }
    public BrushDescriptor setTiltControlsSize(boolean v)        { this.tiltControlsSize = v;        return this; }
    public BrushDescriptor setSizeTiltCurve(float[] curve)       { this.sizeTiltCurve = curve;       return this; }

    // ─── Opacity ──────────────────────────────────────────────────
    public BrushDescriptor setOpacity(int pct)         { this.opacity = pct;             return this; }
    public BrushDescriptor setOpacityMin(int pct)      { this.opacityMin = pct;          return this; }
    public BrushDescriptor setOpacityMax(int pct)      { this.opacityMax = pct;          return this; }
    public BrushDescriptor setPressureControlsOpacity(boolean v) { this.pressureControlsOpacity = v; return this; }
    public BrushDescriptor setOpacityCurve(float[] curve)        { this.opacityPressureCurve = curve; return this; }

    // ─── Smoothing & spacing ──────────────────────────────────────
    public BrushDescriptor setSmoothing(int pct)       { this.smoothing = pct;           return this; }
    public BrushDescriptor setSpacing(int pct)         { this.spacing = pct;             return this; }

    // ─── Stamp ────────────────────────────────────────────────────
    public BrushDescriptor setShapeTip(String assetId) { this.shapeTipAssetId = assetId; return this; }
    public BrushDescriptor setGrain(String assetId)    { this.grainAssetId = assetId;    return this; }

    // ─── Jitter ───────────────────────────────────────────────────
    public BrushDescriptor setJitterSize(int pct)          { this.jitterSize = pct;      return this; }
    public BrushDescriptor setJitterOpacity(int pct)       { this.jitterOpacity = pct;   return this; }
    public BrushDescriptor setJitterRotation(int deg)      { this.jitterRotation = deg;  return this; }
    public BrushDescriptor setFollowStrokeAngle(boolean v) { this.followStrokeAngle = v; return this; }

    // ─── Tilt ─────────────────────────────────────────────────────
    public BrushDescriptor setTiltControlsAngle(boolean v)  { this.tiltControlsAngle = v;  return this; }
    public BrushDescriptor setAngleTiltCurve(float[] curve) { this.angleTiltCurve = curve; return this; }

    // ─── Flow ─────────────────────────────────────────────────────
    public BrushDescriptor setFlow(int pct)                      { this.flow = pct;                   return this; }
    public BrushDescriptor setFlowMin(int pct)                   { this.flowMin = pct;                return this; }
    public BrushDescriptor setFlowMax(int pct)                   { this.flowMax = pct;                return this; }
    public BrushDescriptor setPressureControlsFlow(boolean v)    { this.pressureControlsFlow = v;     return this; }
    public BrushDescriptor setFlowCurve(float[] curve)           { this.flowPressureCurve = curve;    return this; }

    // ─── Blend mode ───────────────────────────────────────────────
    public BrushDescriptor setBlendMode(BlendMode mode) { this.blendMode = mode;         return this; }
}