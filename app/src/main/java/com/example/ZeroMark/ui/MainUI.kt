package com.example.ZeroMark.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.ZeroMark.ToolManager
import com.example.ZeroMark.FastDrawingView
import com.example.ZeroMark.CanvasOverlay
import com.example.ZeroMark.R
import kotlin.math.*

// ── Vertical slider ───────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 1f..100f,
    trackWidth: Dp = 275.dp,
    modifier: Modifier = Modifier,
    trackBrush: Brush? = null
) {
    val trackHeight = 40.dp
    Box(
        modifier = modifier.size(width = trackHeight, height = trackWidth),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .requiredWidth(trackWidth)
                .graphicsLayer { rotationZ = 270f },
            thumb = {
                Image(
                    painter = painterResource(id = R.drawable.slider_thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(31.dp)
                        .graphicsLayer { rotationZ = 90f }
                )
            },
            track = { sliderState ->
                val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val progressFraction = if (range != 0f) (sliderState.value - sliderState.valueRange.start) / range else 0f
                Box(
                    modifier = Modifier.fillMaxWidth().height(trackHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(trackBrush ?: Brush.linearGradient(listOf(Color(0xFFE0F3FF), Color(0xFFE0F3FF))))
                    )
                    if (trackBrush == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(progressFraction).fillMaxHeight().background(Color(0xFF51A0D5)))
                        }
                    }
                }
            }
        )
    }
}

// ── Toolbar ───────────────────────────────────────────────────────────────────
@Composable
fun DrawingToolbar(
    selectedTool: ToolManager.ToolType,
    onToolSelected: (ToolManager.ToolType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(50.dp).background(Color(0xFF1D1D1D)).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onToolSelected(ToolManager.ToolType.PEN) }, modifier = Modifier.size(50.dp)) {
            Image(painter = painterResource(if (selectedTool == ToolManager.ToolType.PEN) R.drawable.brush_active else R.drawable.brush_inactive), contentDescription = "Brush")
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = { onToolSelected(ToolManager.ToolType.ERASER) }, modifier = Modifier.size(50.dp)) {
            Image(painter = painterResource(if (selectedTool == ToolManager.ToolType.ERASER) R.drawable.eraser_active else R.drawable.eraser_inactive), contentDescription = "Eraser")
        }
    }
}

// ── Color Picker List ──────────────────────────────────────────────────────────
@Composable
fun ColorPickerList(
    colors: List<Color>,
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    onUpdateColor: (Int, Color) -> Unit,
    onAddColor: (Color) -> Unit,
    onDeleteColor: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPopup by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(colors) { index, color ->
                    val isSelected = color == selectedColor
                    val isDragging = draggedIndex == index
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    
                    val scale by animateFloatAsState(
                        targetValue = if (isPressed || isDragging) 0.85f else 1f,
                        animationSpec = tween(100),
                        label = "scale"
                    )
                    val fadeAlpha by animateFloatAsState(
                        targetValue = if (isDragging && abs(dragOffset.x) > 50f) 0.4f else 1f,
                        animationSpec = tween(100),
                        label = "fade"
                    )

                    Box(
                        modifier = Modifier
                            .offset { if (isDragging) IntOffset(dragOffset.x.toInt(), dragOffset.y.toInt()) else IntOffset.Zero }
                            .scale(scale)
                            .alpha(fadeAlpha)
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                            .border(width = 2.dp, color = if (isSelected) Color(0xFF51A0D5) else Color.Gray, shape = RoundedCornerShape(8.dp))
                            .pointerInput(index, isSelected) {
                                detectTapGestures(
                                    onTap = { 
                                        if (isSelected) {
                                            editingIndex = index
                                            showPopup = true
                                        } else {
                                            onColorSelected(color)
                                        }
                                    },
                                    onPress = { offset ->
                                        val press = androidx.compose.foundation.interaction.PressInteraction.Press(offset)
                                        interactionSource.emit(press)
                                        try {
                                            awaitRelease()
                                        } finally {
                                            interactionSource.emit(androidx.compose.foundation.interaction.PressInteraction.Release(press))
                                        }
                                    }
                                )
                            }
                            .pointerInput(index) {
                                detectDragGestures(
                                    onDragStart = { draggedIndex = index },
                                    onDrag = { change, dragAmount ->
                                        dragOffset += dragAmount
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        if (dragOffset.x > 150f) onDeleteColor(index)
                                        draggedIndex = -1
                                        dragOffset = Offset.Zero
                                    },
                                    onDragCancel = { draggedIndex = -1; dragOffset = Offset.Zero }
                                )
                            }
                    )
                }
            }
            
            val addInteractionSource = remember { MutableInteractionSource() }
            val isAddPressed by addInteractionSource.collectIsPressedAsState()
            val addScale by animateFloatAsState(targetValue = if (isAddPressed) 0.85f else 1f, label = "addScale")

            IconButton(
                onClick = { 
                    editingIndex = -1
                    showPopup = true 
                },
                interactionSource = addInteractionSource,
                modifier = Modifier
                    .scale(addScale)
                    .size(40.dp)
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
            ) {
                Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = "Add Color", tint = Color.White)
            }
        }

        if (showPopup) {
            ColorPickerPopup(
                initialColor = if (editingIndex >= 0) colors.getOrNull(editingIndex) else null,
                onColorChanged = { newColor ->
                    if (editingIndex >= 0) {
                        onUpdateColor(editingIndex, newColor)
                    } else {
                        onAddColor(newColor)
                        editingIndex = colors.size // Now editing the newly added color
                    }
                },
                onDismissRequest = { 
                    showPopup = false 
                    editingIndex = -1
                }
            )
        }
    }
}

// ── HSV Color Wheel ───────────────────────────────────────────────────────────
@Composable
fun HsvWheel(
    hue: Float,
    saturation: Float,
    onHsvChange: (Float, Float) -> Unit,
    showCursor: Boolean,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    
    fun updateHsv(pos: Offset) {
        val center = canvasSize.center
        val dist = (pos - center).getDistance()
        val radius = canvasSize.minDimension / 2
        val innerRadius = radius * 0.95f

        if (dist <= innerRadius) {
            var angle = atan2(pos.y - center.y, pos.x - center.x) * 180 / PI
            if (angle < 0) angle += 360.0
            val sat = (dist / innerRadius).coerceIn(0f, 1f)
            onHsvChange(angle.toFloat(), sat.toFloat())
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it.toSize() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    updateHsv(down.position)
                    drag(down.id) { change ->
                        updateHsv(change.position)
                        change.consume()
                    }
                }
            }
    ) {
        val center = size.center
        val radius = size.minDimension / 2
        val innerRadius = radius * 0.95f
        
        val hueColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
        drawCircle(brush = Brush.sweepGradient(hueColors, center), radius = innerRadius)
        drawCircle(brush = Brush.radialGradient(colors = listOf(Color.White, Color.Transparent), center = center, radius = innerRadius), radius = innerRadius)
        
        if (showCursor) {
            val hueRad = (hue * PI / 180).toFloat()
            val wheelCursorPos = Offset(
                center.x + innerRadius * saturation * cos(hueRad),
                center.y + innerRadius * saturation * sin(hueRad)
            )
            drawCircle(Color.White, radius = 6.dp.toPx(), center = wheelCursorPos, style = Stroke(width = 2.dp.toPx()))
            drawCircle(Color.Black, radius = 7.dp.toPx(), center = wheelCursorPos, style = Stroke(width = 1.dp.toPx()))
        }
    }
}

// ── Color Picker Popup ────────────────────────────────────────────────────────
@Composable
fun ColorPickerPopup(
    initialColor: Color?,
    onColorChanged: (Color) -> Unit,
    onDismissRequest: () -> Unit
) {
    val originalColor = remember { initialColor ?: Color.Black }
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(1f) }
    var alpha by remember { mutableFloatStateOf(initialColor?.alpha ?: 1f) }
    
    // Track if the user has picked a color yet when adding new
    var hasInteracted by remember { mutableStateOf(initialColor != null) }

    LaunchedEffect(initialColor) {
        initialColor?.let {
            val hsvArr = FloatArray(3)
            android.graphics.Color.colorToHSV(it.toArgb(), hsvArr)
            hue = hsvArr[0]; saturation = hsvArr[1]; brightness = hsvArr[2]
            alpha = it.alpha
        }
    }

    val currentColor = Color.hsv(hue, saturation, brightness, alpha)

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(70, 100),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(360.dp).wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2A2A2A),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Color Picker", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.height(200.dp), verticalAlignment = Alignment.CenterVertically) {
                    HsvWheel(
                        hue = hue, saturation = saturation,
                        onHsvChange = { h, s ->
                            hue = h; saturation = s
                            hasInteracted = true
                            onColorChanged(Color.hsv(h, s, brightness, alpha))
                        },
                        showCursor = hasInteracted,
                        modifier = Modifier.size(200.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(id = R.drawable.brightness_icon),
                                contentDescription = "Brightness",
                                modifier = Modifier.size(20.dp)
                            )
                            AssetVerticalSlider(
                                value = brightness,
                                onValueChange = { 
                                    brightness = it
                                    if (hasInteracted) onColorChanged(Color.hsv(hue, saturation, it, alpha))
                                },
                                valueRange = 0f..1f,
                                trackWidth = 140.dp,
                                trackBrush = Brush.verticalGradient(listOf(Color.White, Color.Black))
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                painter = painterResource(id = R.drawable.opacity_icon),
                                contentDescription = "Opacity",
                                modifier = Modifier.size(20.dp)
                            )
                            AssetVerticalSlider(
                                value = alpha,
                                onValueChange = { 
                                    alpha = it 
                                    if (hasInteracted) onColorChanged(Color.hsv(hue, saturation, brightness, it))
                                },
                                valueRange = 0f..1f,
                                trackWidth = 140.dp,
                                trackBrush = Brush.verticalGradient(listOf(Color.White, Color.Transparent))
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // Comparison Window
                Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            .background(originalColor)
                            .clickable {
                                val hsvArr = FloatArray(3)
                                android.graphics.Color.colorToHSV(originalColor.toArgb(), hsvArr)
                                hue = hsvArr[0]; saturation = hsvArr[1]; brightness = hsvArr[2]
                                alpha = originalColor.alpha
                                hasInteracted = true
                                onColorChanged(originalColor)
                            }
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(if (!hasInteracted) Color.Gray else currentColor)
                    )
                }
            }
        }
    }
}

// ── Sidebar ───────────────────────────────────────────────────────────────────
@Composable
fun DrawingSidebar(
    pressure: Float,
    onPressureChange: (Float) -> Unit,
    selectedColor: Color,
    colors: List<Color>,
    onColorSelected: (Color) -> Unit,
    onUpdateColor: (Int, Color) -> Unit,
    onAddColor: (Color) -> Unit,
    onDeleteColor: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(60.dp).fillMaxHeight().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AssetVerticalSlider(
                value = pressure,
                onValueChange = { value ->
                    onPressureChange(value)
                    ToolManager.getInstance().getActivePen().setPressureSensitivity(value)
                }
            )
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            ColorPickerList(
                colors = colors, selectedColor = selectedColor,
                onColorSelected = onColorSelected, onUpdateColor = onUpdateColor,
                onAddColor = onAddColor, onDeleteColor = onDeleteColor
            )
        }
    }
}

// ── Full screen ───────────────────────────────────────────────────────────────
@Composable
fun DrawingScreen() {
    var selectedTool by remember { mutableStateOf(ToolManager.ToolType.PEN) }
    var pressure     by remember { mutableFloatStateOf(10f) }
    
    var selectedColor by remember { mutableStateOf(Color.Black) }
    var colors by remember { mutableStateOf(listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color.Green, Color.Yellow)) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1D1D1D))) {
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
                pressure = pressure, onPressureChange = { pressure = it },
                selectedColor = selectedColor, colors = colors,
                onColorSelected = { 
                    selectedColor = it
                    ToolManager.getInstance().getActivePen().setColor(it.toArgb())
                },
                onUpdateColor = { index, newColor ->
                    val newList = colors.toMutableList()
                    newList[index] = newColor
                    colors = newList
                    // If we're updating the currently active color, sync it with tool manager
                    if (selectedColor == colors[index]) {
                        selectedColor = newColor
                        ToolManager.getInstance().getActivePen().setColor(newColor.toArgb())
                    }
                },
                onAddColor = { newColor ->
                    colors = colors + newColor
                    selectedColor = newColor
                    ToolManager.getInstance().getActivePen().setColor(newColor.toArgb())
                },
                onDeleteColor = { index ->
                    val newList = colors.toMutableList()
                    newList.removeAt(index)
                    colors = newList
                }
            )
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE6E6E6))) {
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
