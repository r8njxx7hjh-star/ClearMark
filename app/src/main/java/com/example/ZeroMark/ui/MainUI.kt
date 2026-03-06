package com.example.ZeroMark.ui

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader as AndroidShader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
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
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 1f..100f,
    trackLength: Dp = 275.dp,
    trackBrush: Brush? = null,
    trackThickness: Dp = 6.dp,
    backgroundContent: (@Composable () -> Unit)? = null
) {
    val sliderContainerWidth = 40.dp
    Box(
        modifier = modifier.size(width = sliderContainerWidth, height = trackLength),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .requiredWidth(trackLength)
                .height(sliderContainerWidth)
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
                Box(
                    modifier = Modifier.fillMaxWidth().height(sliderContainerWidth),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(trackThickness)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                    ) {
                        backgroundContent?.invoke()
                        if (trackBrush != null) {
                            Box(modifier = Modifier.fillMaxSize().background(trackBrush))
                        } else if (backgroundContent == null) {
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE0F3FF)))
                        }
                    }
                    if (trackBrush == null && backgroundContent == null) {
                        val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                        val progressFraction = if (range != 0f) (sliderState.value - sliderState.valueRange.start) / range else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(trackThickness)
                                .clip(RoundedCornerShape(trackThickness / 2)),
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
            Image(
                painter = painterResource(if (selectedTool == ToolManager.ToolType.PEN) R.drawable.brush_active else R.drawable.brush_inactive),
                contentDescription = "Brush"
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = { onToolSelected(ToolManager.ToolType.ERASER) }, modifier = Modifier.size(50.dp)) {
            Image(
                painter = painterResource(if (selectedTool == ToolManager.ToolType.ERASER) R.drawable.eraser_active else R.drawable.eraser_inactive),
                contentDescription = "Eraser"
            )
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
    onReorderColors: (List<Color>) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPopup by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableIntStateOf(-1) }

    // ── Drag-to-reorder state ──────────────────────────────────────────────
    var itemHeightPx by remember { mutableFloatStateOf(0f) }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var visualDraggingIndex by remember { mutableIntStateOf(-1) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }
    var dragDeltaX by remember { mutableFloatStateOf(0f) }

    // Track which item is currently being pressed/held
    var pressedIndex by remember { mutableIntStateOf(-1) }

    // liveColors is the working reorder buffer; synced from parent when not dragging
    var liveColors by remember { mutableStateOf(colors) }
    LaunchedEffect(colors) {
        if (draggingIndex == -1) liveColors = colors
    }

    val itemSpacingPx = with(LocalDensity.current) { 12.dp.toPx() }
    val slotPx = itemHeightPx + itemSpacingPx
    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                userScrollEnabled = draggingIndex == -1
            ) {
                itemsIndexed(liveColors) { index, color ->
                    val isSelected = color == selectedColor
                    val isDragging = draggingIndex == index
                    val isPressed = pressedIndex == index

                    // ── Visual offset for neighbour items ──────────────────
                    val neighbourOffset: Float = if (visualDraggingIndex == -1 || isDragging) {
                        0f
                    } else {
                        val slots = (dragDeltaY / slotPx).roundToInt()
                            .coerceIn(-visualDraggingIndex, liveColors.lastIndex - visualDraggingIndex)
                        val dest = visualDraggingIndex + slots
                        when {
                            visualDraggingIndex < dest && index in (visualDraggingIndex + 1)..dest -> -slotPx
                            visualDraggingIndex > dest && index in dest until visualDraggingIndex  ->  slotPx
                            else -> 0f
                        }
                    }
                    val animatedNeighbourOffset by animateFloatAsState(
                        targetValue = neighbourOffset,
                        animationSpec = tween(140),
                        label = "neighbour$index"
                    )

                    val translationY = if (isDragging) dragDeltaY else animatedNeighbourOffset
                    val translationX = if (isDragging) dragDeltaX else 0f

                    val isDeleteSwipe = isDragging &&
                            abs(dragDeltaX) > 60f &&
                            abs(dragDeltaX) > abs(dragDeltaY)
                    val itemAlpha by animateFloatAsState(
                        targetValue = if (isDeleteSwipe) 0.35f else 1f,
                        animationSpec = tween(250),
                        label = "alpha$index"
                    )
                    
                    // Shrink (0.9f) when pressed, Expand (1.15f) when dragged/lifted
                    val itemScale by animateFloatAsState(
                        targetValue = when {
                            isDragging -> 1.15f
                            isPressed -> 0.9f
                            else -> 1f
                        },
                        animationSpec = tween(100),
                        label = "scale$index"
                    )

                    Box(
                        modifier = Modifier
                            .onSizeChanged { size ->
                                if (itemHeightPx == 0f) itemHeightPx = size.height.toFloat()
                            }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                this.translationX = translationX
                                this.translationY = translationY
                                this.scaleX = itemScale
                                this.scaleY = itemScale
                                this.alpha = itemAlpha
                            }
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                            .border(
                                width = 2.dp,
                                color = if (isSelected) Color(0xFF51A0D5) else Color.Gray,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .pointerInput(index, slotPx, selectedColor, color) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // Prevent dragging if the list is currently scrolling
                                    if (listState.isScrollInProgress) return@awaitEachGesture
                                    
                                    pressedIndex = index
                                    var dragStarted = false
                                    var cumulativeX = 0f
                                    var cumulativeY = 0f
                                    val startTimestamp = System.currentTimeMillis()
                                    val dragThresholdMs = 150L 

                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.find { it.id == down.id } ?: break
                                        
                                        if (!change.pressed) {
                                            // Finger lifted - trigger tap if we didn't start dragging
                                            if (!dragStarted) {
                                                if (isSelected) {
                                                    editingIndex = index
                                                    showPopup = true
                                                } else {
                                                    onColorSelected(color)
                                                }
                                            }
                                            break
                                        }

                                        val dx = change.positionChange().x
                                        val dy = change.positionChange().y
                                        cumulativeX += dx
                                        cumulativeY += dy

                                        if (!dragStarted) {
                                            val elapsed = System.currentTimeMillis() - startTimestamp
                                            // Start dragging if held long enough and moved past threshold
                                            if (elapsed >= dragThresholdMs) {
                                                if (abs(cumulativeX) > 8.dp.toPx() || abs(cumulativeY) > 8.dp.toPx()) {
                                                    dragStarted = true
                                                    draggingIndex = index
                                                    visualDraggingIndex = index
                                                    dragDeltaX = cumulativeX
                                                    dragDeltaY = cumulativeY
                                                    pressedIndex = -1 // Switch from shrink to lift (expand)
                                                    change.consume()
                                                }
                                            } else {
                                                // If moved too much too early, treat as potential scroll and cancel drag
                                                if (abs(cumulativeX) > 15.dp.toPx() || abs(cumulativeY) > 15.dp.toPx()) {
                                                    break
                                                }
                                            }
                                        } else {
                                            change.consume()
                                            dragDeltaX += dx
                                            dragDeltaY += dy
                                        }
                                    }

                                    if (dragStarted) {
                                        val horizontalSwipe =
                                            abs(dragDeltaX) > abs(dragDeltaY) &&
                                                    abs(dragDeltaX) > 100.dp.toPx()

                                        if (horizontalSwipe && liveColors.size > 1) {
                                            liveColors = liveColors.toMutableList().also {
                                                it.removeAt(draggingIndex)
                                            }
                                            onDeleteColor(draggingIndex)
                                        } else {
                                            val slots = (dragDeltaY / slotPx).roundToInt()
                                                .coerceIn(-visualDraggingIndex, liveColors.lastIndex - visualDraggingIndex)
                                            val dest = visualDraggingIndex + slots
                                            if (dest != visualDraggingIndex) {
                                                val mutable = liveColors.toMutableList()
                                                mutable.add(dest, mutable.removeAt(visualDraggingIndex))
                                                liveColors = mutable
                                            }
                                            onReorderColors(liveColors.toList())
                                        }

                                        draggingIndex = -1
                                        visualDraggingIndex = -1
                                        dragDeltaX = 0f
                                        dragDeltaY = 0f
                                    }
                                    pressedIndex = -1
                                }
                            }
                    )
                }
            }

            // ── Add button ────────────────────────────────────────────────────
            val addInteractionSource = remember { MutableInteractionSource() }
            val isAddPressed by addInteractionSource.collectIsPressedAsState()
            val addScale by animateFloatAsState(
                targetValue = if (isAddPressed) 0.85f else 1f,
                label = "addScale"
            )
            IconButton(
                onClick = { editingIndex = -1; showPopup = true },
                interactionSource = addInteractionSource,
                modifier = Modifier
                    .scale(addScale)
                    .size(40.dp)
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "Add Color",
                    tint = Color.White
                )
            }
        }

        if (showPopup) {
            val popupTargetIndex = editingIndex
            ColorPickerPopup(
                initialColor = if (popupTargetIndex >= 0) colors.getOrNull(popupTargetIndex) else null,
                onColorChanged = { newColor ->
                    if (editingIndex >= 0) {
                        onUpdateColor(editingIndex, newColor)
                        onColorSelected(newColor)
                    } else {
                        onAddColor(newColor)
                        editingIndex = colors.size
                        onColorSelected(newColor)
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
fun SvSquare(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onSvChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    fun updateSv(pos: Offset) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val s = (pos.x / canvasSize.width).coerceIn(0f, 1f)
            val v = (1f - pos.y / canvasSize.height).coerceIn(0f, 1f)
            onSvChange(s, v)
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it.toSize() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    updateSv(down.position)
                    drag(down.id) { change ->
                        change.consume()
                        updateSv(change.position)
                    }
                }
            }
    ) {
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val cx = saturation * size.width
        val cy = (1f - brightness) * size.height
        drawCircle(Color.White, radius = 6.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.Black, radius = 7.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val hueColors = listOf(
        Color.Red, Color.Yellow, Color.Green,
        Color.Cyan, Color.Blue, Color.Magenta, Color.Red
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            drawRoundRect(
                brush = Brush.horizontalGradient(hueColors),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
            )
        }
        Slider(
            value = hue,
            onValueChange = onHueChange,
            valueRange = 0f..360f,
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val horizontalPadding = 10.dp.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(maxWidth = constraints.maxWidth + horizontalPadding * 2)
                    )
                    layout(placeable.width - horizontalPadding * 2, placeable.height) {
                        placeable.place(-horizontalPadding, 0)
                    }
                },
            thumb = {
                Image(
                    painter = painterResource(id = R.drawable.slider_thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(31.dp).rotate(90f)
                )
            },
            track = { Box(modifier = Modifier.fillMaxWidth()) }
        )
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
    
    val initialHsv = remember {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV((initialColor ?: Color.Black).toArgb(), arr)
        arr
    }
    
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(initialColor?.alpha ?: 1f) }
    var hasInteracted by remember { mutableStateOf(initialColor != null) }

    val currentColor = Color.hsv(hue, saturation, brightness, alpha)

    LaunchedEffect(initialColor) {
        initialColor?.let {
            if (it.toArgb() != currentColor.toArgb()) {
                val hsvArr = FloatArray(3)
                android.graphics.Color.colorToHSV(it.toArgb(), hsvArr)
                hue = hsvArr[0]
                saturation = hsvArr[1]
                brightness = hsvArr[2]
                alpha = it.alpha
            }
        }
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(140, -40),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier = Modifier.width(350.dp).wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2A2A2A),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Color Picker", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SvSquare(
                            hue = hue,
                            saturation = saturation,
                            brightness = brightness,
                            onSvChange = { s, v ->
                                saturation = s; brightness = v
                                hasInteracted = true
                                onColorChanged(Color.hsv(hue, s, v, alpha))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        HueBar(
                            hue = hue,
                            onHueChange = { h ->
                                hue = h
                                hasInteracted = true
                                onColorChanged(Color.hsv(h, saturation, brightness, alpha))
                            },
                            modifier = Modifier.fillMaxWidth().height(20.dp)
                        )
                    }

                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AssetVerticalSlider(
                            value = alpha,
                            onValueChange = {
                                alpha = it
                                if (hasInteracted) onColorChanged(Color.hsv(hue, saturation, brightness, it))
                            },
                            valueRange = 0f..1f,
                            trackLength = 240.dp - 20.dp - 8.dp,
                            trackThickness = 20.dp,
                            backgroundContent = {
                                val image = ImageBitmap.imageResource(id = R.drawable.opacity_background)
                                val density = LocalDensity.current
                                val patternSize = 48.dp
                                val bgColor = Color.hsv(hue, saturation, brightness)

                                val shader = remember(image, patternSize) {
                                    val androidBitmap = image.asAndroidBitmap()
                                    val tilePx = with(density) { patternSize.toPx() }
                                    BitmapShader(
                                        androidBitmap,
                                        AndroidShader.TileMode.REPEAT,
                                        AndroidShader.TileMode.REPEAT
                                    ).also {
                                        val scale = tilePx / androidBitmap.width.toFloat()
                                        it.setLocalMatrix(Matrix().apply { setScale(scale, scale) })
                                    }
                                }

                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawIntoCanvas { canvas ->
                                        val paint = android.graphics.Paint().apply { this.shader = shader }
                                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
                                    }
                                    drawRect(
                                        brush = Brush.linearGradient(
                                            colors = listOf(
                                                bgColor.copy(alpha = 0f),
                                                bgColor.copy(alpha = 1f)
                                            ),
                                            start = Offset(0f, 0f),
                                            end = Offset(size.width, 0f)
                                        )
                                    )
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

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
                                if (hsvArr[2] > 0f) { hue = hsvArr[0]; saturation = hsvArr[1] }
                                brightness = hsvArr[2]
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
    onReorderColors: (List<Color>) -> Unit,
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
                colors = colors,
                selectedColor = selectedColor,
                onColorSelected = onColorSelected,
                onUpdateColor = onUpdateColor,
                onAddColor = onAddColor,
                onDeleteColor = onDeleteColor,
                onReorderColors = onReorderColors
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
    var colors by remember {
        mutableStateOf(listOf(Color.Black, Color.White, Color.Red, Color.Blue, Color.Green, Color.Yellow))
    }

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
                pressure = pressure,
                onPressureChange = { pressure = it },
                selectedColor = selectedColor,
                colors = colors,
                onColorSelected = {
                    selectedColor = it
                    ToolManager.getInstance().getActivePen().setColor(it.toArgb())
                },
                onUpdateColor = { index, newColor ->
                    val newList = colors.toMutableList()
                    newList[index] = newColor
                    colors = newList
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
                    val deletedColor = newList.removeAt(index)
                    colors = newList
                    // If deleted color was selected, pick nearest neighbour
                    if (deletedColor == selectedColor) {
                        val neighbour = newList.getOrNull(index) ?: newList.getOrNull(index - 1)
                        if (neighbour != null) {
                            selectedColor = neighbour
                            ToolManager.getInstance().getActivePen().setColor(neighbour.toArgb())
                        }
                    }
                },
                onReorderColors = { reordered ->
                    colors = reordered
                }
            )
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFE6E6E6))) {
                DrawingCanvas()
            }
        }
    }
}

// ── Embeds existing custom Views ──────────────────────────────────────────────
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
