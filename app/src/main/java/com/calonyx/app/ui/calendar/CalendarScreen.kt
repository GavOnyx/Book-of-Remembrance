package com.calonyx.app.ui.calendar
import androidx.compose.foundation.text.KeyboardOptions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.IconButton
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.ui.EventDisplay
import com.calonyx.app.ui.EventLayout
import com.calonyx.app.ui.components.CalendarTabIcon
import com.calonyx.app.ui.components.EventListItem
import com.calonyx.app.ui.components.JournalIcon
import com.calonyx.app.ui.components.PlusIcon
import com.calonyx.app.ui.displayColor
import com.calonyx.app.ui.displayLabel
import com.calonyx.app.ui.formatDayHeader
import com.calonyx.app.ui.formatTime
import com.calonyx.app.ui.layoutDayEvents
import com.calonyx.app.ui.millisAtHour
import com.calonyx.app.ui.toUtcPickerMillis
import com.calonyx.app.ui.utcPickerMillisToLocalDate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private const val HOUR_MILLIS = 3_600_000L
private const val MIN_EVENT_MILLIS = 15 * 60_000L
private const val SNAP_MILLIS = 15 * 60_000L

/** Snaps a timestamp to the nearest 15-minute boundary. */
private fun snapTo15(millis: Long): Long {
    val r = millis % SNAP_MILLIS
    return millis - r + if (r >= SNAP_MILLIS / 2) SNAP_MILLIS else 0
}

/** Outcome of a tile's pre-long-press tracking phase. */
private enum class Phase1 { UP, MOVED, PINCH }

/**
 * Calendar tab: day timeline cascade with date navigation, title search,
 * tap-to-create / tap-to-edit. The visible hour range comes from [AppSettings].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onEditEvent: (Long) -> Unit,
    onNewEvent: (startMillis: Long, endMillis: Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val date by viewModel.selectedDate.collectAsState()
    val dayEvents by viewModel.dayEvents.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    // v0.20.7: the search field holds TextFieldValue (not the
    // ViewModel's String) so the keyboard's composing region survives
    // recompositions — the String-state form destroyed it on every
    // keystroke, the mechanism behind the backspace space-eating bug.
    // The ViewModel String stays the source of truth for filtering;
    // this mirrors it, and re-syncs if it ever changes elsewhere.
    var queryField by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (queryField.text != query) queryField = TextFieldValue(query)
    }
    val results by viewModel.searchResults.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val todaySignal by viewModel.todaySignal.collectAsState()
    val eventTypes by viewModel.eventTypes.collectAsState()
    val selectedIds by viewModel.selectedEventIds.collectAsState()
    val hourHeightDp by viewModel.hourHeightDp.collectAsState()
    // Milestone 20: timeline vs journal day view, persisted in settings.
    val journalMode = settings.calendarJournalMode
    val pickerSort by viewModel.pickerSort.collectAsState()
    val pickerContacts by viewModel.pickerContacts.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()
    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTypeDialog by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val pendingUndo by viewModel.pendingUndo.collectAsState()

    // The timeline's ScrollState is hoisted here (v0.20.0) so the day
    // timeline keeps its position across mode switches.
    val timelineScrollState = rememberScrollState()
    val journalListState = rememberLazyListState()
    val resultsListState = rememberLazyListState()
    var searchFocused by remember { mutableStateOf(false) }

    // v0.20.1: scroll-tied search collapse. The v0.20.0 boolean
    // AnimatedVisibility popped the field in/out whole, which yanked
    // the content by the full field height. Now the field's height is
    // driven by nested-scroll deltas consumed before the list scrolls,
    // so it reveals at exactly the rate the user scrolls — no jump
    // beyond the finger's own movement. A focused field always stays
    // fully shown, so collapsing can never swallow it mid-typing.
    val density = LocalDensity.current
    val searchFullHeightPx = remember { mutableStateOf(0f) }
    val searchHeightPx = remember { mutableStateOf(0f) }
    var searchMeasured by remember { mutableStateOf(false) }
    val searchFocusedRef = rememberUpdatedState(searchFocused)
    val searchCollapseConnection = remember {
        // v0.20.3: the v0.20.2 branches were backwards — nested-scroll
        // deltas are screen-pixel deltas (y grows downward), so a
        // finger dragging UP (scrolling down the list) arrives as
        // NEGATIVE dy, and a finger dragging DOWN (scrolling up)
        // arrives as POSITIVE dy. v0.20.2 hid on positive dy, which
        // is why the field showed on scroll down and hid on scroll
        // up. The branches below are the corrected mapping, verified
        // against that report: scrolling down hides, scrolling up
        // reveals, at exactly the scrolled rate.
        // Programmatic scrolls must never be eaten by the field: only
        // touch drags, flings and wheel scrolls drive the collapse.
        // (In this Compose version the sources are Drag/Fling/Wheel/
        // Relocate; Relocate is bring-into-view scrolling — letting
        // the field consume it is exactly what broke the journal
        // keyboard scroll in v0.20.2, where the collapse connection
        // ate the relocation deltas instead of the list.)
        fun adjust(dy: Float, source: NestedScrollSource): Offset {
            if (searchFocusedRef.value) return Offset.Zero
            if (source != NestedScrollSource.Drag &&
                source != NestedScrollSource.Fling &&
                source != NestedScrollSource.Wheel
            ) return Offset.Zero
            val full = searchFullHeightPx.value
            if (full <= 0f) return Offset.Zero
            return if (dy < 0) {
                // Scrolling down the list: hide the field first.
                val consumed = minOf(-dy, searchHeightPx.value)
                searchHeightPx.value -= consumed
                Offset(0f, -consumed)
            } else if (dy > 0) {
                // Scrolling up the list: reveal the field first.
                val toReveal = (full - searchHeightPx.value).coerceAtLeast(0f)
                val consumed = minOf(dy, toReveal)
                searchHeightPx.value += consumed
                Offset(0f, consumed)
            } else Offset.Zero
        }
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                adjust(available.y, source)
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                adjust(available.y, source)
        }
    }
    LaunchedEffect(searchFocused) {
        if (searchFocused) searchHeightPx.value = searchFullHeightPx.value
    }

    // v0.18.0: the now-line must be current whenever the user looks at
    // the calendar — recompute it on every resume (app backgrounded and
    // reopened, phone woken, tab returned to), not just on navigation.
    var nowTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) nowTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back exits selection mode first; only then does the app-level
    // back handler navigate away. Inner handlers take precedence.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    // Undo snackbar for event deletes (single + bulk). The buffer is
    // dropped when the snackbar times out, is dismissed, or this tab is
    // left — a new delete replaces the buffer.
    LaunchedEffect(pendingUndo) {
        val undone = pendingUndo
        if (undone != null) {
            val n = undone.events.size
            val result = snackbarHostState.showSnackbar(
                message = if (n == 1) "Event deleted" else "$n events deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
            else viewModel.clearUndo()
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.clearUndo() } }

    // Searching hides the timeline; never leave a stale selection behind.
    LaunchedEffect(query) {
        if (query.isNotBlank() && selectionMode) viewModel.clearSelection()
    }

    Scaffold(
        modifier = modifier.padding(contentPadding),
        // Edge-to-edge (M14) lets system insets flow into Compose; the
        // status-bar inset already arrives once via the outer Scaffold's
        // contentPadding above, so the inner default would apply it twice
        // and leave a dead gap at the top. Zeroed to restore the pre-M14
        // layout exactly.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    // v0.18.0: "+" starts at the current time (seconds
                    // cleared). Tapping an hour in the cascade still snaps
                    // to that hour — unchanged.
                    val start = LocalDateTime.of(date, LocalTime.now())
                        .withSecond(0).withNano(0)
                    onNewEvent(
                        start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                        start.plusHours(1).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    )
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                PlusIcon(tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner)
                .nestedScroll(searchCollapseConnection)
        ) {
            if (selectionMode) {
                // Contextual bulk-action bar replaces the date nav while selecting.
                // v0.19.1: the REAL fix for the broken bar. Five buttons plus a
                // titleMedium count exceeded narrow screens; the weighted count
                // was crushed into a sliver, wrapped letter-by-letter, and the
                // multi-line Text stretched the whole Row vertically — that
                // tall row with centered buttons WAS the "dead space above
                // the bar". Tighter button padding + a smaller count style
                // fit everything on one line; maxLines/softWrap/Ellipsis
                // guarantee the count can never wrap again.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selectedIds.size} selected",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 12.dp)
                    )
                    val barBtnPadding = PaddingValues(horizontal = 4.dp)
                    TextButton(
                        onClick = { showTypeDialog = true },
                        contentPadding = barBtnPadding
                    ) { Text("Type", maxLines = 1, softWrap = false) }
                    TextButton(
                        onClick = { showStatusDialog = true },
                        contentPadding = barBtnPadding
                    ) { Text("Status", maxLines = 1, softWrap = false) }
                    TextButton(
                        onClick = { viewModel.duplicateSelectedEvents() },
                        contentPadding = barBtnPadding
                    ) { Text("Duplicate", maxLines = 1, softWrap = false) }
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        contentPadding = barBtnPadding
                    ) {
                        Text(
                            "Delete",
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(
                        onClick = { viewModel.clearSelection() },
                        contentPadding = barBtnPadding
                    ) { Text("✕", maxLines = 1, softWrap = false) }
                }
            } else {
                // Date navigation header (slim: ~10% shorter than before).
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Milestone 20: the journal toggle sits left of the
                    // date selector. It shows the destination: the
                    // journal glyph in the timeline, the calendar glyph
                    // while in journal mode.
                    IconButton(onClick = { viewModel.setCalendarJournalMode(!journalMode) }) {
                        if (journalMode) CalendarTabIcon(tint = MaterialTheme.colorScheme.primary)
                        else JournalIcon(tint = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { viewModel.shiftDay(-1) }) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                    Text(
                        text = formatDayHeader(date),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showDatePicker = true }
                            .padding(vertical = 8.dp)
                    )
                    TextButton(onClick = { viewModel.shiftDay(1) }) { Text("›", style = MaterialTheme.typography.headlineMedium) }
                    TextButton(onClick = { viewModel.goToday() }) { Text("Today") }
                }
            }
            // Search (slim: ~10% shorter than before). v0.20.1: its
            // height is scroll-driven (see the collapse connection
            // above); the first frame measures its natural height.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (searchMeasured) Modifier.height(
                            with(density) { searchHeightPx.value.toDp() }
                        ) else Modifier
                    )
                    .clipToBounds()
            ) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    value = queryField,
                    onValueChange = { queryField = it; viewModel.searchQuery.value = it.text },
                    placeholder = { Text("Search events") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                        .onSizeChanged {
                            if (!searchMeasured) {
                                searchFullHeightPx.value = it.height.toFloat()
                                searchHeightPx.value = it.height.toFloat()
                                searchMeasured = true
                            }
                        }
                        .onFocusChanged { searchFocused = it.isFocused }
                )
            }
            if (query.isNotBlank()) {
                LazyColumn(
                    state = resultsListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results, key = { it.event.id }) { display ->
                        EventListItem(display = display, onClick = { onEditEvent(display.event.id) })
                    }
                }
            } else if (journalMode) {
                JournalDayView(
                    listState = journalListState,
                    displays = dayEvents,
                    eventTypes = eventTypes,
                    contacts = pickerContacts,
                    pickerSort = pickerSort,
                    onPickerSortChange = viewModel::setPickerSort,
                    onPickerOpened = viewModel::pickerOpened,
                    onUpdateEvent = viewModel::updateJournalEvent,
                    onLoadContactIds = viewModel::contactIdsForEvent,
                    onSetContacts = viewModel::setEventContacts,
                    modifier = Modifier.fillMaxSize(),
                    // v0.20.5: the ancestor pads this content by the
                    // outer Scaffold's bottom; the journal list needs
                    // it so its keyboard padding doesn't double-count.
                    scaffoldBottomPadding = contentPadding.calculateBottomPadding()
                )
            } else {
                DayTimeline(
                    date = date,
                    layouts = layoutDayEvents(dayEvents),
                    settings = settings,
                    scrollSignal = todaySignal,
                    scrollState = timelineScrollState,
                    nowSignal = nowTick,
                    hourHeight = hourHeightDp.dp,
                    selectedIds = selectedIds,
                    selectionMode = selectionMode,
                    onEventClick = onEditEvent,
                    onEventToggleSelect = viewModel::toggleEventSelection,
                    onEmptyHourClick = { dateTime ->
                        if (selectionMode) {
                            // Tapping empty space exits selection mode.
                            viewModel.clearSelection()
                        } else {
                            val start = dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            onNewEvent(start, start + HOUR_MILLIS)
                        }
                    },
                    onShiftDay = viewModel::shiftDay,
                    onZoom = viewModel::zoomTimeline,
                    onMoveEvent = viewModel::moveEvent,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete ${selectedIds.size} events?") },
            text = { Text("You can undo this for a few seconds.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelectedEvents()
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showTypeDialog) {
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            title = { Text("Set type for ${selectedIds.size} events") },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = false,
                        onClick = {
                            viewModel.setTypeForSelected(null)
                            showTypeDialog = false
                        },
                        label = { Text("None") }
                    )
                    eventTypes.forEach { type ->
                        val color = Color(type.colorArgb)
                        FilterChip(
                            selected = false,
                            onClick = {
                                viewModel.setTypeForSelected(type.id)
                                showTypeDialog = false
                            },
                            label = { Text(type.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.25f)
                            )
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTypeDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showStatusDialog) {
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("Set status for ${selectedIds.size} events") },
            text = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    EventStatus.entries.forEach { s ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                viewModel.setStatusForSelected(s)
                                showStatusDialog = false
                            },
                            label = { Text(s.displayLabel()) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(s.displayColor())
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStatusDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toUtcPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.selectDate(it.utcPickerMillisToLocalDate()) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * Vertical day timeline restricted to the configured hour range.
 * Tap an event tile to edit it; tap an empty hour (or the hour gutter) to
 * create an event there. Long-press a tile to enter multi-select mode;
 * long-press AND drag moves the tile vertically (15-minute snap).
 *
 * Gestures on the timeline container:
 * - one-finger horizontal drag past 72dp switches days (vertical drags
 *   still scroll, taps still work — only horizontal movement past touch
 *   slop is consumed);
 * - two-finger pinch zooms the hour height (24–160dp per hour).
 *
 * The container's swipe arbitration ignores already-consumed moves, so a
 * tile's drag-move (which consumes its moves once long-press is armed)
 * can never also trigger a day swipe.
 */
@Composable
private fun DayTimeline(
    date: LocalDate,
    layouts: List<EventLayout>,
    settings: AppSettings,
    scrollSignal: Int,
    /** Hoisted (v0.20.0) so the search-hide tracker can read direction. */
    scrollState: ScrollState,
    /** Bumped on every resume so the now-line recomputes (v0.18.0). */
    nowSignal: Int,
    hourHeight: Dp,
    selectedIds: Set<Long>,
    selectionMode: Boolean,
    onEventClick: (Long) -> Unit,
    onEventToggleSelect: (Long) -> Unit,
    onEmptyHourClick: (LocalDateTime) -> Unit,
    onShiftDay: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    onMoveEvent: (Event, Long, Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val hourPx = with(density) { hourHeight.toPx() }
    val startHour = settings.calendarStartHour
    val endHour = settings.calendarEndHour
    val hourCount = (endHour - startHour).coerceAtLeast(1)
    val rangeStart = date.millisAtHour(startHour)
    val rangeEnd = date.millisAtHour(endHour)
    // Recomputed on every resume via nowSignal — the line is always
    // current when the user looks at the calendar, without yanking the
    // scroll position (centering stays keyed on scrollSignal only).
    val now = remember(nowSignal) { System.currentTimeMillis() }
    val isToday = date == LocalDate.now()
    // Horizontal swipe threshold for day switching.
    val swipeThresholdPx = with(density) { 72.dp.toPx() }

    // Drag-move state. While a tile is dragged it renders from these
    // values; on release the same snapped values are committed to the DB,
    // so the tile never visually jumps between drag position and final
    // position (the v0.4.1 failure).
    var dragEventId by remember { mutableStateOf<Long?>(null) }
    var dragActive by remember { mutableStateOf(false) }
    var dragStartMillis by remember { mutableStateOf(0L) }
    var dragDurationMillis by remember { mutableStateOf(0L) }

    // Hold the drag render position until the DB round-trips: clearing the
    // drag state only once the observed event matches the committed start
    // avoids a one-frame flicker back to the pre-drag position on release.
    // The timeout is a safety net so a failed write can't stick the tile.
    LaunchedEffect(layouts, dragActive) {
        val id = dragEventId ?: return@LaunchedEffect
        if (dragActive) return@LaunchedEffect
        val matches = layouts.firstOrNull { it.display.event.id == id }
            ?.display?.event?.let { it.startTime == dragStartMillis } == true
        if (matches) {
            dragEventId = null
        } else {
            delay(3000)
            if (!dragActive && dragEventId == id) dragEventId = null
        }
    }

    // BoxWithConstraints gives the viewport height so the now-line can be
    // centered on entry without ever scrolling past the content bounds.
    //
    // A single custom gesture detector handles both the one-finger
    // horizontal day-swipe and the two-finger pinch zoom, so the two can
    // never fight each other. Taps, long-press and vertical scrolling are
    // untouched: this detector consumes a gesture only once it has committed
    // to a horizontal swipe or a pinch.
    //
    // requireUnconsumed = false is essential here. Pointer events dispatch
    // child-first, and the timeline is tiled with clickable children (hour
    // rows, gutter, event tiles) whose tap detection consumes the down
    // press — with the default requireUnconsumed = true, awaitFirstDown()
    // would never return and both swipe and pinch would silently die.
    // Pointers are tracked by id so a second finger can never corrupt the
    // swipe math.
    BoxWithConstraints(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val id1 = down.id
                var id2: PointerId? = null
                // 0 = undecided, 1 = horizontal day-swipe, 2 = pinch zoom
                var mode = 0
                var totalDx = 0f
                var totalDy = 0f
                var prevSpan = 0f
                val slop = viewConfiguration.touchSlop
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }.associateBy { it.id }
                    // A second finger starts (or continues) a pinch, unless
                    // we're already committed to a horizontal swipe.
                    if (id2 == null && mode != 1 && pressed.containsKey(id1)) {
                        val other = pressed.keys.firstOrNull { it != id1 }
                        if (other != null) {
                            id2 = other
                            mode = 2
                            prevSpan = 0f
                        }
                    }
                    val c1 = pressed[id1]
                    val c2 = id2?.let { pressed[it] }
                    when {
                        c1 == null && c2 == null -> finished = true
                        id2 != null -> {
                            if (c1 != null && c2 != null) {
                                val d = c1.position - c2.position
                                val span = sqrt(d.x * d.x + d.y * d.y)
                                if (prevSpan > 0f && span > 0f) {
                                    onZoom(span / prevSpan)
                                }
                                prevSpan = span
                                // Swallow the gesture so scrolling doesn't
                                // jitter mid-pinch.
                                c1.consume()
                                c2.consume()
                            } else {
                                // A finger lifted mid-pinch: end the gesture
                                // cleanly instead of snapping into a swipe.
                                finished = true
                            }
                        }
                        c1 != null -> {
                            if (mode == 0) {
                                // A child that consumed the move (a tile whose
                                // long-press is armed, i.e. a drag-move in
                                // progress) owns the gesture: never let those
                                // moves also commit a day swipe.
                                if (!c1.isConsumed) {
                                    totalDx += c1.position.x - c1.previousPosition.x
                                    totalDy += c1.position.y - c1.previousPosition.y
                                    when {
                                        abs(totalDx) > slop && abs(totalDx) > abs(totalDy) -> {
                                            mode = 1
                                            c1.consume()
                                        }
                                        abs(totalDy) > slop && abs(totalDy) > abs(totalDx) ->
                                            // Vertical: the inner scroll owns this gesture.
                                            finished = true
                                    }
                                }
                            } else {
                                totalDx += c1.position.x - c1.previousPosition.x
                                c1.consume()
                                // A stray second finger during a swipe must not
                                // fall through as a tap on a child.
                                pressed.values.forEach { if (it.id != id1) it.consume() }
                            }
                        }
                        else -> finished = true
                    }
                }
                if (mode == 1) {
                    when {
                        totalDx <= -swipeThresholdPx -> onShiftDay(1L)
                        totalDx >= swipeThresholdPx -> onShiftDay(-1L)
                    }
                }
            }
        }
    ) {
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val contentHeightPx = hourPx * hourCount

        // Centers the red now-line on first entry and on "Today" presses.
        // Keyed on the signal + hour range only, so manual scrolling is
        // never fought afterwards.
        LaunchedEffect(scrollSignal, startHour, endHour) {
            if (isToday && now in rangeStart..rangeEnd) {
                val nowPx = (now - rangeStart) / HOUR_MILLIS.toFloat() * hourPx
                val maxScroll = (contentHeightPx - viewportHeightPx).coerceAtLeast(0f)
                scrollState.scrollTo(
                    (nowPx - viewportHeightPx / 2f).coerceIn(0f, maxScroll).roundToInt()
                )
            } else {
                scrollState.scrollTo(0)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .height(hourHeight * hourCount)
        ) {
            // Time gutter (tapping an hour creates an event there, same as
            // tapping empty cascade space).
            Column(modifier = Modifier.width(52.dp).fillMaxHeight()) {
                for (hour in startHour until endHour) {
                    Box(
                        modifier = Modifier
                            .height(hourHeight)
                            .fillMaxWidth()
                            .clickable {
                                onEmptyHourClick(LocalDateTime.of(date, LocalTime.of(hour % 24, 0)))
                            },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = LocalTime.of(hour % 24, 0).format(DateTimeFormatter.ofPattern("h a")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            // Timeline area
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                val widthPx = with(density) { maxWidth.toPx() }
                // Hour rows (tap empty hour to create)
                Column {
                    for (hour in startHour until endHour) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(hourHeight)
                                .clickable {
                                    onEmptyHourClick(LocalDateTime.of(date, LocalTime.of(hour % 24, 0)))
                                }
                        ) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                modifier = Modifier.align(Alignment.TopCenter)
                            )
                        }
                    }
                }
                // v0.19.3: a tile's 28dp minimum height must never spill
                // into the tile below it (back-to-back short events like a
                // 15-minute event over the next event rendered as if they
                // overlapped). For each tile, record the top and horizontal
                // range of every other tile; a tile is capped at the
                // distance to the nearest tile below it whose range
                // intersects its own. Same-column neighbours never overlap
                // in time, and a later cluster starts after every earlier
                // event ends, so the cap is always >= the tile's true
                // duration height — only minimum-height overflow is ever
                // trimmed, never real event time.
                val tileBounds = layouts.mapNotNull { layout ->
                    val event = layout.display.event
                    if (event.endTime <= rangeStart || event.startTime >= rangeEnd) return@mapNotNull null
                    val isDragging = dragEventId == event.id
                    val effStart = if (isDragging) dragStartMillis else event.startTime
                    val effEnd = if (isDragging) dragStartMillis + dragDurationMillis else event.endTime
                    val clampedStart = effStart.coerceIn(rangeStart, rangeEnd)
                    val clampedEnd = effEnd.coerceIn(rangeStart, rangeEnd)
                        .coerceAtLeast(clampedStart + MIN_EVENT_MILLIS)
                    val top = (clampedStart - rangeStart) / HOUR_MILLIS.toFloat() * hourPx
                    val xFrac = layout.column / layout.columnCount.toFloat()
                    val wFrac = 1f / layout.columnCount.toFloat()
                    val left = (xFrac * widthPx).roundToInt() + 2
                    val right = left + ((wFrac * widthPx) - 4).roundToInt().coerceAtLeast(24)
                    Triple(event.id, top, left to right)
                }
                // Event tiles: tap to edit (or toggle in selection mode),
                // long-press to multi-select, long-press + drag to move.
                layouts.forEach { layout ->
                    val event = layout.display.event
                    // Skip events entirely outside the visible range.
                    if (event.endTime <= rangeStart || event.startTime >= rangeEnd) return@forEach
                    val isDragging = dragEventId == event.id
                    // While dragging, the tile renders from the drag state —
                    // the same snapped values committed on release, so there
                    // is no visual jump (and no tile scaling, per v0.4.1).
                    val effStart = if (isDragging) dragStartMillis else event.startTime
                    val effEnd = if (isDragging) dragStartMillis + dragDurationMillis else event.endTime
                    // The tile's live visual position, which the move gesture
                    // reads (via rememberUpdatedState, never as a pointerInput
                    // key) so a re-drag engages from where the tile actually
                    // sits rather than the stale event held by the gesture
                    // coroutine (v0.13.2).
                    val effDuration = effEnd - effStart
                    val clampedStart = effStart.coerceIn(rangeStart, rangeEnd)
                    val clampedEnd = effEnd.coerceIn(rangeStart, rangeEnd)
                        .coerceAtLeast(clampedStart + MIN_EVENT_MILLIS)
                    val topPx = (clampedStart - rangeStart) / HOUR_MILLIS.toFloat() * hourPx
                    val heightPx = ((clampedEnd - clampedStart) / HOUR_MILLIS.toFloat() * hourPx)
                        .coerceAtLeast(with(density) { 28.dp.toPx() })
                    val xFrac = layout.column / layout.columnCount.toFloat()
                    val wFrac = 1f / layout.columnCount.toFloat()
                    val tileX = (xFrac * widthPx).roundToInt() + 2
                    val tileW = ((wFrac * widthPx) - 4).roundToInt().coerceAtLeast(24)
                    // v0.19.3: trim minimum-height overflow at the nearest
                    // tile below in an intersecting horizontal range.
                    val capPx = tileBounds
                        .filter { it.first != event.id && it.second > topPx &&
                            it.third.first < tileX + tileW && tileX < it.third.second }
                        .minOfOrNull { it.second - topPx }
                    val cappedHeightPx = if (capPx != null) minOf(heightPx, capPx) else heightPx
                    val selected = event.id in selectedIds

                    EventTile(
                        display = layout.display,
                        selected = selected,
                        dragging = isDragging,
                        dragStartMillis = dragStartMillis,
                        // The contacts line only fits on taller tiles; short
                        // ones keep title + time.
                        showContacts = with(density) { cappedHeightPx.roundToInt().toDp() } >= 80.dp,
                        // Below the old 28dp floor, the tile is too short
                        // for title + time: title only, centered.
                        compact = with(density) { cappedHeightPx.roundToInt().toDp() } < 28.dp,
                        modifier = Modifier
                            .offset { IntOffset(tileX, topPx.roundToInt()) }
                            .size(
                                width = with(density) { tileW.toDp() },
                                height = with(density) { cappedHeightPx.roundToInt().toDp() }
                            )
                            .tileMoveGesture(
                                event = event,
                                hourPx = hourPx,
                                rangeStart = rangeStart,
                                rangeEnd = rangeEnd,
                                visualStartMillis = effStart,
                                visualDurationMillis = effDuration,
                                onTap = {
                                    if (selectionMode) onEventToggleSelect(event.id)
                                    else onEventClick(event.id)
                                },
                                onToggleSelect = { onEventToggleSelect(event.id) },
                                onDragStart = {
                                    dragActive = true
                                    dragEventId = event.id
                                },
                                onDragMove = { startMillis, durationMillis ->
                                    dragStartMillis = startMillis
                                    dragDurationMillis = durationMillis
                                },
                                onDragEnd = { commit, startMillis, durationMillis ->
                                    dragActive = false
                                    if (commit) {
                                        // Keep rendering from the drag state
                                        // until the DB round-trips (see the
                                        // LaunchedEffect above): the tile
                                        // never flickers back mid-release.
                                        onMoveEvent(
                                            event,
                                            startMillis,
                                            startMillis + durationMillis
                                        )
                                    } else {
                                        dragEventId = null
                                    }
                                }
                            )
                    )
                }
                // "Now" line
                if (isToday && now in rangeStart..rangeEnd) {
                    val nowPx = (now - rangeStart) / HOUR_MILLIS.toFloat() * hourPx
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, nowPx.roundToInt()) }
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        } // Row (timeline)
    } // BoxWithConstraints (viewport)
}

@Composable
private fun EventTile(
    display: EventDisplay,
    selected: Boolean,
    dragging: Boolean,
    dragStartMillis: Long,
    showContacts: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val event = display.event
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = display.typeColor.copy(alpha = if (selected) 0.45f else 0.22f)
        ),
        // Lifted while dragging (elevation only — never scaled, per v0.4.1).
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 1.dp),
        // v0.19.3 restyle: sharp corners, no all-around border — a single
        // thicker accent bar on the LEFT edge carries the type color
        // (primary when selected, mirroring the old border colors).
        // The light tinted fill stays.
        shape = RectangleShape
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // v0.20.4: top-left and bottom-left corners rounded (2dp =
            // half the bar width), so back-to-back same-color events
            // read as separate bars instead of one long bar.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else display.typeColor,
                        shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)
                    )
            )
            if (compact) {
                // Too short for title + time: title only, centered.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(event.status.displayColor())
                    )
                    Text(
                        text = event.title.ifBlank { "(untitled)" },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
        Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp)) {
            // v0.18.0: status dot sits on the title line, immediately left
            // of the title (was below it, next to the time).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(event.status.displayColor())
                )
                Text(
                    text = event.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                // While dragging, show the live snapped time.
                text = formatTime(if (dragging) dragStartMillis else event.startTime),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (showContacts && display.contactNames.isNotEmpty()) {
                Text(
                    text = "with ${display.contactNames.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } // Column (full tile)
            } // else (non-compact)
        } // Row (accent bar + content)
    } // Card
}

/**
 * Tile-level gesture arbitration: tap, long-press multi-select, and
 * long-press+drag move.
 *
 * - Quick tap (up before the long-press timeout, no slop movement) → [onTap].
 * - Movement past touch slop before the timeout → abandoned WITHOUT
 *   consuming moves, so the container's day-swipe and the row's vertical
 *   scroll behave exactly as before.
 * - A second finger at any point → abandoned; the container's pinch owns it.
 * - Long-press timeout with the finger held still → selection mode is
 *   entered immediately via [onToggleSelect] (v0.19.2; matches the contacts
 *   view), with a haptic. From here every subsequent move is consumed, so
 *   the container's swipe arbitration (which ignores consumed moves) can
 *   never fire mid-gesture:
 *   - release without dragging → nothing more to do (already selected);
 *   - vertical drag past 8dp → the arming toggle is undone, then drag-move
 *     engages (haptic): the tile follows the finger with 15-minute snapping
 *     via [onDragMove]; release commits through [onDragEnd]. Drag end-states
 *     are unchanged (moved, not selected).
 * - If the coroutine is cancelled mid-drag (navigation, dialog), the drag
 *   reverts with no DB write.
 */
@Composable
private fun Modifier.tileMoveGesture(
    event: Event,
    hourPx: Float,
    rangeStart: Long,
    rangeEnd: Long,
    // The tile's current visual start/duration (drag state while dragging,
    // DB event otherwise). Read via rememberUpdatedState below — NEVER as a
    // pointerInput key, since they change during a drag and would relaunch
    // the gesture mid-drag.
    visualStartMillis: Long,
    visualDurationMillis: Long,
    onTap: () -> Unit,
    onToggleSelect: () -> Unit,
    onDragStart: () -> Unit,
    onDragMove: (startMillis: Long, durationMillis: Long) -> Unit,
    onDragEnd: (commit: Boolean, startMillis: Long, durationMillis: Long) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // The gesture coroutine below launches once (keys: event.id, hourPx,
    // rangeStart, rangeEnd) and never restarts, so any plain capture of
    // event/visualStartMillis/onDragEnd would go stale after a DB commit.
    // rememberUpdatedState keeps the coroutine reading the latest values
    // without relaunching it (v0.13.2).
    val currentVisualStart by rememberUpdatedState(visualStartMillis)
    val currentVisualDuration by rememberUpdatedState(visualDurationMillis)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    return pointerInput(event.id, hourPx, rangeStart, rangeEnd) {
        val slopPx = viewConfiguration.touchSlop
        val slopSq = slopPx * slopPx
        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val dragThresholdPx = with(density) { 8.dp.toPx() }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            val downId = down.id
            var dragging = false
            var completed = false
            // v0.19.2: tracks the arming toggle below, so a later
            // drag-engage (or a cancellation) can undo it exactly once.
            var armedToggled = false
            try {
                // Phase 1: tap vs long-press vs scroll/swipe. Moves are
                // deliberately NOT consumed here.
                val phase1 = withTimeoutOrNull(longPressTimeout) {
                    while (true) {
                        val e = awaitPointerEvent()
                        val pressed = e.changes.filter { it.pressed }
                        if (pressed.any { it.id != downId }) return@withTimeoutOrNull Phase1.PINCH
                        val c = pressed.firstOrNull { it.id == downId }
                            ?: return@withTimeoutOrNull Phase1.UP
                        val dx = c.position.x - down.position.x
                        val dy = c.position.y - down.position.y
                        if (dx * dx + dy * dy > slopSq) return@withTimeoutOrNull Phase1.MOVED
                    }
                }
                when (phase1) {
                    Phase1.UP -> {
                        completed = true
                        onTap()
                        return@awaitEachGesture
                    }
                    Phase1.MOVED, Phase1.PINCH -> {
                        completed = true
                        return@awaitEachGesture
                    }
                    null -> {
                        // v0.19.2: long-press recognized — enter selection
                        // mode NOW, while the finger is still down (matches
                        // the contacts view), instead of waiting for release.
                        // A later drag undoes this toggle at engage time, so
                        // drag end-states are unchanged.
                        armedToggled = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleSelect()
                    }
                }

                // Phase 2: long-press armed — consume everything from here on.
                var baseStart = 0L
                var duration = 0L
                var lastStart = 0L
                var pxPerHour = 0f
                var endCommit = false
                var finished = false
                // Accumulated incrementally: per-event deltas are
                // frame-invariant under the tile's own vertical translation
                // (the tile re-offsets as it follows the finger), whereas
                // differencing against the stale down point subtracts the
                // tile's own movement and oscillates.
                var totalDy = 0f
                while (!finished) {
                    val e = awaitPointerEvent()
                    val pressed = e.changes.filter { it.pressed }
                    if (pressed.any { it.id != downId }) {
                        // Second finger: commit an in-progress drag at its
                        // current snapped position, then hand off to pinch.
                        if (dragging) endCommit = true
                        finished = true
                    } else {
                        val c = pressed.firstOrNull { it.id == downId }
                        if (c == null) {
                            if (dragging) endCommit = true
                            finished = true
                        } else {
                            c.consume()
                            // Incremental per-event delta: frame-invariant under
                            // the tile's own vertical translation (mirrors the
                            // container swipe detector's pattern).
                            totalDy += c.position.y - c.previousPosition.y
                            if (!dragging && abs(totalDy) > dragThresholdPx) {
                                dragging = true
                                // v0.19.2: the arming toggle above selected
                                // this event; a drag moves instead of
                                // selecting, so undo it — the drag end-state
                                // stays exactly as before (moved, not
                                // selected).
                                if (armedToggled) {
                                    armedToggled = false
                                    onToggleSelect()
                                }
                                // Engage from where the tile actually renders
                                // right now (v0.13.2): the coroutine's
                                // captured `event` is the pre-first-drag DB
                                // row and would snap a same-direction re-drag
                                // back to the old position.
                                baseStart = currentVisualStart
                                duration = currentVisualDuration
                                    .coerceAtLeast(MIN_EVENT_MILLIS)
                                pxPerHour = hourPx
                                lastStart = baseStart
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragStart()
                            }
                            if (dragging) {
                                val raw = baseStart + (totalDy / pxPerHour * HOUR_MILLIS).toLong()
                                lastStart = snapTo15(raw).coerceIn(
                                    rangeStart,
                                    (rangeEnd - duration).coerceAtLeast(rangeStart)
                                )
                                onDragMove(lastStart, duration)
                            }
                        }
                    }
                }
                completed = true
                if (dragging) {
                    // Routed through rememberUpdatedState (v0.13.2): the
                    // call-site lambda closes over the latest event, so a
                    // commit never writes back stale title/description/etc.
                    // via moveEvent's event.copy(...).
                    currentOnDragEnd(endCommit, lastStart, duration)
                } else {
                    // v0.19.2: long-press + release, no drag — the toggle
                    // (and its haptic) already happened at arming time, so
                    // there is nothing left to do. This branch also covers a
                    // second finger arriving while armed: selection was
                    // already entered at arming, then hands off to pinch.
                }
            } finally {
                if (!completed && armedToggled && !dragging) {
                    // v0.19.2: cancelled after arming but before any
                    // release/drag decision — undo the arming toggle so a
                    // dead gesture leaves selection untouched.
                    onToggleSelect()
                }
                if (!completed && dragging) {
                    // Cancelled mid-drag (navigation, dialog, …): revert the
                    // visual state with no DB write.
                    currentOnDragEnd(false, 0L, 0L)
                }
            }
        }
    }
}
