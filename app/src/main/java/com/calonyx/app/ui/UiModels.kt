package com.calonyx.app.ui

import androidx.compose.ui.graphics.Color
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.EventType

/**
 * An event paired with its type's display info for rendering in lists and
 * the day timeline. [typeColor] falls back to the theme primary when the
 * event has no type.
 */
data class EventDisplay(
    val event: Event,
    val typeName: String?,
    val typeColor: Color,
    /** Display names of linked contacts (populated for timeline tiles). */
    val contactNames: List<String> = emptyList()
)

/** Resolves type display info for a batch of events. */
fun List<Event>.withTypes(types: List<EventType>, fallback: Color): List<EventDisplay> {
    val byId = types.associateBy { it.id }
    return map { event ->
        val type = event.eventTypeId?.let { byId[it] }
        EventDisplay(
            event = event,
            typeName = type?.name,
            typeColor = type?.let { Color(it.colorArgb) } ?: fallback
        )
    }
}

/** Blueprint color key for the three event statuses. */
fun EventStatus.displayColor(): Color = when (this) {
    EventStatus.UNMARKED -> Color(0xFF9E9E9E)
    EventStatus.HAPPENED_RECORDED -> Color(0xFF4CAF50)
    EventStatus.DID_NOT_HAPPEN -> Color(0xFFF44336)
}

fun EventStatus.displayLabel(): String = when (this) {
    EventStatus.UNMARKED -> "Unmarked"
    EventStatus.HAPPENED_RECORDED -> "Happened ✓"
    EventStatus.DID_NOT_HAPPEN -> "Didn't happen"
}

/** Layout info for one event tile in the day timeline. */
data class EventLayout(
    val display: EventDisplay,
    /** Zero-based column among overlapping events. */
    val column: Int,
    /** Total columns in this event's overlap cluster. */
    val columnCount: Int
)

/**
 * Groups overlapping events into clusters and assigns each a column so
 * simultaneous events render side-by-side (per the blueprint's cascade).
 */
fun layoutDayEvents(events: List<EventDisplay>): List<EventLayout> {
    if (events.isEmpty()) return emptyList()
    val sorted = events.sortedBy { it.event.startTime }
    // Cluster: events connected through overlaps.
    val clusters = mutableListOf<MutableList<EventDisplay>>()
    for (e in sorted) {
        val last = clusters.lastOrNull()
        if (last != null && e.event.startTime < last.maxOf { it.event.endTime }) {
            last.add(e)
        } else {
            clusters.add(mutableListOf(e))
        }
    }
    return clusters.flatMap { cluster ->
        val columnEndTimes = mutableListOf<Long>()
        val assignment = mutableMapOf<Long, Int>()
        for (e in cluster.sortedBy { it.event.startTime }) {
            val free = columnEndTimes.indexOfFirst { it <= e.event.startTime }
            if (free >= 0) {
                columnEndTimes[free] = e.event.endTime
                assignment[e.event.id] = free
            } else {
                columnEndTimes.add(e.event.endTime)
                assignment[e.event.id] = columnEndTimes.size - 1
            }
        }
        val count = columnEndTimes.size.coerceAtLeast(1)
        cluster.map { EventLayout(it, assignment[it.event.id] ?: 0, count) }
    }
}
