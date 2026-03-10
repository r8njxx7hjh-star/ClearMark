package com.example.ZeroMark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ZeroMark.Brushes.ToolManager
import com.example.ZeroMark.Canvas.CanvasOverlay
import com.example.ZeroMark.Canvas.FastDrawingView

@Composable
fun DrawingScreen() {
    var selectedTool       by remember { mutableStateOf(ToolManager.ToolType.PEN) }
    var brushSize          by remember { mutableFloatStateOf(40f) }
    var opacity            by remember { mutableFloatStateOf(40f) }
    var selectedColorIndex by remember { mutableIntStateOf(1) }
    var colors by remember {
        mutableStateOf(listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color.Green, Color.Yellow))
    }
    val selectedColor = colors.getOrElse(selectedColorIndex) { Color.Black }

    // Push initial values into ToolManager once on first composition
    LaunchedEffect(Unit) {
        val brush = ToolManager.getInstance().getActiveBrush()
        brush?.let {
            it.setSize(brushSize.toInt())
            it.setOpacity(opacity.toInt())
            it.setColor(selectedColor.toArgb())
        }
    }

    Column(Modifier.fillMaxSize().background(ColorBackground)) {
        DrawingToolbar(
            selectedTool       = selectedTool,
            onToolSelected     = { tool ->
                selectedTool = tool
                ToolManager.getInstance().setCurrentTool(tool, 0)
            },
            selectedColor      = selectedColor,
            selectedColorIndex = selectedColorIndex,
            colors             = colors,
            onColorSelected    = { idx ->
                selectedColorIndex = idx
                colors.getOrNull(idx)?.let {
                    ToolManager.getInstance().getActiveBrush()?.setColor(it.toArgb())
                }
            },
            onUpdateColor = { index, newColor ->
                colors = colors.toMutableList().also { it[index] = newColor }
                if (index == selectedColorIndex)
                    ToolManager.getInstance().getActiveBrush()?.setColor(newColor.toArgb())
            },
            onAddColor = { newColor ->
                colors = colors + newColor
                selectedColorIndex = colors.lastIndex
                ToolManager.getInstance().getActiveBrush()?.setColor(newColor.toArgb())
            },
            onDeleteColor = { index ->
                val list = colors.toMutableList().also { it.removeAt(index) }
                colors = list
                selectedColorIndex = when {
                    list.isEmpty()              -> 0
                    selectedColorIndex >= index -> (selectedColorIndex - 1).coerceAtLeast(0)
                    else                        -> selectedColorIndex
                }
                colors.getOrNull(selectedColorIndex)?.let {
                    ToolManager.getInstance().getActiveBrush()?.setColor(it.toArgb())
                }
            },
            onReorderColors = { reordered ->
                val currentColor   = colors.getOrNull(selectedColorIndex)
                colors             = reordered
                selectedColorIndex = if (currentColor != null) reordered.indexOf(currentColor).coerceAtLeast(0) else 0
            }
        )

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(ColorCanvas)) {
                DrawingCanvas()
            }
            DrawingSidebar(
                brushSize         = brushSize,
                onBrushSizeChange = { size ->
                    brushSize = size
                    ToolManager.getInstance().getActiveBrush()?.setSize(size.toInt())
                },
                opacity           = opacity,
                onOpacityChange   = { op ->
                    opacity = op
                    ToolManager.getInstance().getActiveBrush()?.setOpacity(op.toInt())
                }
            )
        }
    }
}

@Composable
fun DrawingCanvas() {
    AndroidView(
        factory  = { ctx ->
            android.widget.FrameLayout(ctx).apply {
                val drawingView = FastDrawingView(ctx)
                val overlay     = CanvasOverlay(ctx)
                drawingView.setCanvasOverlay(overlay)
                addView(drawingView, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                addView(overlay,     android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                drawingView.requestFocus()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun DrawingScreenPreview() {
    DrawingScreen()
}