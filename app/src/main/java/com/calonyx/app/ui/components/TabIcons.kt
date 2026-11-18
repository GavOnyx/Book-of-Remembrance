package com.calonyx.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Minimal bottom-nav icons drawn with Canvas. The Material icons artifact
 * is not available in this build's dependency set, so these hand-drawn
 * glyphs keep the tab bar from depending on it.
 */
@Composable
fun HomeTabIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.92f, h * 0.48f)
            lineTo(w * 0.80f, h * 0.48f)
            lineTo(w * 0.80f, h * 0.88f)
            lineTo(w * 0.20f, h * 0.88f)
            lineTo(w * 0.20f, h * 0.48f)
            lineTo(w * 0.08f, h * 0.48f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = w * 0.09f))
    }
}

@Composable
fun CalendarTabIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        // Body
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.20f),
            size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.68f),
            style = Stroke(width = w * 0.09f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
        )
        // Top binder line
        drawLine(
            color = tint,
            start = Offset(w * 0.12f, h * 0.38f),
            end = Offset(w * 0.88f, h * 0.38f),
            strokeWidth = w * 0.09f
        )
        // Binder rings
        drawLine(tint, Offset(w * 0.32f, h * 0.08f), Offset(w * 0.32f, h * 0.28f), w * 0.09f)
        drawLine(tint, Offset(w * 0.68f, h * 0.08f), Offset(w * 0.68f, h * 0.28f), w * 0.09f)
    }
}

@Composable
fun ContactsTabIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        // Head
        drawCircle(
            color = tint,
            radius = w * 0.20f,
            center = Offset(w * 0.5f, h * 0.34f),
            style = Stroke(width = w * 0.09f)
        )
        // Shoulders
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.52f),
            size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.44f),
            style = Stroke(width = w * 0.09f)
        )
    }
}

/**
 * Journal glyph (milestone 20): a sheet of paper with two text lines and
 * a pencil across its bottom-right corner. Hand-drawn like the other
 * icons. Used for the calendar top-bar toggle that switches the day view
 * to journal mode; the calendar glyph ([CalendarTabIcon]) is shown while
 * already in journal mode, since the button always shows the destination.
 *
 * v0.20.5: the pencil redrawn — the original was a thin diagonal line
 * plus a skewed tip triangle whose base sat off the body line, so it
 * read as a bent checkmark. The new pencil has a real body width, a
 * symmetric tip whose base is perpendicular to and centered on the
 * body line with its apex continuing the body direction, and an
 * eraser cap at the lower end.
 * v0.20.6: the pencil shifted up so it sits fully on the page — its
 * tip inside the sheet and its lower end clear of the sheet's bottom
 * edge, instead of hanging low and overlapping it.
 * v0.20.7: the pencil moved up and to the right again — the v0.20.6
 * position read as the bottom-left corner. It now sits upper-right
 * on the sheet, lower end still clear of the bottom edge.
 * v0.21.1: the pencil rotated 180 degrees in place (the eraser was
 * pointing bottom-left, reading as erasing; now the tip is at the
 * lower end and the eraser at the upper end), then elongated with
 * the tip as the fixed origin, so the eraser sits beyond the
 * sheet's right edge.
 */
@Composable
fun JournalIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // Sheet
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.10f),
            size = androidx.compose.ui.geometry.Size(w * 0.54f, h * 0.80f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
        // Text lines
        drawLine(
            color = tint,
            start = Offset(w * 0.28f, h * 0.30f),
            end = Offset(w * 0.60f, h * 0.30f),
            strokeWidth = stroke * 0.8f
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.28f, h * 0.46f),
            end = Offset(w * 0.60f, h * 0.46f),
            strokeWidth = stroke * 0.8f
        )
        // Pencil, diagonal across the sheet. Body axis runs up-right
        // at 45 degrees; d is its unit direction, p the perpendicular.
        // The tip's base is centered on the body end and perpendicular
        // to the body, so the tip reads as a pencil point instead of
        // a bent checkmark. v0.21.1: rotated 180 degrees in place
        // (tip now at the lower end, eraser at the upper end), then
        // elongated from the tip as the fixed origin so the eraser
        // clears the sheet's right edge.
        val dx = 0.7071f
        val dy = -0.7071f
        val px = 0.7071f
        val py = 0.7071f
        val bodyStartX = 0.44f
        val bodyStartY = 0.70f
        // Elongation along d from the tip end (bodyStart), applied
        // after the rotation: only the eraser end moves.
        val elong = 0.12f
        val bodyEndX = 0.70f + dx * elong
        val bodyEndY = 0.44f + dy * elong
        val halfWidth = 0.055f
        // Thick body
        drawLine(
            color = tint,
            start = Offset(w * bodyStartX, h * bodyStartY),
            end = Offset(w * bodyEndX, h * bodyEndY),
            strokeWidth = w * halfWidth * 2f,
            cap = StrokeCap.Butt
        )
        // Symmetric tip at the lower end, apex continuing away from
        // the body (opposite the eraser direction)
        val tip = Path().apply {
            moveTo(w * (bodyStartX + px * halfWidth), h * (bodyStartY + py * halfWidth))
            lineTo(w * (bodyStartX - dx * 0.16f), h * (bodyStartY - dy * 0.16f))
            lineTo(w * (bodyStartX - px * halfWidth), h * (bodyStartY - py * halfWidth))
            close()
        }
        drawPath(tip, color = tint)
        // Eraser cap at the upper end, slightly wider than the body
        // with a hairline gap so it reads as a separate cap
        val eraserHalfWidth = 0.065f
        val eraserCenterX = bodyEndX + dx * 0.07f
        val eraserCenterY = bodyEndY + dy * 0.07f
        val eraserHalfLen = 0.04f
        val eraser = Path().apply {
            moveTo(
                w * (eraserCenterX + px * eraserHalfWidth + dx * eraserHalfLen),
                h * (eraserCenterY + py * eraserHalfWidth + dy * eraserHalfLen)
            )
            lineTo(
                w * (eraserCenterX + px * eraserHalfWidth - dx * eraserHalfLen),
                h * (eraserCenterY + py * eraserHalfWidth - dy * eraserHalfLen)
            )
            lineTo(
                w * (eraserCenterX - px * eraserHalfWidth - dx * eraserHalfLen),
                h * (eraserCenterY - py * eraserHalfWidth - dy * eraserHalfLen)
            )
            lineTo(
                w * (eraserCenterX - px * eraserHalfWidth + dx * eraserHalfLen),
                h * (eraserCenterY - py * eraserHalfWidth + dy * eraserHalfLen)
            )
            close()
        }
        drawPath(eraser, color = tint)
    }
}

@Composable
fun PlusIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawLine(tint, Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f), w * 0.12f)
        drawLine(tint, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), w * 0.12f)
    }
}

@Composable
fun GearIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val center = Offset(w * 0.5f, w * 0.5f)
        val rOuter = w * 0.30f
        val rInner = w * 0.14f
        // Teeth
        for (i in 0 until 8) {
            val a = (i * 45f) * Math.PI / 180.0
            val dir = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
            drawLine(
                color = tint,
                start = center + dir * rOuter,
                end = center + dir * (rOuter + w * 0.09f),
                strokeWidth = w * 0.10f
            )
        }
        drawCircle(tint, rOuter, center, style = Stroke(width = w * 0.10f))
        drawCircle(tint, rInner, center, style = Stroke(width = w * 0.10f))
    }
}

@Composable
fun BackIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawLine(
            color = tint,
            start = Offset(w * 0.62f, h * 0.22f),
            end = Offset(w * 0.36f, h * 0.5f),
            strokeWidth = w * 0.11f
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.36f, h * 0.5f),
            end = Offset(w * 0.62f, h * 0.78f),
            strokeWidth = w * 0.11f
        )
    }
}

/**
 * Copy-to-clipboard glyph: two overlapping rounded rectangles. Hand-drawn
 * like the other icons (the Material icons artifact is not a dependency).
 */
@Composable
fun CopyIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // Back sheet (top-right)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.38f, h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.50f, h * 0.58f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
        // Front sheet (bottom-left), drawn over the back one
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.12f, h * 0.30f),
            size = androidx.compose.ui.geometry.Size(w * 0.50f, h * 0.58f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
    }
}

/**
 * Phone handset glyph (smartphone): rounded body with a speaker slot.
 * Hand-drawn like the other icons.
 */
@Composable
fun PhoneIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.30f, h * 0.08f),
            size = androidx.compose.ui.geometry.Size(w * 0.40f, h * 0.84f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
        )
        // Speaker slot near the top
        drawLine(
            color = tint,
            start = Offset(w * 0.42f, h * 0.20f),
            end = Offset(w * 0.58f, h * 0.20f),
            strokeWidth = stroke
        )
    }
}

/**
 * SMS glyph: speech bubble with two text lines and a tail.
 * Hand-drawn like the other icons.
 */
@Composable
fun SmsIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.14f),
            size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.56f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f)
        )
        // Tail
        val tail = Path().apply {
            moveTo(w * 0.30f, h * 0.70f)
            lineTo(w * 0.22f, h * 0.90f)
            lineTo(w * 0.44f, h * 0.70f)
            close()
        }
        drawPath(tail, color = tint, style = Stroke(width = stroke))
        // Text lines
        drawLine(
            color = tint,
            start = Offset(w * 0.24f, h * 0.34f),
            end = Offset(w * 0.76f, h * 0.34f),
            strokeWidth = stroke * 0.8f
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.24f, h * 0.50f),
            end = Offset(w * 0.62f, h * 0.50f),
            strokeWidth = stroke * 0.8f
        )
    }
}

/**
 * Email glyph: envelope with a flap. Hand-drawn like the other icons.
 */
@Composable
fun EmailIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.22f),
            size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.56f),
            style = Stroke(width = stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f)
        )
        // Flap
        val flap = Path().apply {
            moveTo(w * 0.12f, h * 0.26f)
            lineTo(w * 0.50f, h * 0.56f)
            lineTo(w * 0.88f, h * 0.26f)
        }
        drawPath(flap, color = tint, style = Stroke(width = stroke))
    }
}
