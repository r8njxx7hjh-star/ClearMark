package com.example.zeromark.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CanvasSettings(
    val type: CanvasType = CanvasType.INFINITE,
    val gridType: GridType = GridType.BLANK,
    val width: Int? = null,
    val height: Int? = null
) : Parcelable

enum class CanvasType {
    INFINITE,
    A4_VERTICAL,
    A4_HORIZONTAL,
    FIXED
}

enum class GridType {
    BLANK,
    LINED,
    SQUARED
}
