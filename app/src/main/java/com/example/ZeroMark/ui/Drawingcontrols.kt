package com.example.ZeroMark.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ZeroMark.Brushes.ToolManager
import com.example.ZeroMark.R

// ── Top Toolbar ───────────────────────────────────────────────────────────────
@Composable
fun DrawingToolbar(
    selectedTool: ToolManager.ToolType,
    onToolSelected: (ToolManager.ToolType) -> Unit,
    selectedColor: Color,
    selectedColorIndex: Int,
    colors: List<Color>,
    onColorSelected: (index: Int) -> Unit,
    onUpdateColor: (Int, Color) -> Unit,
    onAddColor: (Color) -> Unit,
    onDeleteColor: (Int) -> Unit,
    onReorderColors: (List<Color>) -> Unit,
    currentOpacity: Float = 1f,
    onOpacityChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showColorGrid   by remember { mutableStateOf(false) }
    var toolbarBottomPx by remember { mutableIntStateOf(0) }
    val density          = LocalDensity.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(ColorBackground)
            .padding(horizontal = 16.dp)
            .onGloballyPositioned { coords ->
                toolbarBottomPx = with(density) {
                    (coords.positionInWindow().y + coords.size.height).toInt()
                }
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                painter            = painterResource(id = R.drawable.house_icon),
                contentDescription = "Home",
                tint               = Color.White,
                modifier           = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text       = stringResource(R.string.canvas_title),
                color      = Color.White,
                fontSize   = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(
                iconActive   = R.drawable.eraser_active,
                iconInactive = R.drawable.eraser_inactive,
                isActive     = selectedTool == ToolManager.ToolType.ERASER,
                iconSize     = 50.dp,
                onClick      = { onToolSelected(ToolManager.ToolType.ERASER) },
                contentDescription = "Eraser"
            )
            Spacer(Modifier.width(12.dp))
            ToolButton(
                iconActive   = R.drawable.brush_active,
                iconInactive = R.drawable.brush_inactive,
                isActive     = selectedTool == ToolManager.ToolType.PEN,
                iconSize     = 40.dp,
                onClick      = { onToolSelected(ToolManager.ToolType.PEN) },
                contentDescription = "Brush"
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(selectedColor)
                    .border(2.dp, Color(0xFF5F5F5F), CircleShape)
                    .clickable { showColorGrid = !showColorGrid }
            )
        }

        if (showColorGrid) {
            ColorGridPopup(
                colors             = colors,
                selectedColorIndex = selectedColorIndex,
                onColorSelected    = onColorSelected,
                onUpdateColor      = onUpdateColor,
                onAddColor         = onAddColor,
                onDeleteColor      = onDeleteColor,
                onReorderColors    = onReorderColors,
                onDismiss          = { showColorGrid = false },
                popupOffset        = IntOffset(x = -16, y = toolbarBottomPx),
                currentOpacity     = currentOpacity,
                onOpacityChange    = onOpacityChange
            )
        }
    }
}

// ── Reusable tool button ──────────────────────────────────────────────────────
@Composable
private fun ToolButton(
    iconActive: Int,
    iconInactive: Int,
    isActive: Boolean,
    iconSize: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ColorToolbarButton)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter            = painterResource(if (isActive) iconActive else iconInactive),
            contentDescription = contentDescription,
            modifier           = Modifier.size(iconSize)
        )
    }
}

// ── Sidebar ───────────────────────────────────────────────────────────────────
@Composable
fun DrawingSidebar(
    brushSize: Float,
    onBrushSizeChange: (Float) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier         = modifier.fillMaxHeight().padding(16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(ColorSidebar)
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = stringResource(R.string.brush_size_label),
                    color      = ColorLabelText,
                    fontSize   = 8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 2,
                    lineHeight = 10.sp
                )
                AssetVerticalSlider(
                    value          = brushSize,
                    onValueChange  = onBrushSizeChange,
                    valueRange     = 1f..100f,
                    trackLength    = 280.dp,
                    trackThickness = 8.dp
                )
            }

            Box(Modifier.width(32.dp).height(1.dp).background(ColorDivider))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = stringResource(R.string.opacity_label),
                    color      = ColorLabelText,
                    fontSize   = 8.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines   = 1
                )
                AssetVerticalSlider(
                    value          = opacity,
                    onValueChange  = onOpacityChange,
                    valueRange     = 0f..100f,
                    trackLength    = 280.dp,
                    trackThickness = 8.dp
                )
            }
        }
    }
}