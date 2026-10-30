package com.calonyx.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Contact
import com.calonyx.app.data.EventType
import com.calonyx.app.data.RelationshipTag
import com.calonyx.app.data.ThemeSettings
import com.calonyx.app.data.importexport.ImportExportManager
import com.calonyx.app.data.importexport.ImportExportManager.ExportPreview
import com.calonyx.app.data.importexport.ImportExportManager.ExportSpec
import com.calonyx.app.data.importexport.ImportExportManager.IcsImportPreview
import com.calonyx.app.data.importexport.ImportExportManager.ImportSummary
import com.calonyx.app.data.importexport.ImportExportManager.VcfImportPreview
import com.calonyx.app.data.parseSwatchList
import com.calonyx.app.data.swatchListToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen: theme colors, calendar/fill hour ranges, text size,
 * event-type and relationship-tag management. All values live in the
 * database and flow back into the app live; there is no separate
 * apply step.
 */
class SettingsViewModel(internal val repository: CalOnyxRepository) : ViewModel() {

    val theme: StateFlow<ThemeSettings?> =
        repository.observeTheme()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val appSettings: StateFlow<AppSettings> =
        repository.observeAppSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val eventTypes: StateFlow<List<EventType>> =
        repository.observeEventTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relationshipTags: StateFlow<List<RelationshipTag>> =
        repository.observeRelationshipTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contacts: StateFlow<List<Contact>> =
        repository.observeContactsAlphabetical()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Import/export orchestration (parsing, filtering, file building). */
    val importExport = ImportExportManager(repository)

    fun saveTheme(theme: ThemeSettings) {
        viewModelScope.launch { repository.saveTheme(theme) }
    }

    // --- Themes (v0.21) ---
    //
    // The dropdown shows the persisted selected theme — except while
    // an edit is unsaved, when it shows Custom (the temporary custom:
    // the dropdown's Custom entry always means the SAVED Custom, and
    // editing from any theme creates a temporary one that only
    // replaces the saved one on Save). The flag lives here, not in
    // the screen, so leaving Settings can discard unsaved edits even
    // after the screen itself is disposed.
    private val _themeUnsaved = MutableStateFlow(false)
    val themeUnsaved: StateFlow<Boolean> = _themeUnsaved.asStateFlow()

    /** A color edit (appearance or default-type): flips to temporary Custom. */
    fun markThemeEdited() {
        _themeUnsaved.value = true
    }

    /** Dropdown selection: applies the preset, or the saved Custom. Clears unsaved edits. */
    fun selectTheme(id: String) {
        viewModelScope.launch {
            if (!_themeUnsaved.value && theme.value?.selectedThemeId == id) return@launch
            when (id) {
                CalOnyxRepository.THEME_CUSTOM -> repository.applySavedCustomTheme()
                else -> CalOnyxRepository.THEME_PRESETS.find { it.id == id }
                    ?.let { repository.applyThemePreset(it) }
            }
            _themeUnsaved.value = false
        }
    }

    /** Save button: snapshots the current colors as the saved Custom theme. */
    fun saveCustomTheme() {
        viewModelScope.launch {
            repository.saveCustomThemeSnapshot()
            _themeUnsaved.value = false
        }
    }

    /** Settings entry: restores the invariant that current colors equal the persisted theme. */
    fun enterSettingsTheme() {
        viewModelScope.launch { repository.reconcileThemeToPersisted() }
    }

    /** Settings exit: unsaved edits are discarded, reverting to the persisted theme. */
    fun onSettingsExit() {
        viewModelScope.launch {
            if (_themeUnsaved.value) {
                repository.reconcileThemeToPersisted()
                _themeUnsaved.value = false
            }
        }
    }

    fun saveAppSettings(settings: AppSettings) {
        viewModelScope.launch { repository.saveAppSettings(settings) }
    }

    fun saveEventType(type: EventType) {
        viewModelScope.launch { repository.saveEventType(type) }
    }

    fun deleteEventType(type: EventType) {
        viewModelScope.launch { repository.deleteEventType(type) }
    }

    fun saveRelationshipTag(tag: RelationshipTag) {
        viewModelScope.launch { repository.saveRelationshipTag(tag) }
    }

    fun deleteRelationshipTag(tag: RelationshipTag) {
        viewModelScope.launch { repository.deleteRelationshipTag(tag) }
    }

    /** Moves a tag within [current] (manual ordering) and persists the result. */
    fun moveTag(current: List<RelationshipTag>, from: Int, to: Int) {
        if (from == to || from !in current.indices || to !in current.indices) return
        val reordered = current.toMutableList()
        reordered.add(to, reordered.removeAt(from))
        viewModelScope.launch { repository.saveTagOrder(reordered) }
    }

    suspend fun countEventsWithType(typeId: Long): Int =
        repository.countEventsWithType(typeId)

    // --- Import / Export ---

    suspend fun previewExport(spec: ExportSpec): ExportPreview =
        importExport.previewExport(spec)

    suspend fun buildExportIcs(preview: ExportPreview): String =
        importExport.buildExportIcs(preview)

    suspend fun buildExportVcf(contacts: List<Contact>): String =
        importExport.buildExportVcf(contacts)

    suspend fun buildJournalText(
        fromMillis: Long,
        toMillis: Long,
        typeIds: Set<Long>
    ): String = importExport.buildJournalText(fromMillis, toMillis, typeIds)

    suspend fun previewIcsImport(text: String): IcsImportPreview =
        importExport.previewIcsImport(text)

    suspend fun applyIcsImport(text: String, autoCreate: Boolean): ImportSummary =
        importExport.importIcs(text, autoCreate)

    suspend fun previewVcfImport(text: String): VcfImportPreview =
        importExport.previewVcfImport(text)

    suspend fun applyVcfImport(text: String): ImportSummary =
        importExport.importVcf(text)

    // --- M16 partner sync ---

    /** All remembered sync partners, newest first. */
    fun observePartners(): kotlinx.coroutines.flow.Flow<List<com.calonyx.app.data.Partner>> =
        repository.observePartners()

    fun addPartner(partner: com.calonyx.app.data.Partner) {
        viewModelScope.launch { repository.savePartner(partner) }
    }

    fun renamePartner(uuid: String, name: String) {
        viewModelScope.launch { repository.renamePartner(uuid, name) }
    }

    fun removePartner(uuid: String) {
        viewModelScope.launch { repository.deletePartnerByUuid(uuid) }
    }

    fun setPartnerSyncContacts(uuid: String, enabled: Boolean) {
        viewModelScope.launch { repository.setPartnerSyncContacts(uuid, enabled) }
    }

    /** This install's stable identity for pairing; generated once, never rotated. */
    suspend fun getOrCreateDeviceUuid(): String = repository.getOrCreateDeviceUuid()

    /**
     * Pairing continuation, receiver side: the sender transmits SYNC-START,
     * both sides swap cursor lines, and the delta exchange runs on the
     * already code-authenticated socket (cursors are 0 for new partners).
     */
    suspend fun runPairingSyncAsListener(
        socket: java.net.Socket,
        peerUuid: String,
        onPhase: (SyncPhase) -> Unit
    ): SyncResult {
        val inp = socket.getInputStream()
        val out = socket.getOutputStream()
        if (inp.readLineUtf8(32) != "SYNC-START") {
            throw java.io.IOException("The other device didn't start the sync.")
        }
        val partner = repository.getPartner(peerUuid)
            ?: throw java.io.IOException("Partner not found.")
        out.writeLine("CURSOR ${partner.lastSyncAt} ${if (partner.syncContacts) 1 else 0}")
        val peerLine = inp.readLineUtf8(64) ?: "CURSOR 0 1"
        val peerCursor = parseCursorLine(peerLine)
        val syncContacts = partner.syncContacts && peerLine.trim().endsWith("1")
        return exchangeDeltas(
            socket = socket, repo = repository, partner = partner,
            myCursor = partner.lastSyncAt, peerCursor = peerCursor,
            syncContacts = syncContacts, isConnector = false, onPhase = onPhase
        )
    }

    /**
     * Pairing continuation, sender side: transmits SYNC-START, swaps cursor
     * lines, then runs the two-way delta exchange as the connector.
     */
    suspend fun runPairingSyncAsConnector(
        socket: java.net.Socket,
        peerUuid: String,
        onPhase: (SyncPhase) -> Unit
    ): SyncResult {
        val inp = socket.getInputStream()
        val out = socket.getOutputStream()
        val partner = repository.getPartner(peerUuid)
            ?: throw java.io.IOException("Partner not found.")
        out.writeLine("SYNC-START")
        out.writeLine("CURSOR ${partner.lastSyncAt} ${if (partner.syncContacts) 1 else 0}")
        val peerLine = inp.readLineUtf8(64) ?: "CURSOR 0 1"
        val peerCursor = parseCursorLine(peerLine)
        val syncContacts = partner.syncContacts && peerLine.trim().endsWith("1")
        return exchangeDeltas(
            socket = socket, repo = repository, partner = partner,
            myCursor = partner.lastSyncAt, peerCursor = peerCursor,
            syncContacts = syncContacts, isConnector = true, onPhase = onPhase
        )
    }

    private fun parseCursorLine(line: String): Long =
        line.removePrefix("CURSOR").trim().split(' ')
            .getOrNull(0)?.toLongOrNull() ?: 0L

    /** Saves a custom hex color as a persistent palette swatch. */
    fun addCustomSwatch(argb: Int) {
        val s = appSettings.value
        val customs = (parseSwatchList(s.customSwatches) + argb).distinct()
        saveAppSettings(s.copy(customSwatches = swatchListToString(customs)))
    }

    /**
     * Deletes a swatch from the palette: custom swatches are removed from
     * the custom list; presets are recorded as removed for this install.
     */
    fun deleteSwatch(argb: Int) {
        val s = appSettings.value
        val customs = parseSwatchList(s.customSwatches)
        if (argb in customs) {
            saveAppSettings(s.copy(customSwatches = swatchListToString(customs - argb)))
        } else {
            val removed = (parseSwatchList(s.removedSwatches) + argb).distinct()
            saveAppSettings(s.copy(removedSwatches = swatchListToString(removed)))
        }
    }
}
