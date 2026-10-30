package com.calonyx.app.ui.calendar

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.DeletedEvents
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.EventType
import com.calonyx.app.ui.EventDisplay
import com.calonyx.app.ui.endOfDayMillis
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

/** Bounds for the pinch-zoomable timeline hour height (dp per hour). */
const val MIN_HOUR_HEIGHT_DP = 24f
const val MAX_HOUR_HEIGHT_DP = 160f

/** Minimum event duration kept by drag-move (matches the timeline's). */
private const val MIN_EVENT_MILLIS = 15 * 60_000L

/**
 * Calendar tab: the selected day, its events, the event-type palette, title
 * search results, and the user-configurable display settings.
 */
class CalendarViewModel(private val repository: CalOnyxRepository) : ViewModel() {

    val selectedDate = MutableStateFlow(LocalDate.now())

    /**
     * Bumped every time "Today" is pressed so the day timeline can
     * re-center the now-line even when the date doesn't change.
     */
    val todaySignal = MutableStateFlow(0)

    /** Behavior settings (calendar hour range, …). Defaults until seeded. */
    val appSettings: StateFlow<AppSettings> =
        repository.observeAppSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val eventTypes: StateFlow<List<EventType>> =
        repository.observeEventTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Journal picker sort, mirroring the event editor's picker. */
    val pickerSort = MutableStateFlow(com.calonyx.app.data.ContactSort.ALPHABETICAL)

    /** Contacts for the journal card's attendee picker, in picker sort order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pickerContacts: StateFlow<List<com.calonyx.app.data.Contact>> =
        pickerSort.flatMapLatest { repository.observeContactsSorted(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPickerSort(sort: com.calonyx.app.data.ContactSort) {
        pickerSort.value = sort
    }

    /**
     * v0.20.2: the attendee picker opens on the default contacts sort
     * (Settings → Contacts), not on the hardcoded alphabetical default.
     * A sort picked inside the dialog still holds until the next open.
     */
    fun pickerOpened() {
        pickerSort.value =
            com.calonyx.app.data.ContactSort.fromName(appSettings.value.contactSortOrder)
    }

    /** Milestone 20: flips the calendar day view between timeline and journal. */
    fun setCalendarJournalMode(enabled: Boolean) {
        viewModelScope.launch {
            val current = appSettings.value
            repository.saveAppSettings(current.copy(calendarJournalMode = enabled))
        }
    }

    /**
     * Journal-card save. Mirrors the editor's edit-detaches rule: editing
     * a recurring-series instance clears its series link, so a later
     * "delete this and future events" won't remove the customized copy.
     */
    fun updateJournalEvent(event: Event) {
        viewModelScope.launch {
            val detached = if (event.seriesId != null) {
                event.copy(seriesId = null, recurrenceRule = null)
            } else event
            repository.updateEvent(detached)
        }
    }

    /** Contact ids currently linked to one event (journal picker initial state). */
    suspend fun contactIdsForEvent(eventId: Long): Set<Long> =
        repository.getContactRefsForEvents(listOf(eventId))
            .map { it.contactId }.toSet()

    /** Replaces one event's attendee links (journal picker Done). */
    fun setEventContacts(eventId: Long, contactIds: Set<Long>) {
        viewModelScope.launch {
            repository.setEventContactLinks(eventId, contactIds.toList())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val dayEvents: StateFlow<List<EventDisplay>> =
        combine(selectedDate, eventTypes) { date, types -> date to types }
            .flatMapLatest { (date, types) ->
                repository.observeEventsInRange(date.startOfDayMillis(), date.endOfDayMillis())
                    .combine(eventTypes) { events, t -> events.withTypes(t, Color(0xFFBB86FC)) }
            }
            .flatMapLatest { displays ->
                // Re-resolve linked-contact names whenever contacts change so
                // renames propagate to the timeline tiles — and whenever
                // LINKS change (v0.20.1). The journal picker writes links
                // without touching the event row, so watching only the
                // events/contacts tables left journal names stale until
                // an editor resave rewrote the row.
                combine(
                    repository.observeContactsAlphabetical(),
                    repository.observeAllRefs()
                ) { _, _ -> Unit }
                    .map {
                        val namesByEvent =
                            repository.getContactNamesForEvents(displays.map { it.event.id })
                        displays.map { d -> d.copy(contactNames = namesByEvent[d.event.id].orEmpty()) }
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchQuery = MutableStateFlow("")

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<EventDisplay>> =
        searchQuery.flatMapLatest { q ->
            if (q.isBlank()) {
                kotlinx.coroutines.flow.flowOf(emptyList<EventDisplay>())
            } else {
                repository.searchEvents(q).combine(eventTypes) { events, types ->
                    events.withTypes(types, Color(0xFFBB86FC)).sortedByDescending { it.event.startTime }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
        clearSelection()
    }

    fun goToday() {
        selectedDate.value = LocalDate.now()
        todaySignal.value += 1
        clearSelection()
    }

    fun shiftDay(days: Long) {
        selectedDate.value = selectedDate.value.plusDays(days)
        clearSelection()
    }

    /** Vertical zoom level of the day timeline, in dp per hour. */
    val hourHeightDp = MutableStateFlow(64f)

    fun zoomTimeline(factor: Float) {
        hourHeightDp.value = (hourHeightDp.value * factor).coerceIn(MIN_HOUR_HEIGHT_DP, MAX_HOUR_HEIGHT_DP)
    }

    /** Event ids currently selected for bulk actions; empty = not in selection mode. */
    val selectedEventIds = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleEventSelection(id: Long) {
        selectedEventIds.value =
            if (id in selectedEventIds.value) selectedEventIds.value - id
            else selectedEventIds.value + id
    }

    fun clearSelection() {
        selectedEventIds.value = emptySet()
    }

    /** Most recently deleted events awaiting a possible Undo; null when none. */
    val pendingUndo = MutableStateFlow<DeletedEvents?>(null)

    /** Single-event delete with undo (used by the event editor). */
    fun deleteEventWithUndo(event: Event) {
        deleteEventWithScope(event, deleteFuture = false)
    }

    /**
     * Deletes an event with undo. When [deleteFuture] is true and the
     * event belongs to a recurring series, every instance from this one
     * onward is deleted; otherwise only the single event.
     */
    fun deleteEventWithScope(event: Event, deleteFuture: Boolean) {
        viewModelScope.launch {
            pendingUndo.value = if (deleteFuture && event.seriesId != null) {
                repository.deleteSeriesFrom(event)
            } else {
                repository.deleteEventsWithUndo(listOf(event))
            }
        }
    }

    /** Deletes all selected events and exits selection mode; undoable. */
    fun deleteSelectedEvents() {
        viewModelScope.launch {
            val ids = selectedEventIds.value
            if (ids.isEmpty()) return@launch
            val events = dayEvents.value
                .filter { it.event.id in ids }
                .map { it.event }
            pendingUndo.value = repository.deleteEventsWithUndo(events)
            selectedEventIds.value = emptySet()
        }
    }

    /** Restores the buffered delete (rows keep their original ids, links too). */
    fun undoDelete() {
        viewModelScope.launch {
            pendingUndo.value?.let { repository.restoreDeletedEvents(it) }
            pendingUndo.value = null
        }
    }

    /** Drops the undo buffer (timeout, dismissal, or navigation away). */
    fun clearUndo() {
        pendingUndo.value = null
    }

    /** Applies a status to all selected events via non-destructive update. */
    fun setStatusForSelected(status: EventStatus) {
        viewModelScope.launch {
            val ids = selectedEventIds.value
            if (ids.isEmpty()) return@launch
            dayEvents.value
                .filter { it.event.id in ids }
                .forEach { repository.updateEvent(it.event.copy(status = status)) }
            selectedEventIds.value = emptySet()
        }
    }

    /** Applies an event type (or none) to all selected events. */
    fun setTypeForSelected(typeId: Long?) {
        viewModelScope.launch {
            val ids = selectedEventIds.value
            if (ids.isEmpty()) return@launch
            dayEvents.value
                .filter { it.event.id in ids }
                .forEach { repository.updateEvent(it.event.copy(eventTypeId = typeId)) }
            selectedEventIds.value = emptySet()
        }
    }

    /** Duplicates all selected events (fresh UIDs, same fields + attendees). */
    fun duplicateSelectedEvents() {
        viewModelScope.launch {
            val ids = selectedEventIds.value
            if (ids.isEmpty()) return@launch
            val events = dayEvents.value
                .filter { it.event.id in ids }
                .map { it.event }
            repository.duplicateEvents(events)
            selectedEventIds.value = emptySet()
        }
    }

    /**
     * Drag-move commit from the timeline: shifts the event, preserving its
     * duration. Uses the non-destructive update so links survive. A
     * recurring-series instance moved this way is detached (series link
     * cleared), mirroring the editor's edit-detaches rule — a later
     * "delete this and future events" won't remove the customized copy.
     */
    fun moveEvent(event: Event, newStartMillis: Long, newEndMillis: Long) {
        viewModelScope.launch {
            repository.updateEvent(
                event.copy(
                    startTime = newStartMillis,
                    endTime = newEndMillis.coerceAtLeast(newStartMillis + MIN_EVENT_MILLIS),
                    seriesId = null,
                    recurrenceRule = null
                )
            )
        }
    }
}
