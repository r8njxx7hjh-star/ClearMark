package com.example.zeromark.canvas.model

import android.graphics.RectF
import com.example.zeromark.brushes.BrushDescriptor

data class Stroke(
    /** 
     * Flat array of points: [ax1, ay1, ap1, bx1, by1, bp1, ax2, ay2, ap2, ...]
     * Storing as a flat array significantly reduces object count and GC pressure.
     */
    val points: FloatArray,
    val brush: BrushDescriptor,
    val bounds: RectF
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Stroke) return false
        if (!points.contentEquals(other.points)) return false
        if (brush != other.brush) return false
        return true
    }

    override fun hashCode(): Int {
        var result = points.contentHashCode()
        result = 31 * result + brush.hashCode()
        return result
    }
}
