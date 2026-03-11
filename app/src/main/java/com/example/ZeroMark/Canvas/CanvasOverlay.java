package com.example.ZeroMark.Canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.example.ZeroMark.Brushes.ToolManager;

public class CanvasOverlay extends View {

    private float cursorX, cursorY, cursorPressure;

    // ─── Tether state ─────────────────────────────────────────────
    // tetherPrevX/Y: the smoothed point before the tip — gives us the
    //               incoming stroke direction to bend the tether curve.
    // tetherAx/Ay:  smoothed tip (start of the tether).
    // tetherBx/By:  raw touch position (end of the tether).
    // tetherCarry:  live spacingCarry at the smoothed tip, so tether dabs
    //               land exactly where the committed stroke will extend —
    //               prevents any visual shift when the pen lifts.
    private float tetherPrevX, tetherPrevY;
    private float tetherAx, tetherAy, tetherAp;
    private float tetherBx, tetherBy, tetherBp;
    private float tetherCarry;

    private boolean eraserVisible = false;
    private boolean tetherVisible = false;

    private final Paint cursorPaint;
    private final Paint tetherPaint;  // filled circle, same style as the stroke brush

    public CanvasOverlay(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);

        cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setColor(Color.GRAY);
        cursorPaint.setStrokeWidth(4f);

        tetherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tetherPaint.setStyle(Paint.Style.FILL);
    }

    public void updateCursor(float x, float y, float pressure) {
        cursorX = x;
        cursorY = y;
        cursorPressure = pressure;
        eraserVisible = true;
        invalidate();
    }

    public void hideCursor() {
        eraserVisible = false;
        invalidate();
    }

    /**
     * Called every touch event with:
     *   prevX/prevY  — the smoothed point before the current tip (for stroke direction)
     *   ax/ay/ap     — current smoothed tip (start of tether)
     *   bx/by/bp     — raw touch position  (end of tether)
     *   carry        — live spacingCarry so dab positions align with the committed stroke
     */
    public void updateTether(float prevX, float prevY,
                             float ax, float ay, float ap,
                             float bx, float by, float bp,
                             float carry) {
        tetherPrevX = prevX; tetherPrevY = prevY;
        tetherAx = ax; tetherAy = ay; tetherAp = ap;
        tetherBx = bx; tetherBy = by; tetherBp = bp;
        tetherCarry = carry;
        tetherVisible = true;
        invalidate();
    }

    public void hideTether() {
        tetherVisible = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (eraserVisible) {
            float r = BrushResolver.resolveSize(
                    ToolManager.getInstance().getActiveBrush(), cursorPressure);
            canvas.drawCircle(cursorX, cursorY, r / 2f, cursorPaint);
        }

        if (tetherVisible) {
            com.example.ZeroMark.Brushes.BrushDescriptor brush =
                    ToolManager.getInstance().getActiveBrush();
            if (brush == null) return;

            tetherPaint.setColor(brush.color);

            float wa = BrushResolver.resolveSize(brush, tetherAp);
            float wb = BrushResolver.resolveSize(brush, tetherBp);
            float avgDiam = (wa + wb) / 2f;
            float step    = Math.max(1f, avgDiam * BrushResolver.resolveSpacingMultiplier(brush));

            // ── Stroke-direction–curved tether ──────────────────────────────────
            // Build a quadratic Bézier from the smoothed tip to the raw touch.
            // The control point is placed along the incoming stroke direction so the
            // tether naturally bends the way the stroke is travelling rather than
            // poking out at an arbitrary angle — much more organic to look at.
            //
            //   P0  = smoothed tip   (tetherAx/Ay)
            //   P1  = control point  = P0 + strokeDir × (tether_length × 0.85)
            //   P2  = raw touch      (tetherBx/By)
            float strokeDx     = tetherAx - tetherPrevX;
            float strokeDy     = tetherAy - tetherPrevY;
            float strokeDirLen = (float) Math.sqrt(strokeDx * strokeDx + strokeDy * strokeDy);
            float dirX = strokeDirLen > 0.5f ? strokeDx / strokeDirLen : 0f;
            float dirY = strokeDirLen > 0.5f ? strokeDy / strokeDirLen : 0f;

            float rawDx  = tetherBx - tetherAx;
            float rawDy  = tetherBy - tetherAy;
            float rawLen = (float) Math.sqrt(rawDx * rawDx + rawDy * rawDy);

            // Control point: extends from the tip along the stroke direction.
            // 0.85× tether length gives a much more pronounced bend than the old 0.60×,
            // making the tether swing into place like real ink following a pen —
            // shorter strokes stay organic while longer ones arc noticeably.
            float cpLen = Math.min(rawLen * 0.85f, rawLen);
            float cpX   = tetherAx + dirX * cpLen;
            float cpY   = tetherAy + dirY * cpLen;

            // ── Arc-length parameterisation (N = 32 samples) ────────────────────
            // Pre-sample the Bézier so we can place dabs by distance rather than
            // by t-parameter, which would cause bunching on curved tethers.
            final int N = 32;
            float[] bxArr  = new float[N + 1];
            float[] byArr  = new float[N + 1];
            float[] arcArr = new float[N + 1];
            arcArr[0] = 0f;
            for (int i = 0; i <= N; i++) {
                float t  = (float) i / N;
                float mt = 1f - t;
                bxArr[i]  = mt * mt * tetherAx + 2f * mt * t * cpX + t * t * tetherBx;
                byArr[i]  = mt * mt * tetherAy + 2f * mt * t * cpY + t * t * tetherBy;
                if (i > 0) {
                    float sdx = bxArr[i] - bxArr[i - 1];
                    float sdy = byArr[i] - byArr[i - 1];
                    arcArr[i] = arcArr[i - 1] + (float) Math.sqrt(sdx * sdx + sdy * sdy);
                }
            }
            float totalLen = arcArr[N];
            if (totalLen < 0.5f) return; // tether has zero length — nothing to draw

            // ── Carry-aligned dab placement ─────────────────────────────────────
            // Start from the live spacingCarry so the first tether dab lands exactly
            // where the committed stroke would place its next dab. This is what
            // prevents dab positions from shifting when the pen lifts.
            float distAlongCurve = step - tetherCarry;
            int   seg            = 1;

            while (distAlongCurve <= totalLen) {
                // Advance the arc-length pointer (monotonically increasing — no reset needed)
                while (seg < N && arcArr[seg] < distAlongCurve) seg++;

                float denom = arcArr[seg] - arcArr[seg - 1];
                float frac  = denom > 1e-4f
                        ? (distAlongCurve - arcArr[seg - 1]) / denom
                        : 0f;
                float px = bxArr[seg - 1] + (bxArr[seg] - bxArr[seg - 1]) * frac;
                float py = byArr[seg - 1] + (byArr[seg] - byArr[seg - 1]) * frac;

                float tPos      = distAlongCurve / totalLen;
                float pressure  = tetherAp + (tetherBp - tetherAp) * tPos;
                float baseAlpha = BrushResolver.resolveOpacity(brush, pressure);

                // ── Dual fade: opacity + size taper ──────────────────────────────
                // Opacity: power-2.2 stays full near the smoothed tip then drops off
                //          quickly at the trailing end — more "ink bleeding out" than
                //          the old 1.5 exponent which faded too gradually.
                // Size:    linear taper from 100% at the tip down to 35% at the raw
                //          touch. The pointed calligraphic tail clearly communicates
                //          directionality and makes the streamline feel like a live
                //          ink prediction rather than a floating overlay.
                float fade      = (float) Math.pow(1.0f - tPos, 2.2f);
                float sizeTaper = 1.0f - tPos * 0.65f;   // 1.0 → 0.35 along tether
                float r         = (wa + (wb - wa) * tPos) / 2f * sizeTaper;
                tetherPaint.setAlpha((int) (baseAlpha * fade * 255f));
                canvas.drawCircle(px, py, r, tetherPaint);

                distAlongCurve += step;
            }
        }
    }
}
