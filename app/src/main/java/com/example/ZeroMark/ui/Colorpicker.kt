package com.example.zeromark.ui

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader as AndroidShader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.zeromark.R

// ── HSV SV Square ─────────────────────────────────────────────────────────────
@Composable
fun SvSquare(
    hue: Float, saturation: Float, brightness: Float,
    onSvChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    fun updateSv(pos: Offset) {
        if (canvasSize.width > 0 && canvasSize.height > 0)
            onSvChange(
                (pos.x / canvasSize.width).coerceIn(0f, 1f),
                (1f - pos.y / canvasSize.height).coerceIn(0f, 1f)
            )
    }
    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it.toSize() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    updateSv(down.position)
                    drag(down.id) { ch -> ch.consume(); updateSv(ch.position) }
                }
            }
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val cx = saturation * size.width
        val cy = (1f - brightness) * size.height
        drawCircle(Color.White, 6.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black, 7.dp.toPx(), Offset(cx, cy), style = Stroke(1.dp.toPx()))
    }
}

// ── Hue Bar ───────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HueBar(hue: Float, onHueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val hueColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            drawRoundRect(Brush.horizontalGradient(hueColors), cornerRadius = CornerRadius(4.dp.toPx()))
        }
        Slider(
            value         = hue,
            onValueChange = onHueChange,
            valueRange    = 0f..360f,
            modifier      = Modifier.fillMaxWidth().layout { measurable, constraints ->
                val hp = 10.dp.roundToPx()
                val p  = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + hp * 2))
                layout(p.width - hp * 2, p.height) { p.place(-hp, 0) }
            },
            thumb = {
                Image(
                    painterResource(R.drawable.slider_thumb), null,
                    contentScale = ContentScale.Fit,
                    modifier     = Modifier.size(31.dp).rotate(90f)
                )
            },
            track = { Box(Modifier.fillMaxWidth()) }
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
    var hue           by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation    by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness    by remember { mutableFloatStateOf(initialHsv[2]) }
    var hasInteracted by remember { mutableStateOf(initialColor != null) }
    val alpha = 1f
    val currentColor  = Color.hsv(hue, saturation, brightness, alpha)

    LaunchedEffect(initialColor) {
        initialColor?.let {
            if (it.toArgb() != currentColor.toArgb()) {
                val arr = FloatArray(3)
                android.graphics.Color.colorToHSV(it.toArgb(), arr)
                hue = arr[0]; saturation = arr[1]; brightness = arr[2]
            }
        }
    }

    Popup(
        alignment        = Alignment.TopStart,
        offset           = IntOffset(140, -40),
        onDismissRequest = onDismissRequest,
        properties       = PopupProperties(focusable = true)
    ) {
        Surface(
            modifier       = Modifier.width(350.dp).wrapContentHeight(),
            shape          = RoundedCornerShape(12.dp),
            color          = Color(0xFF2A2A2A),
            tonalElevation = 8.dp
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Color Picker", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().height(240.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SvSquare(
                            hue, saturation, brightness,
                            onSvChange = { s, v ->
                                saturation = s; brightness = v; hasInteracted = true
                                onColorChanged(Color.hsv(hue, s, v, alpha))
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(6.dp))
                        )
                        HueBar(
                            hue,
                            { h -> hue = h; hasInteracted = true; onColorChanged(Color.hsv(h, saturation, brightness, alpha)) },
                            Modifier.fillMaxWidth().height(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    Box(
                        Modifier
                            .weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                            .background(originalColor)
                            .clickable {
                                val arr = FloatArray(3)
                                android.graphics.Color.colorToHSV(originalColor.toArgb(), arr)
                                if (arr[2] > 0f) { hue = arr[0]; saturation = arr[1] }
                                brightness = arr[2]
                                hasInteracted = true; onColorChanged(originalColor)
                            }
                    )
                    Box(
                        Modifier
                            .weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
                            .background(if (!hasInteracted) Color.Gray else currentColor)
                    )
                }
            }
        }
    }
}