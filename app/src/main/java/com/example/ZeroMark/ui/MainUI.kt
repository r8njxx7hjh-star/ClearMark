package com.example.ZeroMark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ZeroMark.ToolManager
import com.example.ZeroMark.FastDrawingView
import com.example.ZeroMark.CanvasOverlay
import com.example.ZeroMark.R

// ── Vertical slider ───────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val trackWidth = 275.dp
    val trackHeight = 40.dp
    val trackThickness = 6.dp
    val trackCornerRadius = 2.dp

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = 1f..100f,
        modifier = modifier
            .graphicsLayer { rotationZ = 270f }
            .requiredWidth(trackWidth),
        thumb = {
            Image(
                painter = painterResource(id = R.drawable.slider_thumb),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(31.dp)
                    .graphicsLayer { 
                        // The Slider is rotated 270 degrees. 
                        // We rotate the thumb 90 degrees to make it appear upright.
                        rotationZ = 90f 
                    }
            )
        },
        track = { sliderState ->
            val progressFraction = (sliderState.value - 1f) / 99f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight),
                contentAlignment = Alignment.Center
            ) {
                // Empty track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackThickness)
                        .clip(RoundedCornerShape(trackCornerRadius))
                        .background(Color(0xFFE0F3FF))
                )

                // Filled track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(trackThickness)
                        .clip(RoundedCornerShape(trackCornerRadius)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(Color(0xFF51A0D5))
                    )
                }
            }
        }
    )
}

// ── Toolbar ───────────────────────────────────────────────────────────────────
@Composable
fun DrawingToolbar(
    selectedTool: ToolManager.ToolType,
    onToolSelected: (ToolManager.ToolType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(Color(0xFF1D1D1D))
            .padding(start = 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { onToolSelected(ToolManager.ToolType.PEN) },
            modifier = Modifier.size(50.dp)
        ) {
            Image(
                painter = painterResource(
                    if (selectedTool == ToolManager.ToolType.PEN)
                        R.drawable.brush_active
                    else
                        R.drawable.brush_inactive
                ),
                contentDescription = "Brush tool"
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(
            onClick = { onToolSelected(ToolManager.ToolType.ERASER) },
            modifier = Modifier.size(50.dp)
        ) {
            Image(
                painter = painterResource(
                    if (selectedTool == ToolManager.ToolType.ERASER)
                        R.drawable.eraser_active
                    else
                        R.drawable.eraser_inactive
                ),
                contentDescription = "Eraser tool"
            )
        }
    }
}

// ── Sidebar ───────────────────────────────────────────────────────────────────
@Composable
fun DrawingSidebar(
    pressure: Float,
    smoothing: Float,
    onPressureChange: (Float) -> Unit,
    onSmoothingChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .width(60.dp)
            .fillMaxHeight()
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AssetVerticalSlider(
                value = pressure,
                onValueChange = { value ->
                    onPressureChange(value)
                    ToolManager.getInstance().getActivePen().setPressureSensitivity(value)
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AssetVerticalSlider(
                value = smoothing,
                onValueChange = { value ->
                    onSmoothingChange(value)
                    ToolManager.getInstance().getActivePen().setSmoothing(value)
                }
            )
        }
    }
}

// ── Full screen ───────────────────────────────────────────────────────────────
@Composable
fun DrawingScreen() {
    var selectedTool by remember { mutableStateOf(ToolManager.ToolType.PEN) }
    var pressure     by remember { mutableFloatStateOf(10f) }
    var smoothing    by remember { mutableFloatStateOf(10f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1D1D1D))
    ) {
        Spacer(modifier = Modifier.height(25.dp))
        DrawingToolbar(
            selectedTool = selectedTool,
            onToolSelected = { tool ->
                selectedTool = tool
                ToolManager.getInstance().setCurrentTool(tool, 0)
            }
        )

        Row(modifier = Modifier.fillMaxSize()) {
            DrawingSidebar(
                pressure = pressure,
                smoothing = smoothing,
                onPressureChange = { pressure = it },
                onSmoothingChange = { smoothing = it }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFE6E6E6))
            ) {
                DrawingCanvas()
            }
        }
    }
}

// ── Embeds your existing custom Views ────────────────────────────────────────
@Composable
fun DrawingCanvas() {
    AndroidView(
        factory = { ctx ->
            android.widget.FrameLayout(ctx).apply {
                val drawingView = FastDrawingView(ctx)
                val overlay = CanvasOverlay(ctx)

                drawingView.setCanvasOverlay(overlay)

                addView(drawingView, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                addView(overlay, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)

                drawingView.requestFocus()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Preview(showBackground = true)
@Composable
fun DrawingScreenPreview() {
    DrawingScreen()
}
