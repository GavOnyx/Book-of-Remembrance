package com.calonyx.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.Contact
import com.calonyx.app.data.EventType
import com.calonyx.app.data.importexport.ImportExportManager.ExportPreview
import com.calonyx.app.data.importexport.ImportExportManager.ExportSpec
import com.calonyx.app.data.importexport.ImportExportManager.IcsImportPreview
import com.calonyx.app.data.importexport.ImportExportManager.VcfImportPreview
import com.calonyx.app.ui.formatDate
import com.calonyx.app.ui.toUtcPickerMillis
import com.calonyx.app.ui.utcPickerMillisToLocalDate
import java.time.LocalDate

/**
 * Settings section: import/export entry points plus the auto-create
 * toggle. File picking/saving is wired up in [SettingsScreen]; this file
 * holds the section UI and the export/import dialogs.
 */
@Composable
fun ImportExportSection(
    autoCreate: Boolean,
    onToggleAutoCreate: (Boolean) -> Unit,
    onExportClick: () -> Unit,
    onJournalClick: () -> Unit,
    onImportEventsClick: () -> Unit,
    onImportContactsClick: () -> Unit
) {
    SectionHeader("Import / Export")
    Text(
        "Export events as .ics and contacts as .vcf — standard formats " +
            "any calendar app can read, with Book of Remembrance details preserved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    OutlinedButton(
        onClick = onExportClick,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Export events…") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onJournalClick,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Export journal (.txt)…") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onImportEventsClick,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Import events (.ics)…") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onImportContactsClick,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Import contacts (.vcf)…") }
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Auto-create contacts for unknown attendees",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = autoCreate, onCheckedChange = onToggleAutoCreate)
    }
    Text(
        "When on, importing events creates contacts for attendees that " +
            "don't match anyone yet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private enum class DatePreset(val label: String) {
    ALL_TIME("All time"),
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
    CUSTOM("Custom")
}

/**
 * Export dialog: three combinable filters (date range, event types,
 * contacts) plus the include-contacts toggle and a live result count.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(
    viewModel: SettingsViewModel,
    eventTypes: List<EventType>,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onConfirm: (ExportSpec) -> Unit,
    /** Title override so the device-share flow can reuse the filter UI. */
    title: String = "Export events",
    /** Confirm-button label override for the same reuse. */
    confirmLabel: String = "Export"
) {
    var preset by remember { mutableStateOf(DatePreset.ALL_TIME) }
    var fromDate by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var toDate by remember { mutableStateOf(LocalDate.now()) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

    val allTypeIds = remember(eventTypes) {
        eventTypes.map { it.id }.toSet() + ExportSpec.NO_TYPE_ID
    }
    var selectedTypeIds by remember(eventTypes) { mutableStateOf(allTypeIds) }
    val allContactIds = remember(contacts) { contacts.map { it.id }.toSet() }
    var selectedContactIds by remember(contacts) { mutableStateOf(allContactIds) }
    var includeContacts by remember { mutableStateOf(true) }
    var includeUnlinkedEvents by remember { mutableStateOf(true) }

    val spec = ExportSpec(
        fromMillis = when (preset) {
            DatePreset.ALL_TIME -> null
            DatePreset.THIS_MONTH -> LocalDate.now().withDayOfMonth(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            DatePreset.THIS_YEAR -> LocalDate.now().withDayOfYear(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            DatePreset.CUSTOM -> fromDate
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        },
        toMillis = when (preset) {
            DatePreset.ALL_TIME -> null
            DatePreset.THIS_MONTH -> LocalDate.now().withDayOfMonth(1).plusMonths(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            DatePreset.THIS_YEAR -> LocalDate.now().withDayOfYear(1).plusYears(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            DatePreset.CUSTOM -> toDate.plusDays(1)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        },
        typeIds = selectedTypeIds,
        contactIds = selectedContactIds,
        includeContacts = includeContacts,
        includeUnlinkedEvents = includeUnlinkedEvents
    )

    var preview by remember { mutableStateOf<ExportPreview?>(null) }
    LaunchedEffect(spec) {
        preview = viewModel.previewExport(spec)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Date range",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatePreset.values().forEach { p ->
                        FilterChip(
                            selected = preset == p,
                            onClick = { preset = p },
                            label = { Text(p.label) }
                        )
                    }
                }
                if (preset == DatePreset.CUSTOM) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { pickFrom = true },
                            modifier = Modifier.weight(1f)
                        ) { Text(formatDate(fromDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())) }
                        OutlinedButton(
                            onClick = { pickTo = true },
                            modifier = Modifier.weight(1f)
                        ) { Text(formatDate(toDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())) }
                    }
                }

                Text(
                    "Event types",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    eventTypes.forEach { t ->
                        FilterChip(
                            selected = t.id in selectedTypeIds,
                            onClick = {
                                selectedTypeIds =
                                    if (t.id in selectedTypeIds) selectedTypeIds - t.id
                                    else selectedTypeIds + t.id
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(t.colorArgb))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(t.name)
                                }
                            }
                        )
                    }
                    FilterChip(
                        selected = ExportSpec.NO_TYPE_ID in selectedTypeIds,
                        onClick = {
                            selectedTypeIds =
                                if (ExportSpec.NO_TYPE_ID in selectedTypeIds)
                                    selectedTypeIds - ExportSpec.NO_TYPE_ID
                                else selectedTypeIds + ExportSpec.NO_TYPE_ID
                        },
                        label = { Text("No type") }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Contacts",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { selectedContactIds = allContactIds }) {
                        Text("All")
                    }
                    TextButton(onClick = { selectedContactIds = emptySet() }) {
                        Text("None")
                    }
                }
                Text(
                    "An event is exported if any linked contacts are checked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    contacts.forEach { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedContactIds =
                                        if (c.id in selectedContactIds) selectedContactIds - c.id
                                        else selectedContactIds + c.id
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = c.id in selectedContactIds,
                                onCheckedChange = {
                                    selectedContactIds =
                                        if (c.id in selectedContactIds) selectedContactIds - c.id
                                        else selectedContactIds + c.id
                                }
                            )
                            Text(
                                c.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Include events with no linked contacts",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = includeUnlinkedEvents,
                        onCheckedChange = { includeUnlinkedEvents = it }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Also export linked contacts (.vcf)",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = includeContacts,
                        onCheckedChange = { includeContacts = it }
                    )
                }

                val p = preview
                // v0.19.2: when the .vcf switch is off no contacts are
                // exported, so the preview counts 0 regardless of links.
                val previewContactCount = if (p != null && includeContacts) p.contacts.size else 0
                Text(
                    if (p == null) "Counting…"
                    else "${p.events.size} event(s), $previewContactCount contact(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(spec) },
                enabled = (preview?.events?.isNotEmpty() == true)
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (pickFrom) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = fromDate.toUtcPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { fromDate = it.utcPickerMillisToLocalDate() }
                    pickFrom = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (pickTo) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = toDate.toUtcPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { pickTo = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { toDate = it.utcPickerMillisToLocalDate() }
                    pickTo = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTo = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

/**
 * Journal export dialog (v0.19.0): date range (default = previous month)
 * + event-type multiselect. Only described events of the chosen types are
 * included; days with none are omitted from the file.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalExportDialog(
    eventTypes: List<EventType>,
    onDismiss: () -> Unit,
    onConfirm: (fromMillis: Long, toMillis: Long, typeIds: Set<Long>) -> Unit
) {
    val zone = remember { java.time.ZoneId.systemDefault() }
    val prevMonth = remember { LocalDate.now().minusMonths(1) }
    var fromDate by remember { mutableStateOf(prevMonth.withDayOfMonth(1)) }
    var toDate by remember {
        mutableStateOf(prevMonth.withDayOfMonth(prevMonth.lengthOfMonth()))
    }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }

    val allTypeIds = remember(eventTypes) {
        eventTypes.map { it.id }.toSet() + ExportSpec.NO_TYPE_ID
    }
    var selectedTypeIds by remember(eventTypes) { mutableStateOf(allTypeIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export journal") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "Date range",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { pickFrom = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(formatDate(fromDate.atStartOfDay(zone).toInstant().toEpochMilli()))
                    }
                    OutlinedButton(
                        onClick = { pickTo = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(formatDate(toDate.atStartOfDay(zone).toInstant().toEpochMilli()))
                    }
                }
                Text(
                    "Only events with a description are included; " +
                        "days with none are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "Event types",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    eventTypes.forEach { t ->
                        FilterChip(
                            selected = t.id in selectedTypeIds,
                            onClick = {
                                selectedTypeIds =
                                    if (t.id in selectedTypeIds) selectedTypeIds - t.id
                                    else selectedTypeIds + t.id
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(t.colorArgb))
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(t.name)
                                }
                            }
                        )
                    }
                    FilterChip(
                        selected = ExportSpec.NO_TYPE_ID in selectedTypeIds,
                        onClick = {
                            selectedTypeIds =
                                if (ExportSpec.NO_TYPE_ID in selectedTypeIds)
                                    selectedTypeIds - ExportSpec.NO_TYPE_ID
                                else selectedTypeIds + ExportSpec.NO_TYPE_ID
                        },
                        label = { Text("No type") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        fromDate.atStartOfDay(zone).toInstant().toEpochMilli(),
                        toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                        selectedTypeIds
                    )
                },
                enabled = !fromDate.isAfter(toDate) && selectedTypeIds.isNotEmpty()
            ) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (pickFrom) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = fromDate.toUtcPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { pickFrom = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { fromDate = it.utcPickerMillisToLocalDate() }
                    pickFrom = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickFrom = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
    if (pickTo) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = toDate.toUtcPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { pickTo = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { toDate = it.utcPickerMillisToLocalDate() }
                    pickTo = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTo = false }) { Text("Cancel") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
fun IcsImportConfirmDialog(
    preview: IcsImportPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import events?") },
        text = {
            Text(
                if (preview.total == 0) "No events found in this file."
                else "Found ${preview.total} event(s). " +
                    if (preview.alreadyImported > 0)
                        "${preview.alreadyImported} already imported — will be skipped."
                    else "None already imported."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = preview.total > preview.alreadyImported
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun VcfImportConfirmDialog(
    preview: VcfImportPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import contacts?") },
        text = {
            Text(
                if (preview.total == 0) "No contacts found in this file."
                else "Found ${preview.total} contact(s). " +
                    if (preview.alreadyImported > 0)
                        "${preview.alreadyImported} already in contacts — will be skipped."
                    else "None already in contacts."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = preview.total > preview.alreadyImported
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
