package com.calonyx.app.ui.calendar
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.Contact
import com.calonyx.app.data.ContactSort
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.EventType
import com.calonyx.app.data.importexport.joinContactNames
import com.calonyx.app.ui.EventDisplay
import com.calonyx.app.ui.displayColor
import com.calonyx.app.ui.displayLabel
import com.calonyx.app.ui.formatTime
import com.calonyx.app.ui.toLocalDate
import com.calonyx.app.ui.toMillisAt
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Milestone 20: the calendar's journal view — the same day's events as
 * the timeline, but as editable cards instead of cascade tiles. It reads
 * from and writes to the exact same event rows; switching views never
 * converts, copies, or moves data.
 *
 * Card layout (per the milestone spec): status-dot button opening a
 * status dropdown, editable title, type dropdown with a chevron, one
 * meta line holding start–end times plus the "with …" contacts line
 * (an "Add contacts" button when none are linked), and a large details
 * field. No pencil/edit button anywhere — every field edits directly on
 * tap: title and details type inline, times open time pickers, the
 * contacts line opens the attendee picker.
 */
@Composable
fun JournalDayView(
    listState: LazyListState,
    displays: List<EventDisplay>,
    eventTypes: List<EventType>,
    contacts: List<Contact>,
    pickerSort: ContactSort,
    onPickerSortChange: (ContactSort) -> Unit,
    onPickerOpened: () -> Unit,
    onUpdateEvent: (Event) -> Unit,
    onLoadContactIds: suspend (Long) -> Set<Long>,
    onSetContacts: (Long, Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Bottom padding the ancestor already applies (the outer Scaffold's
     * content padding: nav bar + nav inset). Needed so the keyboard
     * compensation below doesn't count it twice.
     */
    scaffoldBottomPadding: Dp = 0.dp
) {
    val sorted = remember(displays) { displays.sortedBy { it.event.startTime } }
    if (sorted.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No events this day — tap + to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    // v0.20.4: the list must end above the keyboard. The app is
    // edge-to-edge (MainActivity enableEdgeToEdge), so the window
    // never shrinks for the keyboard and adjustResize does nothing —
    // without this, the list's viewport runs behind the keyboard,
    // which is why neither the v0.20.3 auto-scroll (it measured the
    // card as "visible" within the full-height viewport) nor manual
    // scrolling could reveal the last line being typed.
    // v0.20.5: end exactly at the keyboard's top edge, not a nav-bar
    // height above it. The ancestor already pads the content by the
    // outer Scaffold's bottom (nav bar + nav inset), and the keyboard
    // covers the nav bar — so the v0.20.4 full imePadding() counted
    // that bottom inset twice and left a dead band above the keyboard
    // that cut off the card below the focused one. The extra bottom
    // padding is only the part of the keyboard sticking out past the
    // Scaffold bottom, so the total is max(scaffold, keyboard) + 8dp.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val extraBottom = (imeBottom - scaffoldBottomPadding).coerceAtLeast(0.dp)
    // v0.20.6: the auto-scroll below measures against the keyboard's top
    // edge, which sits extraBottom above the list's bottom edge — the
    // cards need that overlap in pixels.
    val extraBottomPx = with(LocalDensity.current) { extraBottom.roundToPx() }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp + extraBottom)
    ) {
        items(sorted, key = { it.event.id }) { display ->
            JournalCard(
                display = display,
                listState = listState,
                eventTypes = eventTypes,
                contacts = contacts,
                pickerSort = pickerSort,
                onPickerSortChange = onPickerSortChange,
                onPickerOpened = onPickerOpened,
                onUpdateEvent = onUpdateEvent,
                onLoadContactIds = onLoadContactIds,
                onSetContacts = onSetContacts,
                keyboardOverlapPx = extraBottomPx
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalCard(
    display: EventDisplay,
    listState: LazyListState,
    eventTypes: List<EventType>,
    contacts: List<Contact>,
    pickerSort: ContactSort,
    onPickerSortChange: (ContactSort) -> Unit,
    onPickerOpened: () -> Unit,
    onUpdateEvent: (Event) -> Unit,
    onLoadContactIds: suspend (Long) -> Set<Long>,
    onSetContacts: (Long, Set<Long>) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How far the keyboard overlaps the list's bottom edge, in pixels
     * (v0.20.6: the bottom content padding past the Scaffold bottom).
     * The auto-scroll target is the keyboard's top edge, not the
     * list's bottom edge.
     */
    keyboardOverlapPx: Int = 0
) {
    val event = display.event
    val scope = rememberCoroutineScope()
    var showStatusMenu by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var pickerSelected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // Title and details autosave (debounced): the local text is keyed on
    // the event id only, so a DB round-trip after a save never yanks the
    // field or the cursor while typing.
    // v0.20.2: both fields hold TextFieldValue, not a plain String, so
    // the keyboard's composing region (predictive text) survives
    // recompositions. With String state the composing span was dropped
    // on re-set, and a backspace could then delete the wrong character
    // — a space several characters behind the cursor (verified
    // 2026-10-28: happens in every text field, fixed by turning text
    // suggestions off). The autosave effects key on .text only, so a
    // cursor move alone never restarts the debounce.
    var titleText by remember(event.id) { mutableStateOf(TextFieldValue(event.title)) }
    LaunchedEffect(titleText.text) {
        // Same fallback chain as the editor: explicit title, then the
        // type name, then the linked contacts' names (journal joining:
        // "and" for two, Oxford comma for three or more). With none of
        // those there is nothing to save to, so a blanked title is left
        // alone rather than stored blank.
        val resolved = titleText.text.trim().takeIf { it.isNotEmpty() }
            ?: display.typeName?.trim()?.takeIf { it.isNotEmpty() }
            ?: joinContactNames(display.contactNames).takeIf { it.isNotEmpty() }
        if (resolved != null && resolved != event.title) {
            delay(600)
            onUpdateEvent(event.copy(title = resolved))
        }
    }
    var detailsText by remember(event.id) { mutableStateOf(TextFieldValue(event.description.orEmpty())) }
    LaunchedEffect(detailsText.text) {
        val saved = detailsText.text.trim().ifBlank { null }
        if (saved != event.description) {
            delay(600)
            onUpdateEvent(event.copy(description = saved))
        }
    }
    // v0.20.3: keyboard scrolling, done explicitly with the list's own
    // API. The v0.20.2 BringIntoViewRequester approach never moved
    // this LazyColumn (adjustResize is confirmed in the manifest, so
    // the window mode isn't the cause). Instead the card measures
    // itself against the viewport: if the card's bottom sits below
    // the viewport end (i.e. under the keyboard), scroll by exactly
    // the overlap. Triggers: field focus, keyboard opening while a
    // field is focused, and text change while focused (so newlines
    // being typed scroll into view). Each check re-measures, so it
    // self-corrects per keystroke.
    var titleFocused by remember { mutableStateOf(false) }
    var detailsFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    fun scrollCardIntoView() {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == event.id } ?: return
        // v0.20.6: viewportEndOffset is the layout size minus the TOP
        // padding only — the bottom content padding still counts as
        // "visible" to it. Since v0.20.5 that padding holds the
        // keyboard overlap, so a card growing behind the keyboard
        // measured as visible and typing never scrolled. The real
        // visible end is the keyboard's top edge: viewport end minus
        // the keyboard overlap.
        val visibleEnd = info.viewportEndOffset - keyboardOverlapPx
        val overflow = (item.offset + item.size) - visibleEnd
        // Direct jump (no nested-scroll dispatch, so the search
        // collapse can never eat it): move content up by the overlap.
        if (overflow > 0) scope.launch {
            listState.scrollToItem(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset + overflow
            )
        }
    }
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && (titleFocused || detailsFocused)) scrollCardIntoView()
    }
    LaunchedEffect(showPicker) {
        if (showPicker) pickerSelected = onLoadContactIds(event.id)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left accent bar in the type color, echoing the timeline tiles.
            // v0.20.4: top-left and bottom-left corners rounded (2dp =
            // half the bar width), so back-to-back same-color events
            // read as separate bars instead of one long bar.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        display.typeColor,
                        shape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)
                    )
            )
            // v0.20.1: tighter vertical padding above/below the title
            // (was 12dp all round) — the card is about the report, not
            // the chrome around it.
            // v0.20.3: tighter still — the v0.20.1 outer-padding cut
            // left the rows themselves tall (TextButton minimum
            // height) with 4dp between them. Rows now sit 2dp apart
            // and the row buttons are capped at 32dp tall.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status dot: a button opening the status dropdown.
                    Box {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(event.status.displayColor())
                                .clickable { showStatusMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (event.status) {
                                    EventStatus.HAPPENED_RECORDED -> "✓"
                                    EventStatus.DID_NOT_HAPPEN -> "✕"
                                    EventStatus.UNMARKED -> ""
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        DropdownMenu(
                            expanded = showStatusMenu,
                            onDismissRequest = { showStatusMenu = false }
                        ) {
                            EventStatus.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(s.displayColor())
                                            )
                                            Text(
                                                text = s.displayLabel(),
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        onUpdateEvent(event.copy(status = s))
                                        showStatusMenu = false
                                    }
                                )
                            }
                        }
                    }
                    // Editable title, directly on the card.
                    BasicTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                        value = titleText,
                        onValueChange = {
                            titleText = it
                            if (titleFocused) scrollCardIntoView()
                        },
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 2,
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                if (titleText.text.isEmpty()) {
                                    Text(
                                        text = "Untitled event",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        },
                        modifier = Modifier.weight(1f)
                            .onFocusChanged {
                                titleFocused = it.isFocused
                                if (it.isFocused) scrollCardIntoView()
                            }
                    )
                    // Type dropdown, with a chevron so it reads as one.
                    Box {
                        TextButton(
                            onClick = { showTypeMenu = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(display.typeColor)
                            )
                            Text(
                                text = display.typeName ?: "No type",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                            Text(" ▾")
                        }
                        DropdownMenu(
                            expanded = showTypeMenu,
                            onDismissRequest = { showTypeMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("No type") },
                                onClick = {
                                    onUpdateEvent(event.copy(eventTypeId = null))
                                    showTypeMenu = false
                                }
                            )
                            eventTypes.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(type.colorArgb))
                                            )
                                            Text(
                                                text = type.name,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        onUpdateEvent(event.copy(eventTypeId = type.id))
                                        showTypeMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                // One meta line: times and contacts together. The
                // contacts text takes the remaining width and wraps
                // rather than truncating a long list.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { pickStart = true },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(formatTime(event.startTime)) }
                    Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = { pickEnd = true },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(formatTime(event.endTime)) }
                    Text(
                        " · ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (display.contactNames.isEmpty()) {
                        TextButton(
                            onClick = { onPickerOpened(); showPicker = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp)
                        ) { Text("Add contacts") }
                    } else {
                        // v0.20.1: exactly one line, ellipsized — the
                        // contacts line must never push the report down.
                        Text(
                            text = "with " + joinContactNames(display.contactNames),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onPickerOpened(); showPicker = true }
                        )
                    }
                }
                // Details field, greyed placeholder while empty.
                // v0.20.1: a blank field stays one line tall and grows
                // as text is typed — an event with no details no longer
                // pays 96dp of vertical space for the privilege.
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    value = detailsText,
                    onValueChange = {
                        detailsText = it
                        if (detailsFocused) scrollCardIntoView()
                    },
                    placeholder = { Text("Add details…") },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged {
                            detailsFocused = it.isFocused
                            if (it.isFocused) scrollCardIntoView()
                        }
                )
            }
        }
    }

    if (pickStart) {
        JournalTimePickerDialog(
            initialMillis = event.startTime,
            title = "Start time",
            onDismiss = { pickStart = false },
            onConfirm = { picked ->
                // Shifting the start preserves the duration.
                val newStart = event.startTime.toLocalDate().toMillisAt(picked)
                val duration = event.endTime - event.startTime
                onUpdateEvent(
                    event.copy(startTime = newStart, endTime = newStart + duration)
                )
                pickStart = false
            }
        )
    }
    if (pickEnd) {
        JournalTimePickerDialog(
            initialMillis = event.endTime,
            title = "End time",
            onDismiss = { pickEnd = false },
            onConfirm = { picked ->
                val newEnd = event.endTime.toLocalDate().toMillisAt(picked)
                    .coerceAtLeast(event.startTime + 15 * 60_000L)
                onUpdateEvent(event.copy(endTime = newEnd))
                pickEnd = false
            }
        )
    }
    if (showPicker) {
        PeoplePickerDialog(
            allContacts = contacts,
            selectedIds = pickerSelected,
            sort = pickerSort,
            onSortChange = onPickerSortChange,
            onToggle = { id ->
                val next = if (id in pickerSelected) pickerSelected - id else pickerSelected + id
                pickerSelected = next
                onSetContacts(event.id, next)
            },
            onDismiss = { showPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalTimePickerDialog(
    initialMillis: Long,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    // Time-of-day of the initial instant, in device time.
    val initial = Instant.ofEpochMilli(initialMillis)
        .atZone(ZoneId.systemDefault()).toLocalTime()
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(LocalTime.of(state.hour, state.minute))
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
