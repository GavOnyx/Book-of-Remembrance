package com.calonyx.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.StatsMode
import com.calonyx.app.ui.components.EventListItem
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Home tab: date header, the month fill-% dashboard, and unmarked past events.
 * Tapping a month-grid day jumps the Calendar tab to that date.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEditEvent: (Long) -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val events by viewModel.unmarkedEvents.collectAsState()
    val month by viewModel.visibleMonth.collectAsState()
    val fills by viewModel.monthFill.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val contactStats by viewModel.contactStats.collectAsState()
    val statsMode = StatsMode.fromName(settings.statsMode)
    val fillByDate = remember(fills) { fills.associateBy { it.date } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        item {
            Text(
                text = LocalDate.now().format(
                    DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
                ),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )
        }
        item {
            MonthFillGrid(
                month = month,
                fillByDate = fillByDate,
                settings = settings,
                onSelectDate = onSelectDate,
                onShiftMonth = viewModel::shiftMonth,
                onCurrentMonth = viewModel::goToCurrentMonth,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        item {
            StatsSection(
                stats = stats,
                contactStats = contactStats,
                mode = statsMode,
                onModeChange = viewModel::setStatsMode,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        item {
            Text(
                text = "Unmarked events (${events.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (events.isEmpty()) {
            item {
                Text(
                    text = "All caught up.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        } else {
            items(events, key = { it.event.id }) { display ->
                EventListItem(display = display, onClick = { onEditEvent(display.event.id) })
            }
        }
    }
}

/** Month grid where each day cell visualizes its fill-%. */
@Composable
private fun MonthFillGrid(
    month: YearMonth,
    fillByDate: Map<LocalDate, DayFill>,
    settings: AppSettings,
    onSelectDate: (LocalDate) -> Unit,
    onShiftMonth: (Long) -> Unit,
    onCurrentMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    val weekFields = remember { WeekFields.of(Locale.getDefault()) }
    val firstDayOfWeek = weekFields.firstDayOfWeek
    val weekdayLabels = remember(firstDayOfWeek) {
        (0..6).map { firstDayOfWeek.plus(it.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    }
    val leadingBlanks = remember(month, firstDayOfWeek) {
        val first = month.atDay(1).dayOfWeek
        ((first.value - firstDayOfWeek.value + 7) % 7)
    }
    val daysInMonth = month.lengthOfMonth()
    val totalCells = leadingBlanks + daysInMonth
    val rowCount = (totalCells + 6) / 7
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }

    // Horizontal swipes change months (arrows still work); vertical drags
    // scroll the Home list and taps still select days, since only
    // horizontal movement past touch slop is consumed.
    Column(
        modifier = modifier.pointerInput(Unit) {
            var totalDx = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDx = 0f },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    totalDx += dragAmount
                },
                onDragEnd = {
                    when {
                        totalDx <= -swipeThresholdPx -> onShiftMonth(1L)
                        totalDx >= swipeThresholdPx -> onShiftMonth(-1L)
                    }
                },
                onDragCancel = { totalDx = 0f }
            )
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { onShiftMonth(-1) }) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onShiftMonth(1) }) {
                Text("›", style = MaterialTheme.typography.headlineMedium)
            }
            TextButton(onClick = onCurrentMonth) { Text("This month") }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                )
            }
        }
        repeat(rowCount) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayOfMonth = cellIndex - leadingBlanks + 1
                    if (dayOfMonth in 1..daysInMonth) {
                        val date = month.atDay(dayOfMonth)
                        val fill = fillByDate[date]
                        DayCell(
                            date = date,
                            fillFraction = fill?.fillFraction ?: 0f,
                            isToday = date == today,
                            onClick = { onSelectDate(date) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
        Text(
            text = "Fill window ${formatHour(settings.fillWindowStartHour)} – ${formatHour(settings.fillWindowEndHour)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    fillFraction: Float,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pct = (fillFraction * 100).toInt()
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isToday) Modifier.border(
                    1.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(8.dp)
                ) else Modifier
            )
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$pct%",
                style = MaterialTheme.typography.labelSmall,
                color = if (pct >= 100) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(
                progress = { fillFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = when {
                    pct >= 100 -> MaterialTheme.colorScheme.primary
                    pct >= 60 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondary
                },
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            )
        }
    }
}

private fun formatHour(hour: Int): String {
    val h = hour % 24
    val suffix = if (h < 12) "AM" else "PM"
    val display = when (h % 12) {
        0 -> 12
        else -> h % 12
    }
    return "$display $suffix"
}

/**
 * Home stats section: a donut chart with a labeled legend over the
 * configured range, drawn with Canvas — no chart libraries. The
 * Types|Contacts toggle switches between time-per-event-type (percentages
 * of fill-window time) and per-contact interaction counts.
 */
@Composable
private fun StatsSection(
    stats: StatsData,
    contactStats: ContactStatsData,
    mode: StatsMode,
    onModeChange: (StatsMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Stats",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        StatsModeToggle(
            mode = mode,
            onModeChange = onModeChange,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        if (mode == StatsMode.TYPE) {
            Text(
                text = "How your time was spent · ${stats.rangeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatsDonut(
                    slices = stats.slices.map { it.color to it.millis },
                    total = stats.totalWindowMillis,
                    centerText = "",
                    modifier = Modifier.size(126.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    stats.slices.forEach { slice ->
                        StatsLegendRow(slice = slice, total = stats.totalWindowMillis)
                    }
                }
            }
        } else {
            Text(
                text = "Who you've spent time with · ${contactStats.rangeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatsDonut(
                    slices = contactStats.slices.map { it.color to it.count },
                    total = contactStats.totalCount,
                    // v0.19.0: no center number — the total counted
                    // "Alone time" and was misleading. Types mode has
                    // none either, by design.
                    centerText = "",
                    modifier = Modifier.size(126.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    contactStats.slices.forEach { slice ->
                        ContactStatsLegendRow(slice = slice)
                    }
                }
            }
        }
    }
}

/** Segmented Types|Contacts control at the top of the Stats card. */
@Composable
private fun StatsModeToggle(
    mode: StatsMode,
    onModeChange: (StatsMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(2.dp)
    ) {
        StatsMode.values().forEach { m ->
            val selected = m == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onModeChange(m) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = m.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatsDonut(
    slices: List<Pair<Color, Long>>,
    total: Long,
    centerText: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension / 5f
            val style = Stroke(width = strokeWidth)
            if (total <= 0L) {
                drawArc(
                    color = Color(0xFF616161),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = style
                )
                return@Canvas
            }
            var startAngle = -90f
            slices.forEach { (color, weight) ->
                val sweep = weight.toFloat() / total * 360f
                if (sweep > 0f) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        style = style
                    )
                    startAngle += sweep
                }
            }
        }
        if (centerText.isNotEmpty()) {
            Text(
                text = centerText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StatsLegendRow(slice: StatsSlice, total: Long) {
    val pct = if (total > 0L) slice.millis.toFloat() / total * 100f else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(slice.color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = slice.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "%.1f%%".format(pct),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ContactStatsLegendRow(slice: ContactStatsSlice) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(slice.color)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = slice.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = slice.count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
