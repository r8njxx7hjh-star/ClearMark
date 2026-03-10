package com.example.ZeroMark.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.example.ZeroMark.R
import kotlin.math.*

private const val COLUMNS  = 3
private const val MAX_ROWS = 5
private val SWATCH: Dp = 40.dp
private val GAP: Dp    = 4.dp

@Composable
fun ColorGridPopup(
    colors: List<Color>,
    selectedColorIndex: Int,
    onColorSelected: (index: Int) -> Unit,
    onUpdateColor: (Int, Color) -> Unit,
    onAddColor: (Color) -> Unit,
    onDeleteColor: (Int) -> Unit,
    onReorderColors: (List<Color>) -> Unit,
    onDismiss: () -> Unit,
    popupOffset: IntOffset = IntOffset.Zero,
    currentOpacity: Float = 1f,
    onOpacityChange: (Float) -> Unit = {}
) {
    val density  = LocalDensity.current
    val swatchPx = with(density) { SWATCH.toPx() }
    val gapPx    = with(density) { GAP.toPx() }
    val cellPx   = swatchPx + gapPx

    var showColorPicker by remember { mutableStateOf(false) }
    var editingIndex    by remember { mutableIntStateOf(-1) }
    var liveColors      by remember { mutableStateOf(colors) }
    var liveSelected    by remember { mutableIntStateOf(selectedColorIndex) }

    LaunchedEffect(colors, selectedColorIndex) {
        liveColors   = colors
        liveSelected = selectedColorIndex
    }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX   by remember { mutableFloatStateOf(0f) }
    var dragOffsetY   by remember { mutableFloatStateOf(0f) }
    var pressedIndex  by remember { mutableIntStateOf(-1) }

    fun resolveTarget(fromIndex: Int, dx: Float, dy: Float): Int {
        val fromRow = fromIndex / COLUMNS
        val fromCol = fromIndex % COLUMNS
        val toCol   = (fromCol + (dx / cellPx).roundToInt()).coerceIn(0, COLUMNS - 1)
        val toRow   = (fromRow + (dy / cellPx).roundToInt()).coerceIn(0, maxOf(0, (liveColors.size - 1) / COLUMNS))
        return (toRow * COLUMNS + toCol).coerceIn(0, maxOf(0, liveColors.lastIndex))
    }

    val scrollState = rememberScrollState()
    val rows        = if (liveColors.isEmpty()) 0 else ceil(liveColors.size.toFloat() / COLUMNS).toInt()
    val visibleRows = rows.coerceAtMost(MAX_ROWS)
    val gridHeight  = ((SWATCH + GAP) * visibleRows) + GAP + SWATCH + 4.dp + 12.dp

    Popup(
        alignment        = Alignment.TopEnd,
        offset           = popupOffset,
        onDismissRequest = onDismiss,
        properties       = PopupProperties(focusable = !showColorPicker)
    ) {
        Surface(
            shape           = RoundedCornerShape(16.dp),
            color           = Color(0xFF232323),
            tonalElevation  = 12.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier            = Modifier
                    .padding(12.dp)
                    .width((SWATCH * COLUMNS) + (GAP * (COLUMNS - 1)) + 24.dp)
                    .height(gridHeight),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState, enabled = draggingIndex == -1)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        repeat(rows) { row ->
                            Row(
                                horizontalArrangement = Arrangement.SpaceAround,
                                modifier              = Modifier.fillMaxWidth().padding(bottom = GAP)
                            ) {
                                repeat(COLUMNS) { col ->
                                    val index = row * COLUMNS + col
                                    if (index >= liveColors.size) {
                                        Spacer(Modifier.size(SWATCH))
                                    } else {
                                        ColorSwatch(
                                            index         = index,
                                            color         = liveColors[index],
                                            isSelected    = index == liveSelected,
                                            isDragging    = draggingIndex == index,
                                            isPressed     = pressedIndex == index,
                                            draggingIndex = draggingIndex,
                                            dragOffsetX   = dragOffsetX,
                                            dragOffsetY   = dragOffsetY,
                                            swatchPx      = swatchPx,
                                            cellPx        = cellPx,
                                            liveColorsSize = liveColors.size,
                                            resolveTarget  = ::resolveTarget,
                                            onTap = { tappedIndex ->
                                                if (tappedIndex == liveSelected) {
                                                    editingIndex    = tappedIndex
                                                    showColorPicker = true
                                                } else {
                                                    liveSelected = tappedIndex
                                                    onColorSelected(tappedIndex)
                                                }
                                            },
                                            onDragStarted = { idx, ox, oy ->
                                                draggingIndex = idx
                                                dragOffsetX   = ox
                                                dragOffsetY   = oy
                                                pressedIndex  = -1
                                            },
                                            onDragUpdated = { dx, dy ->
                                                dragOffsetX += dx
                                                dragOffsetY += dy
                                            },
                                            onDragEnded = { fromIdx ->
                                                val horizSwipe = abs(dragOffsetX) > abs(dragOffsetY) &&
                                                        abs(dragOffsetX) > swatchPx * 1.5f
                                                if (horizSwipe && liveColors.size > 1) {
                                                    val newList = liveColors.toMutableList().also { it.removeAt(fromIdx) }
                                                    liveColors   = newList
                                                    liveSelected = when {
                                                        liveSelected == fromIdx -> fromIdx.coerceAtMost(newList.lastIndex).coerceAtLeast(0)
                                                        liveSelected > fromIdx  -> liveSelected - 1
                                                        else                    -> liveSelected
                                                    }
                                                    onDeleteColor(fromIdx)
                                                } else {
                                                    val dest = resolveTarget(fromIdx, dragOffsetX, dragOffsetY)
                                                    if (dest != fromIdx) {
                                                        val m = liveColors.toMutableList()
                                                        m.add(dest, m.removeAt(fromIdx))
                                                        liveColors   = m
                                                        liveSelected = when {
                                                            liveSelected == fromIdx                                  -> dest
                                                            fromIdx < dest && liveSelected in (fromIdx + 1)..dest   -> liveSelected - 1
                                                            fromIdx > dest && liveSelected in dest until fromIdx    -> liveSelected + 1
                                                            else                                                     -> liveSelected
                                                        }
                                                    }
                                                    onReorderColors(liveColors.toList())
                                                }
                                                draggingIndex = -1
                                                dragOffsetX   = 0f
                                                dragOffsetY   = 0f
                                            },
                                            onPressChanged = { idx -> pressedIndex = idx }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                AddColorButton(onClick = { editingIndex = -1; showColorPicker = true })
            }
        }
    }

    if (showColorPicker) {
        ColorPickerPopup(
            initialColor     = if (editingIndex >= 0) liveColors.getOrNull(editingIndex) else null,
            onColorChanged   = { newColor ->
                if (editingIndex >= 0) {
                    onUpdateColor(editingIndex, newColor)
                    liveSelected = editingIndex
                    onColorSelected(editingIndex)
                } else {
                    onAddColor(newColor)
                    val newIdx   = liveColors.size
                    editingIndex = newIdx
                    liveSelected = newIdx
                    onColorSelected(newIdx)
                }
            },
            onDismissRequest = { showColorPicker = false; editingIndex = -1 },
            externalAlpha    = currentOpacity,
            onAlphaChange    = onOpacityChange
        )
    }
}

// ── Individual swatch ─────────────────────────────────────────────────────────
@Composable
private fun ColorSwatch(
    index: Int,
    color: Color,
    isSelected: Boolean,
    isDragging: Boolean,
    isPressed: Boolean,
    draggingIndex: Int,
    dragOffsetX: Float,
    dragOffsetY: Float,
    swatchPx: Float,
    cellPx: Float,
    liveColorsSize: Int,
    resolveTarget: (Int, Float, Float) -> Int,
    onTap: (Int) -> Unit,
    onDragStarted: (index: Int, offsetX: Float, offsetY: Float) -> Unit,
    onDragUpdated: (dx: Float, dy: Float) -> Unit,
    onDragEnded: (fromIndex: Int) -> Unit,
    onPressChanged: (index: Int) -> Unit
) {
    val target = if (draggingIndex != -1 && !isDragging)
        resolveTarget(draggingIndex, dragOffsetX, dragOffsetY) else -1

    val (nbShiftX, nbShiftY) = run {
        if (target == -1 || isDragging || draggingIndex == -1) return@run 0f to 0f
        val src     = draggingIndex; val dst = target
        val inRange = if (src < dst) index in (src + 1)..dst else index in dst until src
        if (!inRange) return@run 0f to 0f
        val dir     = if (src < dst) -1f else 1f
        val sameRow = (src / COLUMNS) == (dst / COLUMNS)
        if (sameRow) (dir * cellPx) to 0f else 0f to (dir * cellPx)
    }
    val animNbX by animateFloatAsState(nbShiftX, tween(140), label = "nbX$index")
    val animNbY by animateFloatAsState(nbShiftY, tween(140), label = "nbY$index")

    val txFinal       = if (isDragging) dragOffsetX else animNbX
    val tyFinal       = if (isDragging) dragOffsetY else animNbY
    val isDeleteSwipe = isDragging && abs(dragOffsetX) > swatchPx * 1.5f && abs(dragOffsetX) > abs(dragOffsetY)

    val itemAlpha by animateFloatAsState(if (isDeleteSwipe) 0.3f else 1f, tween(200), label = "a$index")
    val itemScale by animateFloatAsState(
        when { isDragging -> 1.18f; isPressed -> 0.88f; else -> 1f },
        tween(100), label = "s$index"
    )

    Box(
        modifier = Modifier
            .size(SWATCH)
            .zIndex(if (isDragging) 10f else 0f)
            .graphicsLayer {
                translationX = txFinal; translationY = tyFinal
                scaleX       = itemScale; scaleY = itemScale
                alpha        = itemAlpha
            }
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .border(
                width = if (isSelected) 2.5.dp else 1.5.dp,
                color = if (isSelected) ColorSliderFill else Color(0xFF3A3A3A),
                shape = RoundedCornerShape(8.dp)
            )
            .pointerInput(index) {
                awaitEachGesture {
                    val down    = awaitFirstDown(requireUnconsumed = false)
                    onPressChanged(index)
                    var dragStarted = false
                    var cumX        = 0f
                    var cumY        = 0f
                    val t0          = System.currentTimeMillis()

                    while (true) {
                        val event  = awaitPointerEvent()
                        val change = event.changes.find { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (!dragStarted) onTap(index)
                            break
                        }

                        val dx = change.positionChange().x
                        val dy = change.positionChange().y
                        cumX += dx; cumY += dy

                        if (!dragStarted) {
                            val elapsed = System.currentTimeMillis() - t0
                            if (elapsed >= 150L && (abs(cumX) > 8.dp.toPx() || abs(cumY) > 8.dp.toPx())) {
                                dragStarted = true
                                onDragStarted(index, cumX, cumY)
                                change.consume()
                            } else if (elapsed < 150L && (abs(cumX) > 15.dp.toPx() || abs(cumY) > 15.dp.toPx())) {
                                break
                            }
                        } else {
                            change.consume()
                            onDragUpdated(dx, dy)
                        }
                    }

                    if (dragStarted) onDragEnded(index)
                    onPressChanged(-1)
                }
            }
    )
}

// ── Add color button ──────────────────────────────────────────────────────────
@Composable
private fun AddColorButton(onClick: () -> Unit) {
    val source    = remember { MutableInteractionSource() }
    val isPressed by source.collectIsPressedAsState()
    val scale     by animateFloatAsState(if (isPressed) 0.85f else 1f, label = "addScale")

    IconButton(
        onClick           = onClick,
        interactionSource = source,
        modifier          = Modifier
            .scale(scale)
            .size(SWATCH)
            .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
    ) {
        Icon(
            painter            = painterResource(id = R.drawable.ic_add),
            contentDescription = "Add Color",
            tint               = Color.White,
            modifier           = Modifier.size(32.dp)
        )
    }
}