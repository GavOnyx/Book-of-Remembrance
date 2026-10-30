package com.calonyx.app.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Color picker dialog with a hand-drawn HSV wheel and an SV square.
 * Built with plain Compose Canvas — no new dependencies (the Material
 * icons artifact isn't available in this build's dependency set, same
 * reason the app's icons are hand-drawn).
 *
 * Used everywhere a custom color can be added (theme colors in Settings
 * and the event-type color editor): [onConfirm] receives the picked ARGB.
 */
@Composable
fun ColorWheelDialog(
    initialArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (argb: Int) -> Unit
) {
    val initial = remember(initialArgb) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialArgb, it) }
    }
    var hue by remember { mutableStateOf(initial[0]) } // 0..360
    var sat by remember { mutableStateOf(initial[1]) } // 0..1
    var value by remember { mutableStateOf(initial[2]) } // 0..1
    val selectedArgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    val selected = Color(selectedArgb)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose color") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                HueWheel(
                    hue = hue,
                    onHueChange = { hue = it },
                    modifier = Modifier
                        .size(216.dp)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(12.dp))
                SvSquare(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onSvChange = { s, v -> sat = s; value = v },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(selected)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    // Readout only — no typing needed.
                    Text(
                        "#%06X".format(selectedArgb and 0xFFFFFF),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedArgb) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Hue (0..360) from a pointer position relative to the canvas. */
private fun hueFor(pos: Offset, size: IntSize): Float {
    val dx = pos.x - size.width / 2f
    val dy = pos.y - size.height / 2f
    var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
    if (deg < 0) deg += 360.0
    return (deg % 360.0).toFloat()
}

/** (saturation, value) from a pointer position relative to the canvas. */
private fun svFor(pos: Offset, size: IntSize): Pair<Float, Float> =
    (pos.x / size.width).coerceIn(0f, 1f) to
        (1f - pos.y / size.height).coerceIn(0f, 1f)

/** Ring of hue wedges; tap or drag sets the hue. */
@Composable
private fun HueWheel(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Updated-state so recompositions during a drag don't restart the gesture.
    val currentOnHue by rememberUpdatedState(onHueChange)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                currentOnHue(hueFor(down.position, size))
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach {
                        it.consume()
                        currentOnHue(hueFor(it.position, size))
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        val radius = size.minDimension / 2f
        val stroke = radius * 0.38f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
        var d = 0
        while (d < 360) {
            drawArc(
                color = Color(android.graphics.Color.HSVToColor(floatArrayOf(d.toFloat(), 1f, 1f))),
                startAngle = d.toFloat() - 90f,
                sweepAngle = 3.2f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(stroke)
            )
            d += 3
        }
        // Marker at the current hue.
        val a = Math.toRadians((hue - 90).toDouble())
        val mr = radius - stroke / 2f
        val m = center + Offset((cos(a) * mr).toFloat(), (sin(a) * mr).toFloat())
        drawCircle(Color.White, radius = 11f, center = m)
        drawCircle(Color.Black, radius = 11f, center = m, style = Stroke(3f))
    }
}

/**
 * Saturation × value square for the current hue: saturation runs left→right,
 * value runs top→bottom. Tap or drag sets both.
 */
@Composable
private fun SvSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onSvChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnSv by rememberUpdatedState(onSvChange)
    val hueColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val (s, v) = svFor(down.position, size)
                currentOnSv(s, v)
                do {
                    val event = awaitPointerEvent()
                    event.changes.forEach {
                        it.consume()
                        val (s2, v2) = svFor(it.position, size)
                        currentOnSv(s2, v2)
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    ) {
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(
            (sat * size.width).coerceIn(0f, size.width),
            ((1f - value) * size.height).coerceIn(0f, size.height)
        )
        drawCircle(Color.White, radius = 12f, center = c)
        drawCircle(Color.Black, radius = 12f, center = c, style = Stroke(3f))
    }
}
