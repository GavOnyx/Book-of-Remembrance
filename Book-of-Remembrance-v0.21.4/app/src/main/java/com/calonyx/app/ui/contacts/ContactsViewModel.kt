package com.calonyx.app.ui.contacts

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calonyx.app.data.AppSettings
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Contact
import com.calonyx.app.data.ContactSort
import com.calonyx.app.data.ContactWithEvents
import com.calonyx.app.data.DeletedContact
import com.calonyx.app.data.RelationshipTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of a device-contacts import. */
data class DeviceSyncResult(val imported: Int, val skipped: Int)

/** Outcome of a device-contacts export (v0.21.4). */
data class DeviceExportResult(val exported: Int, val skipped: Int)

/**
 * Contacts tab: searchable, sortable contact list plus the selected
 * contact's event timeline. The sort order persists in [AppSettings].
 */
class ContactsViewModel(private val repository: CalOnyxRepository) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    val sortOrder = MutableStateFlow(ContactSort.ALPHABETICAL)

    /** True once the user has explicitly changed the sort this session. */
    private var sortTouchedByUser = false

    init {
        // Restore the persisted sort order once; a user change always wins
        // over this late-arriving restore.
        viewModelScope.launch {
            val restored = ContactSort.fromName(repository.observeAppSettings().first().contactSortOrder)
            if (!sortTouchedByUser) sortOrder.value = restored
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val contacts: StateFlow<List<Contact>> =
        combine(searchQuery, sortOrder) { q, sort -> q to sort }
            .flatMapLatest { (q, sort) ->
                if (q.isBlank()) repository.observeContactsSorted(sort)
                else repository.searchContacts(q)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relationshipTags: StateFlow<List<RelationshipTag>> =
        repository.observeRelationshipTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Behavior settings (text scale, …) for detail screens. */
    val appSettings: StateFlow<AppSettings> =
        repository.observeAppSettings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val allContactsForPicker: StateFlow<List<Contact>> =
        repository.observeContactsAlphabetical()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedContactId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedContact: StateFlow<ContactWithEvents?> =
        selectedContactId.flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(null)
            else repository.observeContactWithEvents(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectContact(id: Long?) {
        selectedContactId.value = id
    }

    /** Persists the list sort order (also written to [AppSettings]). */
    fun setSortOrder(sort: ContactSort) {
        sortTouchedByUser = true
        if (sortOrder.value == sort) return
        sortOrder.value = sort
        viewModelScope.launch {
            val current = repository.observeAppSettings().first()
            repository.saveAppSettings(current.copy(contactSortOrder = sort.name))
        }
    }

    /** Full upsert from the contact editor; derives displayName from names. */
    fun saveContactFull(
        id: Long,
        firstName: String,
        lastName: String,
        phone: String,
        email: String,
        address: String,
        notes: String,
        relationshipTagId: Long?,
        onSaved: (Long) -> Unit = {}
    ) {
        val display = Contact.displayNameFor(firstName, lastName).ifBlank { return }
        viewModelScope.launch {
            val existing = if (id == 0L) null else repository.getContact(id)
            val contact = Contact(
                id = id,
                displayName = display,
                firstName = firstName.trim().ifBlank { null },
                lastName = lastName.trim().ifBlank { null },
                phone = phone.trim().ifBlank { null },
                email = email.trim().ifBlank { null },
                address = address.trim().ifBlank { null },
                notes = notes.trim().ifBlank { null },
                relationshipTagId = relationshipTagId,
                deviceContactId = existing?.deviceContactId,
                lastSyncedAt = existing?.lastSyncedAt
            )
            // Existing contacts MUST use the non-destructive @Update path:
            // upsert(REPLACE) deletes + re-inserts the row, which fires the
            // CASCADE on event_contact_cross_ref and wipes the contact's
            // event links. New contacts (id == 0) are safe to insert.
            val savedId = if (id == 0L) {
                repository.saveContact(contact)
            } else {
                repository.updateContact(contact)
                id
            }
            onSaved(savedId)
        }
    }

    suspend fun getContact(id: Long): Contact? = repository.getContact(id)

    /** Most recently deleted contact awaiting a possible Undo; null when none. */
    val pendingUndo = MutableStateFlow<DeletedContact?>(null)

    /** Deletes a contact (links cascade) with undo; [onDeleted] runs after. */
    fun deleteContactWithUndo(contact: Contact, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            pendingUndo.value = repository.deleteContactWithUndo(contact)
            onDeleted()
        }
    }

    /** Restores the buffered contact delete (original id and links). */
    fun undoDelete() {
        viewModelScope.launch {
            pendingUndo.value?.let { repository.restoreDeletedContact(it) }
            pendingUndo.value = null
        }
    }

    /** Drops the undo buffer (timeout, dismissal, or navigation away). */
    fun clearUndo() {
        pendingUndo.value = null
    }

    // --- Contact merging ---

    /** Contact ids selected for merging; empty = not in merge mode. */
    val mergeSelection = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleMergeSelection(id: Long) {
        mergeSelection.value =
            if (id in mergeSelection.value) mergeSelection.value - id
            else mergeSelection.value + id
    }

    fun clearMergeSelection() {
        mergeSelection.value = emptySet()
    }

    /**
     * Bulk "set relationship": applies [tagId] (null clears the tag) to
     * every currently selected contact in one transaction, then exits
     * selection mode.
     */
    fun setRelationshipForSelection(tagId: Long?) {
        val ids = mergeSelection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            repository.setRelationshipForContacts(ids, tagId)
            clearMergeSelection()
        }
    }

    /**
     * Merges the selected contacts into [survivorId]. Reports the
     * survivor's name and absorbed count for the confirmation snackbar.
     */
    fun mergeContacts(survivorId: Long, onDone: (survivorName: String, absorbedCount: Int) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            // Same order as the merge dialog's list so the dialog's
            // per-field preview ("first non-blank wins") matches the merge.
            val absorbed = contacts.value
                .filter { it.id in mergeSelection.value && it.id != survivorId }
                .map { it.id }
            if (absorbed.isEmpty()) return@launch
            val survivorName = contacts.value.firstOrNull { it.id == survivorId }?.displayName
                ?: repository.getContact(survivorId)?.displayName
                ?: "contact"
            repository.mergeContacts(survivorId, absorbed)
            mergeSelection.value = emptySet()
            onDone(survivorName, absorbed.size)
        }
    }

    fun tagNameFor(tagId: Long?): String =
        relationshipTags.value.firstOrNull { it.id == tagId }?.name ?: "—"

    /**
     * Imports device contacts (display name, first phone number, first email,
     * note, and postal address) into the local table. Contacts already
     * imported (matched by provider _ID) are skipped. The caller must hold
     * READ_CONTACTS; runs on Dispatchers.IO. The app works fully from local
     * contacts when permission is denied.
     */
    suspend fun syncDeviceContacts(context: Context): DeviceSyncResult =
        withContext(Dispatchers.IO) {
            var imported = 0
            var skipped = 0

            // One grouped query for notes / postal addresses / emails, so we
            // don't issue a query per contact. First non-blank value wins.
            data class Extras(var note: String? = null, var address: String? = null, var email: String? = null)
            val extrasByContact = mutableMapOf<Long, Extras>()
            val dataUri = ContactsContract.Data.CONTENT_URI
            val dataProjection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Note.NOTE,
                ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            val dataSelection = "${ContactsContract.Data.MIMETYPE} IN (?,?,?)"
            val dataArgs = arrayOf(
                ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            )
            context.contentResolver.query(
                dataUri, dataProjection, dataSelection, dataArgs, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val mimeCol = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val noteCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Note.NOTE)
                val addrCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS)
                val emailCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (cursor.moveToNext()) {
                    val deviceId = cursor.getLong(idCol)
                    val extras = extrasByContact.getOrPut(deviceId) { Extras() }
                    when (cursor.getString(mimeCol)) {
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                            if (extras.note.isNullOrBlank())
                                extras.note = cursor.getString(noteCol)?.trim()?.ifBlank { null }
                        }
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                            if (extras.address.isNullOrBlank())
                                extras.address = cursor.getString(addrCol)?.trim()?.ifBlank { null }
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            if (extras.email.isNullOrBlank())
                                extras.email = cursor.getString(emailCol)?.trim()?.ifBlank { null }
                        }
                    }
                }
            }

            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val seen = mutableSetOf<Long>()
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val deviceId = cursor.getLong(idCol)
                    // One row per phone number; keep the first per contact.
                    if (!seen.add(deviceId)) continue
                    val rawName = cursor.getString(nameCol)?.trim().orEmpty()
                    if (rawName.isBlank()) continue
                    if (repository.getContactByDeviceId(deviceId) != null) {
                        skipped++
                        continue
                    }
                    val phone = cursor.getString(numCol)?.trim()
                    val extras = extrasByContact[deviceId]
                    // Naive split: first token → first name, rest → last name.
                    val parts = rawName.split("\\s+".toRegex(), limit = 2)
                    repository.saveContact(
                        Contact(
                            displayName = rawName,
                            firstName = parts.getOrNull(0)?.ifBlank { null },
                            lastName = parts.getOrNull(1)?.ifBlank { null },
                            phone = phone?.ifBlank { null },
                            email = extras?.email,
                            address = extras?.address,
                            notes = extras?.note,
                            deviceContactId = deviceId,
                            lastSyncedAt = System.currentTimeMillis()
                        )
                    )
                    imported++
                }
            }
            DeviceSyncResult(imported, skipped)
        }

    /**
     * v0.21.4: export Remembrance contacts that are not on the phone yet.
     * Candidates are app contacts with no [Contact.deviceContactId]. A
     * candidate that matches a device contact by name (and by phone, when
     * the app contact has one) is treated as already on the phone: it is
     * linked to that device contact instead of exported, so both
     * directions skip it from now on. New device contacts are created in
     * the local device account, and the new device _ID is written back
     * onto the app contact. The caller must hold WRITE_CONTACTS; runs on
     * Dispatchers.IO. (A later phase will also update device contacts
     * that were edited in the app; this version only adds new ones.)
     */
    suspend fun exportNewContactsToDevice(context: Context): DeviceExportResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val now = System.currentTimeMillis()

            // Device contacts by normalized name, each with its phones.
            data class DeviceContact(val id: Long, val name: String, val phones: MutableList<String>)
            val deviceContacts = mutableListOf<DeviceContact>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                val idCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)?.trim().orEmpty()
                    if (name.isBlank()) continue
                    val id = cursor.getLong(idCol)
                    val phone = cursor.getString(numCol).orEmpty()
                    val existing = deviceContacts.firstOrNull { it.id == id }
                    if (existing == null) deviceContacts += DeviceContact(id, name, mutableListOf(phone))
                    else existing.phones += phone
                }
            }

            var exported = 0
            var skipped = 0
            repository.observeContactsAlphabetical().first()
                .filter { it.deviceContactId == null && it.displayName.isNotBlank() }
                .forEach { contact ->
                    val nameKey = contact.displayName.trim().lowercase()
                    val appPhone = contact.phone?.normalizePhone()
                    // Same name, and the same phone when the app contact
                    // has one — that is the same person, already on the
                    // phone. Link it instead of exporting a duplicate.
                    val match = deviceContacts.firstOrNull { device ->
                        device.name.trim().lowercase() == nameKey &&
                            (appPhone.isNullOrBlank() ||
                                device.phones.any { it.normalizePhone() == appPhone })
                    }
                    if (match != null) {
                        repository.saveContact(
                            contact.copy(deviceContactId = match.id, lastSyncedAt = now)
                        )
                        skipped++
                    } else {
                        val deviceId = insertDeviceContact(resolver, contact)
                        if (deviceId != null) {
                            repository.saveContact(
                                contact.copy(deviceContactId = deviceId, lastSyncedAt = now)
                            )
                            deviceContacts += DeviceContact(
                                deviceId, contact.displayName,
                                contact.phone?.let { mutableListOf(it) } ?: mutableListOf()
                            )
                            exported++
                        }
                    }
                }
            DeviceExportResult(exported, skipped)
        }

    /**
     * Phone digits for comparison, last 10 only — so "+1 (555) 123-4567"
     * and "5551234567" match as the same number.
     */
    private fun String.normalizePhone(): String =
        filter { it.isDigit() }.takeLast(10)

    /**
     * Creates one device contact (local device account) from an app
     * contact and returns its ContactsProvider _ID, or null on failure.
     */
    private fun insertDeviceContact(resolver: ContentResolver, contact: Contact): Long? {
        val rawUri = resolver.insert(
            ContactsContract.RawContacts.CONTENT_URI,
            ContentValues().apply {
                // Null account = the local, on-device account.
                putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
            }
        ) ?: return null
        val rawId = ContentUris.parseId(rawUri)
        fun insertData(mime: String, block: ContentValues.() -> Unit) {
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, mime)
                    block()
                }
            )
        }
        insertData(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
            contact.firstName?.let {
                put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, it)
            }
            contact.lastName?.let {
                put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, it)
            }
        }
        contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            insertData(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
        }
        contact.email?.takeIf { it.isNotBlank() }?.let { email ->
            insertData(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
                put(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
            }
        }
        contact.address?.takeIf { it.isNotBlank() }?.let { address ->
            insertData(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE) {
                put(ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, address)
                put(ContactsContract.CommonDataKinds.StructuredPostal.TYPE, ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME)
            }
        }
        contact.notes?.takeIf { it.isNotBlank() }?.let { note ->
            insertData(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE) {
                put(ContactsContract.CommonDataKinds.Note.NOTE, note)
            }
        }
        // Resolve the raw contact back to its aggregate contact _ID.
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts._ID} = ?",
            arrayOf(rawId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID))
            }
        }
        return null
    }
}
