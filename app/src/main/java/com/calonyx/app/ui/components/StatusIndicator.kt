package com.calonyx.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.EventStatus
import com.calonyx.app.ui.displayColor

/**
 * v0.21.4: event status indicator that differs in shape, not just color,
 * so the status stays readable for colorblind users: a green checkmark
 * for happened, a red X for didn't happen, and the gray circle for
 * unmarked. The journal view already draws its glyphs inside a colored
 * circle; this one draws the bare glyph at the small sizes the timeline
 * tiles and list rows use.
 */
@Composable
fun StatusIndicator(
    status: EventStatus,
    modifier: Modifier = Modifier,
    indicatorSize: Dp = 8.dp
) {
    when (status) {
        EventStatus.UNMARKED -> Box(
            modifier
                .size(indicatorSize)
                .clip(CircleShape)
                .background(status.displayColor())
        )
        EventStatus.HAPPENED_RECORDED, EventStatus.DID_NOT_HAPPEN -> {
            val color = status.displayColor()
            Canvas(modifier.size(indicatorSize)) {
                val w = size.width
                val h = size.height
                val stroke = w * 0.2f
                if (status == EventStatus.HAPPENED_RECORDED) {
                    drawPath(
                        path = Path().apply {
                            moveTo(w * 0.12f, h * 0.55f)
                            lineTo(w * 0.42f, h * 0.82f)
                            lineTo(w * 0.88f, h * 0.18f)
                        },
                        color = color,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                } else {
                    drawLine(
                        color = color,
                        start = Offset(w * 0.18f, h * 0.18f),
                        end = Offset(w * 0.82f, h * 0.82f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = color,
                        start = Offset(w * 0.82f, h * 0.18f),
                        end = Offset(w * 0.18f, h * 0.82f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
