package com.calonyx.app.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calonyx.app.BuildConfig
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.ContactSort
import com.calonyx.app.data.Partner
import com.calonyx.app.data.EventType
import com.calonyx.app.data.RelationshipTag
import com.calonyx.app.data.ThemeSettings
import com.calonyx.app.data.availableSwatches
import com.calonyx.app.data.importexport.ImportExportManager.ExportSpec
import com.calonyx.app.data.importexport.ImportExportManager.IcsImportPreview
import com.calonyx.app.data.importexport.ImportExportManager.VcfImportPreview
import com.calonyx.app.ui.components.BackIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** In-flight SAF export: the .ics is saved first, then the .vcf if present. */
private data class PendingExport(
    val icsText: String,
    val vcfText: String?,
    val vcfName: String,
    val eventCount: Int,
    val contactCount: Int
)

/** Which theme color a row edits. Secondary was removed (unused); Tertiary
 * was retired in favor of Accent. DB columns remain, harmless. */
private enum class ThemeField(val label: String) {
    BACKGROUND("Background"),
    FOREGROUND("Foreground"),
    PRIMARY("Accent"),
    FONT("Font")
}

private fun ThemeSettings.colorOf(field: ThemeField): Int = when (field) {
    ThemeField.BACKGROUND -> background
    ThemeField.FOREGROUND -> foreground
    ThemeField.PRIMARY -> primary
    ThemeField.FONT -> font
}

private fun ThemeSettings.withColor(field: ThemeField, argb: Int): ThemeSettings = when (field) {
    ThemeField.BACKGROUND -> copy(background = argb)
    ThemeField.FOREGROUND -> copy(foreground = argb)
    ThemeField.PRIMARY -> copy(primary = argb)
    ThemeField.FONT -> copy(font = argb)
}

/** Shared preset palette for theme colors and event-type colors. */
internal val PRESET_COLORS = listOf(
    0xFFEF4444, 0xFFF97316, 0xFFF59E0B, 0xFFEAB308, 0xFF22C55E,
    0xFF14B8A6, 0xFF03DAC6, 0xFF3B82F6, 0xFF8B5CF6, 0xFFBB86FC,
    0xFFEC4899, 0xFFCF6679, 0xFFFFFFFF, 0xFFE0E0E0, 0xFF9E9E9E,
    0xFF616161, 0xFF1E1E1E, 0xFF121212, 0xFF000000
).map { it.toInt() }

internal fun parseHex(input: String): Int? {
    val clean = input.trim().removePrefix("#")
    if (!clean.matches(Regex("[0-9a-fA-F]{6}"))) return null
    return try {
        (0xFF000000.toInt() or clean.toInt(16))
    } catch (e: NumberFormatException) {
        null
    }
}

private fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private fun formatHour(hour: Int): String {
    val h = hour % 24
    val suffix = if (h < 12) "AM" else "PM"
    val display = if (h % 12 == 0) 12 else h % 12
    return "$display $suffix"
}

/**
 * Accepts a picked file for vCard import: by its display-name extension,
 * or — when the provider supplies no name — by sniffing the content.
 * (The picker can't be filtered by MIME type: Android maps .vcf to
 * "text/x-vcard" while many pickers only honor "text/vcard", graying the
 * files out. So we accept everything and validate here instead.)
 */
private fun isVcfFile(displayName: String?, text: String): Boolean =
    displayName?.endsWith(".vcf", ignoreCase = true) == true ||
        (displayName == null && text.contains("BEGIN:VCARD"))

/** Same approach for iCalendar import. */
private fun isIcsFile(displayName: String?, text: String): Boolean =
    displayName?.endsWith(".ics", ignoreCase = true) == true ||
        (displayName == null && text.contains("BEGIN:VCALENDAR"))

/**
 * Settings screen: theme colors, calendar/fill hour ranges, text size,
 * event-type and relationship-tag management. Everything writes straight
 * to the database and applies live. When shown as a bottom-nav tab,
 * [onBack] is null and no back button is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: (() -> Unit)?,
    contentPadding: PaddingValues,
    onShareClick: () -> Unit,
    onHelpClick: () -> Unit,
    /** One partner → sync screen directly; several → the partner list. */
    onSyncPartnerClick: (List<Partner>) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme by viewModel.theme.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val eventTypes by viewModel.eventTypes.collectAsState()
    val tags by viewModel.relationshipTags.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val partners by viewModel.observePartners().collectAsState(initial = emptyList())
    val t = theme ?: CalOnyxRepository.DEFAULT_THEME
    val themeUnsaved by viewModel.themeUnsaved.collectAsState()

    // v0.21 (themes): entering Settings restores the invariant that
    // current colors equal the persisted theme (self-healing after a
    // crash mid-edit); leaving with unsaved edits discards the
    // temporary Custom and reverts to the persisted theme. The exit
    // goes through the ViewModel so it still runs after this screen
    // is disposed (its own coroutine scope would be cancelled).
    LaunchedEffect(Unit) { viewModel.enterSettingsTheme() }
    DisposableEffect(Unit) {
        onDispose { viewModel.onSettingsExit() }
    }

    var expandedColor by remember { mutableStateOf<ThemeField?>(null) }
    var showThemeMenu by remember { mutableStateOf(false) }
    var showDefaultSortMenu by remember { mutableStateOf(false) }
    var showAddType by remember { mutableStateOf(false) }
    var editType by remember { mutableStateOf<EventType?>(null) }
    var deleteType by remember { mutableStateOf<EventType?>(null) }
    var deleteTypeCount by remember { mutableStateOf(0) }
    var showAddTag by remember { mutableStateOf(false) }
    var editTag by remember { mutableStateOf<RelationshipTag?>(null) }
    var deleteTag by remember { mutableStateOf<RelationshipTag?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Import/export state ---
    var showExportDialog by remember { mutableStateOf(false) }
    var showJournalDialog by remember { mutableStateOf(false) }
    var icsImport by remember { mutableStateOf<Pair<String, IcsImportPreview>?>(null) }
    var vcfImport by remember { mutableStateOf<Pair<String, VcfImportPreview>?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }

    suspend fun writeUri(uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Could not open destination")
        }.isSuccess
    }

    suspend fun readUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    suspend fun displayNameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    val createVcfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri ->
        val req = pendingExport
        pendingExport = null
        if (uri != null && req?.vcfText != null) {
            scope.launch {
                val ok = writeUri(uri, req.vcfText)
                snackbarHostState.showSnackbar(
                    if (ok) "Exported ${req.eventCount} event(s), ${req.contactCount} contact(s)"
                    else "Contacts export failed"
                )
            }
        }
    }
    val createIcsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar")
    ) { uri ->
        val req = pendingExport
        if (uri != null && req != null) {
            scope.launch {
                val ok = writeUri(uri, req.icsText)
                if (ok && req.vcfText != null) {
                    createVcfLauncher.launch(req.vcfName)
                } else {
                    pendingExport = null
                    snackbarHostState.showSnackbar(
                        if (ok) "Exported ${req.eventCount} event(s)"
                        else "Export failed"
                    )
                }
            }
        } else {
            pendingExport = null
        }
    }
    // v0.19.0: journal (.txt) export.
    var pendingJournalText by remember { mutableStateOf<String?>(null) }
    val createJournalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = pendingJournalText
        pendingJournalText = null
        if (uri != null && text != null) {
            scope.launch {
                val ok = writeUri(uri, text)
                snackbarHostState.showSnackbar(
                    if (ok) "Journal exported" else "Journal export failed"
                )
            }
        }
    }
    val openIcsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = readUri(uri)
                val name = displayNameOf(uri)
                when {
                    text == null -> snackbarHostState.showSnackbar("Could not read file")
                    !isIcsFile(name, text) ->
                        snackbarHostState.showSnackbar("Please choose an .ics calendar file")
                    else -> icsImport = text to viewModel.previewIcsImport(text)
                }
            }
        }
    }
    val openVcfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = readUri(uri)
                val name = displayNameOf(uri)
                when {
                    text == null -> snackbarHostState.showSnackbar("Could not read file")
                    !isVcfFile(name, text) ->
                        snackbarHostState.showSnackbar("Please choose a .vcf contacts file")
                    else -> vcfImport = text to viewModel.previewVcfImport(text)
                }
            }
        }
    }

    fun startExport(spec: ExportSpec) {
        scope.launch {
            showExportDialog = false
            val preview = viewModel.previewExport(spec)
            val ics = viewModel.buildExportIcs(preview)
            val vcf =
                if (spec.includeContacts && preview.contacts.isNotEmpty())
                    viewModel.buildExportVcf(preview.contacts) else null
            val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            val icsName = "remembrance-events-$stamp.ics"
            pendingExport = PendingExport(
                icsText = ics,
                vcfText = vcf,
                vcfName = "remembrance-contacts-$stamp.vcf",
                eventCount = preview.events.size,
                contactCount = preview.contacts.size
            )
            createIcsLauncher.launch(icsName)
        }
    }

    /** v0.19.0: builds the journal text, then opens the save picker. */
    fun startJournalExport(fromMillis: Long, toMillis: Long, typeIds: Set<Long>) {
        scope.launch {
            showJournalDialog = false
            // v0.19.1: prepend a UTF-8 BOM so viewers that don't
            // auto-detect UTF-8 (showing "â€“" for the em-dash) render the
            // journal correctly. Journal .txt only — .ics/.vcf are
            // machine-read and must not gain a BOM.
            pendingJournalText = "\uFEFF" + viewModel.buildJournalText(fromMillis, toMillis, typeIds)
            val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            createJournalLauncher.launch("remembrance-journal-$stamp.txt")
        }
    }

    Scaffold(
        modifier = modifier.padding(contentPadding),
        // Edge-to-edge (M14) lets system insets flow into Compose; the
        // status-bar inset already arrives once via the outer Scaffold's
        // contentPadding above, so the inner default would apply it twice
        // and leave a dead gap at the top. Zeroed to restore the pre-M14
        // layout exactly.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // Same double-application via the app bar's own default
                // insets: the offset already comes from the outer padding.
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            BackIcon(tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    "Book of Remembrance",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                OutlinedButton(
                    onClick = onHelpClick,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                ) { Text("Help") }
                // M16: visible only once at least one partner is remembered.
                if (partners.isNotEmpty()) {
                    Button(
                        onClick = { onSyncPartnerClick(partners) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                    ) { Text("Sync with partner") }
                }
            }
            item {
                SectionHeader("Calendar hours")
                Text(
                    "The hours shown on the calendar day timeline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                HourRow(
                    label = "Day starts",
                    hour = settings.calendarStartHour,
                    range = 0..23,
                    onChange = {
                        viewModel.saveAppSettings(settings.copy(calendarStartHour = it))
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                HourRow(
                    label = "Day ends",
                    hour = settings.calendarEndHour,
                    range = 1..24,
                    onChange = {
                        viewModel.saveAppSettings(settings.copy(calendarEndHour = it))
                    }
                )
            }

            item {
                SectionHeader("Day fill window")
                Text(
                    "The hours the Home month view measures each day against.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                HourRow(
                    label = "Window starts",
                    hour = settings.fillWindowStartHour,
                    range = 0..23,
                    onChange = {
                        viewModel.saveAppSettings(settings.copy(fillWindowStartHour = it))
                    }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                HourRow(
                    label = "Window ends",
                    hour = settings.fillWindowEndHour,
                    range = 1..24,
                    onChange = {
                        viewModel.saveAppSettings(settings.copy(fillWindowEndHour = it))
                    }
                )
            }

            item {
                SectionHeader("Stats")
                Text(
                    "How many past days (plus today) the Home stats section looks back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Past days",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        viewModel.saveAppSettings(
                            settings.copy(statsPastDays = (settings.statsPastDays - 1).coerceIn(0, 30))
                        )
                    }) {
                        Text("−", style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        text = settings.statsPastDays.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(72.dp)
                    )
                    TextButton(onClick = {
                        viewModel.saveAppSettings(
                            settings.copy(statsPastDays = (settings.statsPastDays + 1).coerceIn(0, 30))
                        )
                    }) {
                        Text("+", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            item {
                SectionHeader("Text size")
                TextSizeRow(
                    scale = settings.textScale,
                    onScaleChange = { viewModel.saveAppSettings(settings.copy(textScale = it)) }
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // v0.21: Appearance sits just above Event types, and now
            // carries the theme dropdown. The dropdown shows the
            // persisted theme — or Custom while an edit is unsaved
            // (a temporary Custom; Save makes it the saved one).
            // Selecting a preset applies immediately; selecting Custom
            // restores the saved Custom, discarding unsaved edits.
            item {
                SectionHeader("Appearance")
                Text(
                    "Tap a color to change it. Changes apply immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        val displayedId =
                            if (themeUnsaved) CalOnyxRepository.THEME_CUSTOM
                            else t.selectedThemeId
                        val displayedLabel =
                            CalOnyxRepository.THEME_PRESETS.find { it.id == displayedId }?.label
                                ?: "Custom"
                        OutlinedButton(onClick = { showThemeMenu = true }) {
                            Text(displayedLabel)
                        }
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false }
                        ) {
                            CalOnyxRepository.THEME_PRESETS.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.label) },
                                    onClick = {
                                        viewModel.selectTheme(preset.id)
                                        showThemeMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Custom") },
                                onClick = {
                                    viewModel.selectTheme(CalOnyxRepository.THEME_CUSTOM)
                                    showThemeMenu = false
                                }
                            )
                        }
                    }
                }
                if (themeUnsaved) {
                    Button(
                        onClick = { viewModel.saveCustomTheme() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) { Text("Save custom theme") }
                }
            }
            ThemeField.values().forEach { field ->
                item {
                    ColorRow(
                        label = field.label,
                        argb = t.colorOf(field),
                        expanded = expandedColor == field,
                        swatches = availableSwatches(settings, PRESET_COLORS),
                        onToggle = { expandedColor = if (expandedColor == field) null else field },
                        onPick = {
                            viewModel.saveTheme(t.withColor(field, it))
                            viewModel.markThemeEdited()
                        },
                        onAddSwatch = { viewModel.addCustomSwatch(it) },
                        onDeleteSwatch = { viewModel.deleteSwatch(it) }
                    )
                    // v0.19.3: rule between rows, so each row's controls
                    // read as belonging to that row alone.
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            item {
                SectionHeader("Event types")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${eventTypes.size} types",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showAddType = true }) { Text("+ Add type") }
                }
            }
            eventTypes.forEach { type ->
                item {
                    EventTypeRow(
                        type = type,
                        onEdit = { editType = type },
                        onDelete = {
                            scope.launch {
                                deleteTypeCount = viewModel.countEventsWithType(type.id)
                                deleteType = type
                            }
                        }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            item {
                SectionHeader("Relationship tags")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${tags.size} tags",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showAddTag = true }) { Text("+ Add tag") }
                }
            }
            tags.forEachIndexed { index, tag ->
                item {
                    RelationshipTagRow(
                        tag = tag,
                        onEdit = { editTag = tag },
                        onDelete = { deleteTag = tag },
                        onMoveUp = if (index > 0) {
                            { viewModel.moveTag(tags, index, index - 1) }
                        } else null,
                        onMoveDown = if (index < tags.size - 1) {
                            { viewModel.moveTag(tags, index, index + 1) }
                        } else null
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            // v0.19.3: the default contacts sort, editable here. This is
            // the same value the Contacts screen persists when its sort
            // changes, so the two stay in sync and Contacts opens sorted
            // by the chosen default.
            item {
                SectionHeader("Contacts")
                Text(
                    "The order contacts open in. Changing the sort in Contacts updates this too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Default sort",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    // Menu in a Box with its button, so the popup anchors
                    // to the button (same fix as the Contacts sort menu).
                    Box {
                        TextButton(onClick = { showDefaultSortMenu = true }) {
                            Text("${ContactSort.fromName(settings.contactSortOrder).label} ▾")
                        }
                        DropdownMenu(
                            expanded = showDefaultSortMenu,
                            onDismissRequest = { showDefaultSortMenu = false }
                        ) {
                            ContactSort.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        viewModel.saveAppSettings(
                                            settings.copy(contactSortOrder = option.name)
                                        )
                                        showDefaultSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            item {
                ImportExportSection(
                    autoCreate = settings.autoCreateContactsOnImport,
                    onToggleAutoCreate = {
                        viewModel.saveAppSettings(
                            settings.copy(autoCreateContactsOnImport = it)
                        )
                    },
                    onExportClick = { showExportDialog = true },
                    onJournalClick = { showJournalDialog = true },
                    onImportEventsClick = { openIcsLauncher.launch(arrayOf("*/*")) },
                    onImportContactsClick = { openVcfLauncher.launch(arrayOf("*/*")) }
                )
            }

            item {
                SectionHeader("Device sharing")
                Text(
                    "Send events and contacts straight to another device " +
                        "running Remembrance on the same Wi-Fi network — " +
                        "no accounts, no cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedButton(
                    onClick = onShareClick,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Share with another device…") }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showAddType) {
        EventTypeDialog(
            existing = null,
            // v0.21: a brand-new type inherits the current accent color.
            initialColor = t.primary,
            swatches = availableSwatches(settings, PRESET_COLORS),
            onDismiss = { showAddType = false },
            onSave = { name, argb, duration ->
                viewModel.saveEventType(
                    EventType(
                        name = name,
                        colorArgb = argb,
                        defaultDurationMinutes = duration
                    )
                )
                showAddType = false
            }
        )
    }
    editType?.let { type ->
        EventTypeDialog(
            existing = type,
            swatches = availableSwatches(settings, PRESET_COLORS),
            onDismiss = { editType = null },
            onSave = { name, argb, duration ->
                viewModel.saveEventType(
                    type.copy(
                        name = name,
                        colorArgb = argb,
                        defaultDurationMinutes = duration
                    )
                )
                // v0.21: recoloring a default type flips to temporary
                // Custom; name/duration-only edits don't, and custom
                // types are theme-agnostic (their edits save as-is).
                if (type.isDefault && argb != type.colorArgb) {
                    viewModel.markThemeEdited()
                }
                editType = null
            }
        )
    }
    deleteType?.let { type ->
        AlertDialog(
            onDismissRequest = { deleteType = null },
            title = { Text("Delete \"${type.name}\"?") },
            text = {
                Text(
                    if (deleteTypeCount > 0)
                        "$deleteTypeCount event(s) use this type. They will keep their times but lose the type color."
                    else "No events use this type."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEventType(type)
                    deleteType = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteType = null }) { Text("Cancel") }
            }
        )
    }

    if (showAddTag) {
        TagDialog(
            existing = null,
            onDismiss = { showAddTag = false },
            onSave = { name ->
                viewModel.saveRelationshipTag(RelationshipTag(name = name))
                showAddTag = false
            }
        )
    }
    editTag?.let { tag ->
        TagDialog(
            existing = tag,
            onDismiss = { editTag = null },
            onSave = { name ->
                viewModel.saveRelationshipTag(tag.copy(name = name))
                editTag = null
            }
        )
    }
    deleteTag?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleteTag = null },
            title = { Text("Delete \"${tag.name}\"?") },
            text = { Text("Contacts using this tag will become untagged.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRelationshipTag(tag)
                    deleteTag = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTag = null }) { Text("Cancel") }
            }
        )
    }

    if (showExportDialog) {
        ExportDialog(
            viewModel = viewModel,
            eventTypes = eventTypes,
            contacts = contacts,
            onDismiss = { showExportDialog = false },
            onConfirm = ::startExport
        )
    }

    if (showJournalDialog) {
        JournalExportDialog(
            eventTypes = eventTypes,
            onDismiss = { showJournalDialog = false },
            onConfirm = ::startJournalExport
        )
    }

    icsImport?.let { (text, preview) ->
        IcsImportConfirmDialog(
            preview = preview,
            onDismiss = { icsImport = null },
            onConfirm = {
                icsImport = null
                scope.launch {
                    val summary = viewModel.applyIcsImport(
                        text,
                        settings.autoCreateContactsOnImport
                    )
                    val msg = buildString {
                        append("Imported ${summary.eventsImported} event(s)")
                        if (summary.eventsSkipped > 0)
                            append(", skipped ${summary.eventsSkipped} already imported")
                        if (summary.contactsAutoCreated > 0)
                            append(" (${summary.contactsAutoCreated} contact(s) created)")
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            }
        )
    }

    vcfImport?.let { (text, preview) ->
        VcfImportConfirmDialog(
            preview = preview,
            onDismiss = { vcfImport = null },
            onConfirm = {
                vcfImport = null
                scope.launch {
                    val summary = viewModel.applyVcfImport(text)
                    val msg = buildString {
                        append("Imported ${summary.contactsImported} contact(s)")
                        if (summary.contactsSkipped > 0)
                            append(", skipped ${summary.contactsSkipped} already in contacts")
                    }
                    snackbarHostState.showSnackbar(msg)
                }
            }
        )
    }
}

@Composable
internal fun SectionHeader(text: String) {
    // v0.20.0: no rule under the header itself — the dividers between
    // the section's items are what separate the groups (v0.19.3 put a
    // rule here too, which read as too many lines).
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun ColorRow(
    label: String,
    argb: Int,
    expanded: Boolean,
    swatches: List<Int>,
    onToggle: () -> Unit,
    onPick: (Int) -> Unit,
    onAddSwatch: (Int) -> Unit,
    onDeleteSwatch: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                text = toHex(argb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            ColorEditor(
                initialArgb = argb,
                swatches = swatches,
                onPick = onPick,
                onAddSwatch = onAddSwatch,
                onDeleteSwatch = onDeleteSwatch
            )
        }
    }
}

/** Preset swatches plus custom ones; tap picks, long-press deletes, hex + Add saves. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorEditor(
    initialArgb: Int,
    swatches: List<Int>,
    onPick: (Int) -> Unit,
    onAddSwatch: (Int) -> Unit,
    onDeleteSwatch: (Int) -> Unit
) {
    var showWheel by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)) {
        Text(
            "Tap to use · long-press to remove",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        swatches.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(
                                2.dp,
                                if (c == initialArgb) MaterialTheme.colorScheme.primary
                                else Color.Transparent,
                                CircleShape
                            )
                            .combinedClickable(
                                onClick = { onPick(c) },
                                onLongClick = { onDeleteSwatch(c) }
                            )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(onClick = { showWheel = true }) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(initialArgb))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text("Choose color")
        }
        if (showWheel) {
            ColorWheelDialog(
                initialArgb = initialArgb,
                onDismiss = { showWheel = false },
                onConfirm = { argb ->
                    showWheel = false
                    onAddSwatch(argb)
                    onPick(argb)
                }
            )
        }
    }
}

@Composable
private fun HourRow(
    label: String,
    hour: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onChange((hour - 1).coerceIn(range)) }) {
            Text("−", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            text = formatHour(hour),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp)
        )
        TextButton(onClick = { onChange((hour + 1).coerceIn(range)) }) {
            Text("+", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun TextSizeRow(scale: Float, onScaleChange: (Float) -> Unit) {
    var draft by remember(scale) { mutableStateOf(scale) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Size",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(draft * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            valueRange = 0.5f..3.0f,
            onValueChangeFinished = { onScaleChange(draft) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "The quick brown fox jumps over the lazy dog",
            fontSize = 16.sp * draft,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
