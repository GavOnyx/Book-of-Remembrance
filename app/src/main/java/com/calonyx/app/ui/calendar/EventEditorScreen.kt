package com.calonyx.app.ui.calendar
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Contact
import com.calonyx.app.data.ContactSort
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.EventWithContacts
import com.calonyx.app.data.importexport.Recurrence
import com.calonyx.app.data.importexport.RecurrenceChoice
import com.calonyx.app.data.importexport.summary
import com.calonyx.app.data.importexport.toRRule
import com.calonyx.app.ui.displayColor
import com.calonyx.app.ui.displayLabel
import com.calonyx.app.ui.formatDate
import com.calonyx.app.ui.formatDateTime
import com.calonyx.app.ui.formatTime
import com.calonyx.app.ui.toLocalDate
import com.calonyx.app.ui.toLocalDateTime
import com.calonyx.app.ui.toMillis
import com.calonyx.app.ui.toMillisAt
import com.calonyx.app.ui.toUtcPickerMillis
import com.calonyx.app.ui.utcPickerMillisToLocalDate
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Full-screen event editor: title, start/end date+time, type, status,
 * location, description, and linked people. Used for both creating and
 * editing; [eventId] == null means a new event.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun EventEditorScreen(
    eventId: Long?,
    initialStartMillis: Long,
    initialEndMillis: Long,
    repository: CalOnyxRepository,
    onDismiss: () -> Unit,
    /**
     * Called with the event and whether a recurring series should be
     * deleted from this instance onward (true) or just this one instance
     * (false). The caller owns the undo buffer.
     */
    onDeleteEvent: (Event, Boolean) -> Unit = { _, _ -> },
    onOpenContact: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val existing: EventWithContacts? by
        (if (eventId != null) repository.observeEventWithContacts(eventId)
         else kotlinx.coroutines.flow.flowOf(null))
            .collectAsState(initial = null)
    val eventTypes by repository.observeEventTypes().collectAsState(initial = emptyList())
    // The attendee picker mirrors the Contacts view's sorting (same
    // repository ordering); the picker's choice is local to the editor.
    // v0.20.2: it opens on the default contacts sort (Settings →
    // Contacts), like the journal picker — a sort picked inside the
    // dialog still holds until the next open.
    val appSettings by repository.observeAppSettings().collectAsState(initial = AppSettings())
    // v0.20.4: keyed on the settings' default sort, so the picker
    // opens on the default from the first open — the old
    // ALPHABETICAL init plus the click-time reset made the dialog
    // open on a stale alphabetical list that swapped to the default
    // order a moment later, leaving it scrolled down.
    var pickerSort by remember(appSettings.contactSortOrder) {
        mutableStateOf(ContactSort.fromName(appSettings.contactSortOrder))
    }
    val allContacts by repository.observeContactsSorted(pickerSort).collectAsState(initial = emptyList())

    var initialized by remember(eventId) { mutableStateOf(false) }
    // v0.20.2: title, location and description hold TextFieldValue, not
    // a plain String — same fix as the journal fields (see JournalView):
    // the keyboard's composing region survives recompositions, so a
    // backspace with predictive text on can no longer delete the wrong
    // character (a space behind the cursor). Saves read .text.
    var title by remember(eventId) { mutableStateOf(TextFieldValue("")) }
    var startMillis by remember(eventId) { mutableStateOf(initialStartMillis) }
    var endMillis by remember(eventId) { mutableStateOf(initialEndMillis) }
    // v0.18.0: once the user sets the times by hand, picking a type no
    // longer applies its default duration over them.
    var timesDirty by remember(eventId) { mutableStateOf(false) }
    var location by remember(eventId) { mutableStateOf(TextFieldValue("")) }
    var description by remember(eventId) { mutableStateOf(TextFieldValue("")) }
    var typeId by remember(eventId) { mutableStateOf<Long?>(null) }
    var status by remember(eventId) { mutableStateOf(EventStatus.UNMARKED) }
    var selectedContactIds by remember(eventId) { mutableStateOf(emptySet<Long>()) }
    var error by remember(eventId) { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSeriesDeleteDialog by remember { mutableStateOf(false) }
    var seriesInstanceCount by remember { mutableStateOf(0) }
    var showPeoplePicker by remember { mutableStateOf(false) }
    // Recurrence is a creation-time choice only: editing an instance
    // detaches it from its series (see save()), so the Repeat row is
    // hidden when editing.
    var recurrence by remember(eventId) { mutableStateOf(RecurrenceChoice()) }
    var showRecurrenceDialog by remember { mutableStateOf(false) }
    // v0.17.0: title validation highlight + focus, type dropdown state.
    // v0.17.1: bring-into-view requesters for the text fields (replaces the
    // v0.17.0 hand-rolled scroll math, which overshot). With adjustResize +
    // a single bottom inset, the foundation scrolls the focused field just
    // above the keyboard. (This BOM = foundation 1.6.8, so the API is
    // BringIntoViewRequester in ...foundation.relocation.)
    var titleError by remember(eventId) { mutableStateOf(false) }
    val titleFocusRequester = remember { FocusRequester() }
    val editorScroll = rememberScrollState()
    val titleBiv = remember { BringIntoViewRequester() }
    val locationBiv = remember { BringIntoViewRequester() }
    val descriptionBiv = remember { BringIntoViewRequester() }
    var typeMenuExpanded by remember { mutableStateOf(false) }

    // Populate fields once the existing event loads.
    LaunchedEffect(existing, eventId) {
        if (eventId != null && !initialized && existing != null) {
            val e = existing!!.event
            title = TextFieldValue(e.title)
            startMillis = e.startTime
            endMillis = e.endTime
            location = TextFieldValue(e.location ?: "")
            description = TextFieldValue(e.description ?: "")
            typeId = e.eventTypeId
            status = e.status
            selectedContactIds = existing!!.contacts.map { it.id }.toSet()
            initialized = true
        }
    }

    fun save() {
        // Title fallback chain (v0.17.0): an explicit title wins, then the
        // event type's name, then the linked contacts' names. With none of
        // those the title is required: block the save and highlight the
        // field. Resolution lives in the repository (the save path), so no
        // caller can bypass it.
        val resolvedTitle = repository.resolveEventTitle(
            rawTitle = title.text,
            typeName = eventTypes.find { it.id == typeId }?.name,
            contactNames = allContacts
                .filter { it.id in selectedContactIds }
                .map { it.displayName }
        )
        if (resolvedTitle == null) {
            error = "Title is required."
            titleError = true
            titleFocusRequester.requestFocus()
            return
        }
        if (endMillis < startMillis) {
            error = "End time must not be before start time."
            return
        }
        error = null
        titleError = false
        scope.launch {
            val rrule = if (eventId == null) recurrence.toRRule() else null
            val rule = rrule?.let { Recurrence.parseRRule(it) }
            if (rule != null) {
                // New recurring event: materialize the series as instance
                // rows sharing one seriesId. Each instance is an ordinary
                // event row, so the calendar, stats, and export all work
                // unchanged; the RRULE string is kept for export.
                val seriesId = UUID.randomUUID().toString()
                val startZoned =
                    Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault())
                val duration = (endMillis - startMillis).coerceAtLeast(0)
                val instances = Recurrence.expandInstances(startZoned, duration, rule)
                instances.forEachIndexed { n, (s, e) ->
                    val id = repository.saveEvent(
                        Event(
                            title = resolvedTitle,
                            startTime = s,
                            endTime = e,
                            location = location.text.trim().ifBlank { null },
                            description = description.text.trim().ifBlank { null },
                            eventTypeId = typeId,
                            status = status,
                            // The VEVENT's UID doubles as the series id, so
                            // export and re-import round-trip the series.
                            icalUid = "$seriesId#$n",
                            recurrenceRule = rrule,
                            seriesId = seriesId
                        )
                    )
                    selectedContactIds.forEach { repository.linkContactToEvent(id, it) }
                }
            } else {
                val before = existing?.contacts?.map { it.id }?.toSet() ?: emptySet()
                val id = repository.saveEvent(
                    Event(
                        id = eventId ?: 0,
                        title = resolvedTitle,
                        startTime = startMillis,
                        endTime = endMillis,
                        location = location.text.trim().ifBlank { null },
                        description = description.text.trim().ifBlank { null },
                        eventTypeId = typeId,
                        status = status,
                        // Editing a recurring instance detaches it: the new
                        // row keeps no series link, so a later "delete this
                        // and future events" won't remove the customized copy.
                        recurrenceRule = null,
                        seriesId = null
                    )
                )
                // upsert(REPLACE) cascades and wipes links, so re-link everything selected.
                selectedContactIds.forEach { repository.linkContactToEvent(id, it) }
                (before - selectedContactIds).forEach { repository.unlinkContactFromEvent(id, it) }
            }
            onDismiss()
        }
    }

    /** Delete tap: recurring instances with siblings get a scope dialog. */
    fun onDeleteTapped() {
        val e = existing?.event
        if (e?.seriesId != null) {
            scope.launch {
                val count = repository.countSeriesEvents(e.seriesId)
                if (count > 1) {
                    seriesInstanceCount = count
                    showSeriesDeleteDialog = true
                } else {
                    showDeleteConfirm = true
                }
            }
        } else {
            showDeleteConfirm = true
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (eventId == null) "New Event" else "Edit Event") },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("‹ Back") }
                },
                actions = {
                    if (eventId != null) {
                        TextButton(onClick = ::onDeleteTapped) {
                            Text("Delete", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Button(onClick = ::save) { Text("Save") }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // v0.17.1: exactly one bottom inset. Top/side insets come
                // from the Scaffold; the bottom is the union of nav bar +
                // IME (safeContent), so the keyboard is compensated once —
                // no panning (adjustResize) and no dead zone above it.
                .padding(
                    top = inner.calculateTopPadding(),
                    start = inner.calculateStartPadding(LocalLayoutDirection.current),
                    end = inner.calculateEndPadding(LocalLayoutDirection.current)
                )
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                // v0.20.5: safeContent's bottom is already the union
                // of the nav bar and the IME, so it alone ends the
                // form exactly at the keyboard's top edge. The
                // v0.20.4 imePadding() on top of it counted the
                // keyboard twice — the same double-count dead band
                // as the journal list, masked here only because the
                // fields' bring-into-view still revealed them.
                .verticalScroll(editorScroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = title,
                onValueChange = { title = it; titleError = false },
                label = { Text("Title") },
                singleLine = true,
                isError = titleError,
                supportingText = {
                    if (titleError) Text("Add a title, pick a type, or link a person.")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocusRequester)
                    .bringIntoViewRequester(titleBiv)
                    .onFocusChanged {
                        if (it.isFocused) scope.launch { titleBiv.bringIntoView() }
                    }
            )

            // Event type dropdown (v0.17.0: was a chip cloud). Keeps the
            // color-dot affordance per type, the "None" option, and the
            // default-duration behavior when a type is picked.
            val selectedType = eventTypes.find { it.id == typeId }
            Text("Type", style = MaterialTheme.typography.titleSmall)
            ExposedDropdownMenuBox(
                expanded = typeMenuExpanded,
                onExpandedChange = { typeMenuExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    value = selectedType?.name ?: "None",
                    onValueChange = {},
                    readOnly = true,
                    leadingIcon = {
                        selectedType?.let {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color(it.colorArgb))
                            )
                        }
                    },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = typeMenuExpanded,
                    onDismissRequest = { typeMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = {
                            typeId = null
                            typeMenuExpanded = false
                        }
                    )
                    eventTypes.forEach { type ->
                        val color = Color(type.colorArgb)
                        DropdownMenuItem(
                            text = { Text(type.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                )
                            },
                            onClick = {
                                typeId = type.id
                                // Picking a type applies its default duration —
                                // but never over times the user set by hand.
                                if (!timesDirty) {
                                    endMillis = startMillis + type.defaultDurationMinutes * 60_000L
                                }
                                typeMenuExpanded = false
                            }
                        )
                    }
                }
            }

            // Start / end pickers
            DateTimeRow(
                label = "Starts",
                millis = startMillis,
                onChanged = {
                    startMillis = it
                    timesDirty = true
                    // Moving the start past the end pushes the end forward to
                    // match; end must never precede start.
                    if (endMillis < it) endMillis = it
                }
            )
            DateTimeRow(
                label = "Ends",
                millis = endMillis,
                // An end before the start is ignored entirely.
                onChanged = { if (it >= startMillis) { endMillis = it; timesDirty = true } }
            )

            // Recurrence (creation only — editing an instance detaches it,
            // so there is nothing to configure here when editing).
            if (eventId == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Repeat",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { showRecurrenceDialog = true }) {
                        Text(recurrence.summary())
                    }
                }
            }

            // Status selector (blueprint color key: Unmarked / Happened / Didn't happen).
            // v0.17.1: compact custom chips — FilterChip's fixed internal
            // padding ellipsized the labels at the default text size.
            Text("Status", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                EventStatus.entries.forEach { s ->
                    StatusChip(
                        selected = status == s,
                        onClick = { status = s },
                        dotColor = s.displayColor(),
                        label = s.displayLabel(),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = location,
                onValueChange = { location = it },
                label = { Text("Location (optional)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(locationBiv)
                    .onFocusChanged {
                        if (it.isFocused) scope.launch { locationBiv.bringIntoView() }
                    }
            )
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = description,
                onValueChange = { description = it },
                label = { Text("Details / report (optional)") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(descriptionBiv)
                    .onFocusChanged {
                        // v0.17.1: the foundation scrolls the focused field
                        // just above the keyboard (single bottom inset, no
                        // panning) — no hand-rolled coordinate math.
                        if (it.isFocused) scope.launch { descriptionBiv.bringIntoView() }
                    }
            )

            // People
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("People", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    pickerSort = ContactSort.fromName(appSettings.contactSortOrder)
                    showPeoplePicker = true
                }) { Text("+ Add") }
            }
            val selectedContacts = allContacts.filter { it.id in selectedContactIds }
            if (selectedContacts.isEmpty()) {
                Text(
                    "No one linked yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    selectedContacts.forEach { contact ->
                        PersonChip(
                            contact = contact,
                            onOpen = { onOpenContact(contact.id) },
                            onRemove = { selectedContactIds = selectedContactIds - contact.id }
                        )
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            // v0.19.0: ID + last-edited footer on existing events. The ID is
            // selectable for copy/paste; hidden while creating (no ID yet).
            if (eventId != null) {
                existing?.let { ewc ->
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Text(
                                text = "Event ID: ${ewc.event.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Last edited: " +
                                    if (ewc.event.updatedAt > 0) formatDateTime(ewc.event.updatedAt)
                                    else "—",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPeoplePicker) {
        PeoplePickerDialog(
            allContacts = allContacts,
            selectedIds = selectedContactIds,
            sort = pickerSort,
            onSortChange = { pickerSort = it },
            onToggle = { id ->
                selectedContactIds =
                    if (id in selectedContactIds) selectedContactIds - id
                    else selectedContactIds + id
            },
            // v0.20.4: reset to the default on close (AlertDialog
            // routes back-press and outside-tap through onDismiss
            // too), so the next open never has to swap sorts — the
            // click-time reset below stays as a backup.
            onDismiss = {
                showPeoplePicker = false
                pickerSort = ContactSort.fromName(appSettings.contactSortOrder)
            }
        )
    }

    if (showDeleteConfirm && eventId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete event?") },
            text = { Text("You can undo this for a few seconds.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    existing?.let { onDeleteEvent(it.event, false) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showSeriesDeleteDialog && eventId != null) {
        AlertDialog(
            onDismissRequest = { showSeriesDeleteDialog = false },
            title = { Text("Delete recurring event?") },
            text = {
                Text(
                    "This is one of $seriesInstanceCount events in the series. " +
                        "You can undo this for a few seconds."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSeriesDeleteDialog = false
                    existing?.let { onDeleteEvent(it.event, true) }
                }) { Text("This + future", color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showSeriesDeleteDialog = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showSeriesDeleteDialog = false
                        existing?.let { onDeleteEvent(it.event, false) }
                    }) { Text("This only") }
                }
            }
        )
    }

    if (showRecurrenceDialog) {
        RecurrenceDialog(
            initial = recurrence,
            onConfirm = {
                recurrence = it
                showRecurrenceDialog = false
            },
            onDismiss = { showRecurrenceDialog = false }
        )
    }
}

/** One row of the editor: label + date button + time button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(
    label: String,
    millis: Long,
    onChanged: (Long) -> Unit
) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val dateTime = millis.toLocalDateTime()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) {
            Text(formatDate(millis))
        }
        OutlinedButton(onClick = { pickTime = true }, modifier = Modifier.weight(1f)) {
            Text(formatTime(millis))
        }
    }

    if (pickDate) {
        // DatePicker speaks UTC millis; convert through the UTC helpers so
        // the picked day matches the device timezone exactly.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = dateTime.toLocalDate().toUtcPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        onChanged(
                            picked.utcPickerMillisToLocalDate()
                                .toMillisAt(dateTime.toLocalTime())
                        )
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(
            initialHour = dateTime.hour,
            initialMinute = dateTime.minute
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Pick time") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChanged(dateTime.toLocalDate().toMillisAt(LocalTime.of(state.hour, state.minute)))
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PersonChip(contact: Contact, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        // Tapping the name opens the contact's detail screen; the ✕ still
        // unlinks the contact from this event.
        Text(
            contact.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onOpen)
        )
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

// Milestone 20: no longer private — the journal view reuses this picker
// (same package), so there is exactly one attendee picker in the app.
@Composable
fun PeoplePickerDialog(
    allContacts: List<Contact>,
    selectedIds: Set<Long>,
    sort: ContactSort,
    onSortChange: (ContactSort) -> Unit,
    onToggle: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    // v0.20.7: TextFieldValue state (not plain String) so the keyboard's
    // composing region survives recompositions — same backspace
    // space-eating mechanism as the other converted fields.
    var filter by remember { mutableStateOf(TextFieldValue("")) }
    var showSortMenu by remember { mutableStateOf(false) }
    // v0.19.0: same snap-to-top-on-sort as the Contacts view — only when
    // the picker list was already at the top.
    val listState = rememberLazyListState()
    var snapToTopOnSort by remember { mutableStateOf(false) }
    // v0.20.3: the editor picker opened scrolled down (cause never
    // pinned down statically — the dialog state is fresh per open).
    // Guarantee the top on open regardless.
    LaunchedEffect(Unit) { listState.scrollToItem(0) }
    // v0.20.4: snap to top on any contact-list arrival the user did
    // not cause by changing the sort — this catches the stale-list
    // swap (alphabetical → default order) no matter what positioned
    // the list. A user sort change sets userChangedSort, so the
    // v0.19.0 preserve-position behavior for in-dialog sort changes
    // stands untouched.
    var userChangedSort by remember { mutableStateOf(false) }
    LaunchedEffect(allContacts) {
        if (!userChangedSort) listState.scrollToItem(0)
    }
    LaunchedEffect(sort) {
        if (snapToTopOnSort) {
            snapToTopOnSort = false
            listState.scrollToItem(0)
        }
    }
    val shown = remember(filter.text, allContacts) {
        if (filter.text.isBlank()) allContacts
        else allContacts.filter { it.displayName.contains(filter.text, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link people") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${shown.size} contact${if (shown.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // v0.19.3: menu in a Box with its button, so the popup
                    // anchors to the button — same left-side bug as the
                    // Contacts sort menu (bare Row child anchored to the
                    // whole Row).
                    Box {
                        TextButton(onClick = { showSortMenu = true }) {
                            Text("Sort: ${sort.label} ▾")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            ContactSort.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        snapToTopOnSort =
                                            listState.firstVisibleItemIndex == 0 &&
                                                listState.firstVisibleItemScrollOffset == 0
                                        userChangedSort = true
                                        onSortChange(option)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("Search contacts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.height(320.dp)
                ) {
                    items(shown, key = { it.id }) { contact ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(contact.id) }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = contact.id in selectedIds,
                                onCheckedChange = { onToggle(contact.id) }
                            )
                            Text(contact.displayName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Done") }
        }
    )
}

/**
 * Compact single-line status chip (v0.17.1). Material3 FilterChip's fixed
 * internal padding ellipsized the labels ("Unmar…") at the default text
 * size, so this keeps the same look (tonal selected state, color dot,
 * outline when unselected) with tighter padding. The 48dp touch target
 * is preserved; larger user text sizes may still ellipsize.
 */
@Composable
private fun StatusChip(
    selected: Boolean,
    onClick: () -> Unit,
    dotColor: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent,
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
