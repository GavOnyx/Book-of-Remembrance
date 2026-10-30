package com.calonyx.app.ui.contacts

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
}
