package com.example.ZeroMark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ZeroMark.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 1f..100f,
    trackLength: Dp = 275.dp,
    trackBrush: Brush? = null,
    trackThickness: Dp = 6.dp,
    backgroundContent: (@Composable () -> Unit)? = null
) {
    val containerWidth = 40.dp
    Box(
        modifier = modifier.size(width = containerWidth, height = trackLength),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = valueRange,
            modifier      = Modifier
                .requiredWidth(trackLength)
                .height(containerWidth)
                .graphicsLayer { rotationZ = 270f },
            thumb = {
                Image(
                    painter            = painterResource(id = R.drawable.slider_thumb),
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    alignment          = Alignment.Center,
                    modifier           = Modifier
                        .size(30.dp)
                        .graphicsLayer { rotationZ = 90f }
                )
            },
            track = { sliderState ->
                Box(
                    modifier         = Modifier.fillMaxWidth().height(containerWidth),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(trackThickness)
                            .clip(RoundedCornerShape(trackThickness / 2))
                            .background(ColorSliderTrack)
                    ) {
                        backgroundContent?.invoke()
                        if (trackBrush != null) Box(Modifier.fillMaxSize().background(trackBrush))
                    }
                    if (trackBrush == null && backgroundContent == null) {
                        val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                        val pct   = if (range != 0f) (sliderState.value - sliderState.valueRange.start) / range else 0f
                        Box(
                            modifier         = Modifier
                                .fillMaxWidth()
                                .height(trackThickness)
                                .clip(RoundedCornerShape(trackThickness / 2)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(pct)
                                    .fillMaxHeight()
                                    .background(ColorSliderFill)
                            )
                        }
                    }
                }
            }
        )
    }
}