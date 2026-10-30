package com.calonyx.app.ui.home

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Contact
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.StatsMode
import com.calonyx.app.ui.EventDisplay
import com.calonyx.app.ui.endOfDayMillis
import com.calonyx.app.ui.millisAtHour
import com.calonyx.app.ui.startOfDayMillis
import com.calonyx.app.ui.withTypes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Fill summary for one calendar day in the month grid. */
data class DayFill(
    val date: LocalDate,
    /** 0..1 fraction of the fill window occupied by events. */
    val fillFraction: Float,
    val eventCount: Int
)

/** One slice of the Home stats donut chart. */
data class StatsSlice(
    val label: String,
    val color: Color,
    /** Clipped-to-window milliseconds in the range. */
    val millis: Long
)

/** Time breakdown for the Home stats section. */
data class StatsData(
    /** Event-type slices (only >0) plus "No type" and "Unused time". */
    val slices: List<StatsSlice>,
    /** Total fill-window millis across the range (the percentage denominator). */
    val totalWindowMillis: Long,
    /** e.g. "Today" or "Last 8 days". */
    val rangeLabel: String
) {
    companion object {
        val EMPTY = StatsData(emptyList(), 0L, "Today")
    }
}

/** One slice of the Home contact-stats donut chart. */
data class ContactStatsSlice(
    /** Null for the "Alone time" slice. */
    val contactId: Long?,
    val label: String,
    val color: Color,
    /** Number of events tallied for this row. */
    val count: Long
)

/** Interaction counts for the Home stats section in Contacts mode. */
data class ContactStatsData(
    /** Contact slices (only >0), most-interacted first, plus "Alone time". */
    val slices: List<ContactStatsSlice>,
    /** Sum of all tallies (the donut denominator). */
    val totalCount: Long,
    /** e.g. "Today" or "Last 8 days". */
    val rangeLabel: String
) {
    companion object {
        val EMPTY = ContactStatsData(emptyList(), 0L, "Today")
    }
}

/**
 * Fixed rotating palette for contact-stats donut slices. Assigned by
 * sorted position (most-interacted first), so colors stay stable across
 * recompositions. "Alone time" always uses [ALONE_TIME_COLOR].
 */
val CONTACT_STATS_PALETTE = listOf(
    Color(0xFFEF5350), Color(0xFFAB47BC), Color(0xFF5C6BC0),
    Color(0xFF29B6F6), Color(0xFF26A69A), Color(0xFF9CCC65),
    Color(0xFFFFCA28), Color(0xFFFF7043), Color(0xFF8D6E63),
    Color(0xFFEC407A)
)
val ALONE_TIME_COLOR = Color(0xFF9E9E9E)

/**
 * Home tab: unmarked past events plus the month fill-% dashboard. The fill
 * window comes from [AppSettings]; each day's cell shows what fraction of
 * that window is occupied by events.
 */
class HomeViewModel(private val repository: CalOnyxRepository) : ViewModel() {
    val today: LocalDate = LocalDate.now()

    /**
     * Events still needing a status: UNMARKED and already started, limited
     * to today and yesterday so a big import doesn't bury the user.
     * Most-recent-first.
     */
    val unmarkedEvents: StateFlow<List<EventDisplay>> =
        combine(
            repository.observeEventsInRange(
                today.minusDays(1).startOfDayMillis(),
                today.endOfDayMillis()
            ),
            repository.observeEventTypes()
        ) { events, types ->
            val now = System.currentTimeMillis()
            events.filter { it.status == EventStatus.UNMARKED && it.startTime <= now }
                .withTypes(types, Color(0xFFBB86FC))
                .sortedByDescending { it.event.startTime }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appSettings: StateFlow<AppSettings> =
        repository.observeAppSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val visibleMonth = MutableStateFlow(YearMonth.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val monthFill: StateFlow<List<DayFill>> =
        combine(visibleMonth, appSettings) { month, settings -> month to settings }
            .flatMapLatest { (month, settings) ->
                val first = month.atDay(1)
                val lastExclusive = month.atEndOfMonth().plusDays(1)
                repository.observeEventsInRange(first.startOfDayMillis(), lastExclusive.startOfDayMillis())
                    .map { events -> computeDayFills(month, events, settings) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun shiftMonth(months: Long) {
        visibleMonth.value = visibleMonth.value.plusMonths(months)
    }

    fun goToCurrentMonth() {
        visibleMonth.value = YearMonth.now()
    }

    /**
     * Time breakdown per event type over the fill window, for today plus
     * [AppSettings.statsPastDays] past days. Today is still in progress, so
     * its window is clipped at "now". DID_NOT_HAPPEN events never count;
     * the remainder of the window is the "Unused time" slice.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<StatsData> =
        combine(appSettings, repository.observeEventTypes()) { settings, types ->
            settings to types
        }.flatMapLatest { (settings, types) ->
            val from = today.minusDays(settings.statsPastDays.toLong()).startOfDayMillis()
            repository.observeEventsInRange(from, today.endOfDayMillis())
                .map { events -> computeStats(today, settings, events, types) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsData.EMPTY)

    /**
     * Interaction counts per contact over the same range the type stats
     * use (today plus [AppSettings.statsPastDays] past days). Each event
     * adds +1 to every linked contact; events with no linked contacts add
     * +1 to "Alone time". DID_NOT_HAPPEN events never count. Slices are
     * sized by count, most-interacted first.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val contactStats: StateFlow<ContactStatsData> =
        appSettings.flatMapLatest { settings ->
            val from = today.minusDays(settings.statsPastDays.toLong()).startOfDayMillis()
            repository.observeEventsInRange(from, today.endOfDayMillis())
                .map { events -> computeContactStats(settings, events) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ContactStatsData.EMPTY)

    /** Persists the Stats Types|Contacts toggle. */
    fun setStatsMode(mode: StatsMode) {
        viewModelScope.launch { repository.setStatsMode(mode) }
    }

    /**
     * Tallies per-contact interaction counts. Suspends for the link and
     * contact lookups; called inside the [contactStats] flow.
     */
    private suspend fun computeContactStats(
        settings: AppSettings,
        events: List<Event>
    ): ContactStatsData {
        val inRange = events.filter { it.status != EventStatus.DID_NOT_HAPPEN }
        val refs = if (inRange.isEmpty()) emptyList()
        else repository.getContactRefsForEvents(inRange.map { it.id })
        val contactIdsByEvent = refs.groupBy({ it.eventId }, { it.contactId })
        val counts = mutableMapOf<Long, Long>()
        var alone = 0L
        for (e in inRange) {
            val ids = contactIdsByEvent[e.id].orEmpty().distinct()
            if (ids.isEmpty()) alone++
            else ids.forEach { id -> counts[id] = (counts[id] ?: 0L) + 1L }
        }
        val contactsById = repository.getAllContacts().associateBy { it.id }
        val slices = mutableListOf<ContactStatsSlice>()
        counts.entries.sortedByDescending { it.value }.forEachIndexed { index, (id, count) ->
            if (count <= 0) return@forEachIndexed
            val contact = contactsById[id]
            val label = contact?.let {
                Contact.displayNameFor(it.firstName, it.lastName, it.displayName)
            } ?: "Unknown"
            slices.add(
                ContactStatsSlice(
                    contactId = id,
                    label = label,
                    color = CONTACT_STATS_PALETTE[index % CONTACT_STATS_PALETTE.size],
                    count = count
                )
            )
        }
        if (alone > 0) {
            slices.add(ContactStatsSlice(null, "Alone time", ALONE_TIME_COLOR, alone))
        }
        val rangeLabel = if (settings.statsPastDays == 0) "Today"
        else "Last ${settings.statsPastDays + 1} days"
        return ContactStatsData(slices, counts.values.sum() + alone, rangeLabel)
    }
}

/**
 * For every day of [month], computes what fraction of the configured fill
 * window is covered by events. Each event is clipped to the window and
 * overlapping intervals are merged (union), so simultaneous events count
 * once — the fill is how much of your time is accounted for, not the sum
 * of event durations. The result is capped at 100%.
 * Events marked DID_NOT_HAPPEN never fill the day.
 */
private fun computeDayFills(
    month: YearMonth,
    events: List<Event>,
    settings: AppSettings
): List<DayFill> {
    val windowMinutes = (settings.fillWindowEndHour - settings.fillWindowStartHour) * 60
    return (1..month.lengthOfMonth()).map { day ->
        val date = month.atDay(day)
        val winStart = date.millisAtHour(settings.fillWindowStartHour)
        val winEnd = date.millisAtHour(settings.fillWindowEndHour)
        val intervals = mutableListOf<Pair<Long, Long>>()
        var count = 0
        for (e in events) {
            if (e.status == EventStatus.DID_NOT_HAPPEN) continue
            val s = maxOf(e.startTime, winStart)
            val en = minOf(e.endTime, winEnd)
            if (en > s) {
                intervals.add(s to en)
                count++
            }
        }
        val busyMillis = unionMillis(intervals)
        val fraction = if (windowMinutes > 0) {
            (busyMillis / 60_000f / windowMinutes).coerceIn(0f, 1f)
        } else 0f
        DayFill(date, fraction, count)
    }
}

/**
 * Total millis covered by the union of [intervals] ([start, end) pairs).
 * Overlapping or touching intervals merge into one; disjoint ones add up.
 */
private fun unionMillis(intervals: List<Pair<Long, Long>>): Long {
    val sorted = intervals.filter { it.second > it.first }.sortedBy { it.first }
    if (sorted.isEmpty()) return 0L
    var total = 0L
    var curStart = sorted[0].first
    var curEnd = sorted[0].second
    for (i in 1..sorted.lastIndex) {
        val (s, e) = sorted[i]
        if (s <= curEnd) {
            curEnd = maxOf(curEnd, e)
        } else {
            total += curEnd - curStart
            curStart = s
            curEnd = e
        }
    }
    return total + (curEnd - curStart)
}

/**
 * Sums per-event-type millis clipped to each day's fill window over today
 * plus [settings.statsPastDays] past days. Today's window ends at "now"
 * since the day is still in progress. Only types with >0 time get a slice;
 * events without a type share a "No type" slice. Per-type slices sum each
 * type's own time (an hour in two types counts for both); the "Unused time"
 * slice is the window minus the UNION of all event intervals, so overlaps
 * never double-count. Percentages are taken against the total window millis.
 */
private fun computeStats(
    today: LocalDate,
    settings: AppSettings,
    events: List<Event>,
    types: List<com.calonyx.app.data.EventType>
): StatsData {
    val now = System.currentTimeMillis()
    val perType = mutableMapOf<Long?, Long>()
    var totalWindow = 0L
    var usedUnion = 0L
    for (d in 0..settings.statsPastDays) {
        val date = today.minusDays(d.toLong())
        val winStart = date.millisAtHour(settings.fillWindowStartHour)
        var winEnd = date.millisAtHour(settings.fillWindowEndHour)
        if (d == 0) winEnd = minOf(winEnd, now)
        if (winEnd <= winStart) continue
        totalWindow += winEnd - winStart
        val intervals = mutableListOf<Pair<Long, Long>>()
        for (e in events) {
            if (e.status == EventStatus.DID_NOT_HAPPEN) continue
            val s = maxOf(e.startTime, winStart)
            val en = minOf(e.endTime, winEnd)
            if (en > s) {
                perType[e.eventTypeId] = (perType[e.eventTypeId] ?: 0L) + (en - s)
                intervals.add(s to en)
            }
        }
        usedUnion += unionMillis(intervals)
    }
    val byId = types.associateBy { it.id }
    val slices = mutableListOf<StatsSlice>()
    perType.entries.sortedByDescending { it.value }.forEach { (typeId, millis) ->
        if (millis <= 0) return@forEach
        val type = typeId?.let { byId[it] }
        slices.add(
            StatsSlice(
                label = type?.name ?: "No type",
                color = type?.let { Color(it.colorArgb) } ?: Color(0xFF9E9E9E),
                millis = millis
            )
        )
    }
    // Unused time always appears so the chart reads against the full window.
    slices.add(
        StatsSlice(
            label = "Unused time",
            color = Color(0xFF616161),
            millis = (totalWindow - usedUnion).coerceAtLeast(0L)
        )
    )
    val rangeLabel = if (settings.statsPastDays == 0) "Today"
    else "Last ${settings.statsPastDays + 1} days"
    return StatsData(slices, totalWindow, rangeLabel)
}
