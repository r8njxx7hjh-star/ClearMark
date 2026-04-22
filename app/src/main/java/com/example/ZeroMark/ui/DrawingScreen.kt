package com.example.zeromark.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.zeromark.brushes.ToolManager
import com.example.zeromark.canvas.CanvasOverlay
import com.example.zeromark.canvas.FastDrawingView
import com.example.zeromark.model.CanvasSettings
import kotlinx.coroutines.delay

@Composable
fun DrawingScreen(canvasSettings: CanvasSettings) {
    var selectedTool       by remember { mutableStateOf(ToolManager.ToolType.PEN) }
    var brushSize          by remember { mutableFloatStateOf(40f) }
    var selectedColorIndex by remember { mutableIntStateOf(0) }
    var colors by remember {
        mutableStateOf(listOf(Color.Black, Color.Red, Color.Blue, Color.Green, Color.Yellow))
    }
    val selectedColor = colors.getOrElse(selectedColorIndex) { Color.Black }
    
    var zoomLevel by remember { mutableFloatStateOf(1f) }
    var showZoom by remember { mutableStateOf(false) }

    LaunchedEffect(zoomLevel) {
        showZoom = true
        delay(1000)
        showZoom = false
    }

    // Push initial values into ToolManager once on first composition
    LaunchedEffect(Unit) {
        ToolManager.getInstance().setCurrentTool(ToolManager.ToolType.PEN, 0)
        val brush = ToolManager.getInstance().getActiveBrush()
        brush?.let {
            it.setSize(brushSize.toInt())
            it.setOpacity(100)
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
                AndroidView(
                    factory  = { ctx ->
                        android.widget.FrameLayout(ctx).apply {
                            val drawingView = FastDrawingView(ctx, canvasSettings)
                            val overlay     = CanvasOverlay(ctx)
                            drawingView.setCanvasOverlay(overlay)
                            drawingView.setZoomListener { zoom -> zoomLevel = zoom }
                            addView(drawingView, android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                            addView(overlay,     android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                            drawingView.requestFocus()
                        }
                    },
                    onRelease = { frameLayout ->
                        // frameLayout.getChildAt(0) should be the drawingView
                        val drawingView = frameLayout.getChildAt(0) as? FastDrawingView
                        drawingView?.release()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            DrawingSidebar(
                brushSize         = brushSize,
                onBrushSizeChange = { size ->
                    brushSize = size
                    ToolManager.getInstance().getActiveBrush()?.setSize(size.toInt())
                }
            )

            Box(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.TopCenter) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showZoom,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x88000000))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "${(zoomLevel * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }


                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
fun DrawingScreenPreview() {
    DrawingScreen(CanvasSettings())
}
