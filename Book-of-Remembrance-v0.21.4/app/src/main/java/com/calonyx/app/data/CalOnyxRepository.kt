package com.calonyx.app.data

import androidx.room.withTransaction
import com.calonyx.app.data.importexport.joinContactNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * In-memory buffer of deleted events plus their contact links, held by
 * the ViewModel so a Snackbar can offer Undo. Never persisted: if the
 * process dies, the delete simply stands.
 */
data class DeletedEvents(
    val events: List<Event>,
    val refs: List<EventContactCrossRef>
)

/** In-memory buffer of a deleted contact plus its event links, for Undo. */
data class DeletedContact(
    val contact: Contact,
    val refs: List<EventContactCrossRef>
)

/** First non-blank value of [pick] across the list, or null. */
private fun List<Contact>.firstNonBlank(pick: (Contact) -> String?): String? =
    firstOrNull { !pick(it).isNullOrBlank() }?.let(pick)

/**
 * Single entry point to CalOnyx's data. Screens observe [Flow]s and call
 * suspend functions; Room handles threading. Also owns first-run seeding of
 * default event types, relationship tags, and the theme palette.
 */
class CalOnyxRepository(private val db: CalOnyxDatabase) {

    // --- Events ---

    fun observeEventsInRange(from: Long, to: Long): Flow<List<Event>> =
        db.eventDao().observeInRange(from, to)

    fun searchEvents(query: String): Flow<List<Event>> =
        db.eventDao().searchByTitle(query)

    fun observeEventWithContacts(id: Long): Flow<EventWithContacts?> =
        db.eventDao().observeWithContacts(id)

    suspend fun saveEvent(event: Event): Long {
        // Central updatedAt stamp (M16): every event write bumps the
        // modification time so partner-sync deltas and last-writer-wins
        // conflicts work. The sync engine uses the raw DAO directly to
        // preserve incoming timestamps. Writing a live row supersedes any
        // tombstone for its UID (e.g. undo after delete).
        val stamped = event.copy(updatedAt = nowMillis())
        stamped.icalUid?.let { db.tombstoneDao().delete(Tombstone.EVENT, it) }
        return db.eventDao().upsert(stamped)
    }

    /**
     * Title fallback chain for the event-editor save path (v0.17.0). An
     * explicit title wins; otherwise the event type's name; otherwise the
     * linked contacts' names, joined the journal way ("and" for two,
     * Oxford comma for three or more) so a fallback title reads like
     * the journal's "with ..." line. Returns null when nothing can
     * stand in — the caller must then require a title. Pure function
     * so every save path applies it identically and no caller can
     * bypass the chain.
     */
    fun resolveEventTitle(
        rawTitle: String,
        typeName: String?,
        contactNames: List<String>
    ): String? {
        val t = rawTitle.trim()
        if (t.isNotEmpty()) return t
        val tn = typeName?.trim().orEmpty()
        if (tn.isNotEmpty()) return tn
        val names = contactNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isNotEmpty()) return joinContactNames(names)
        return null
    }

    /**
     * Non-destructive in-place update (same rationale as [updateContact]).
     * Used when stamping stable export UIDs onto existing events.
     */
    suspend fun updateEvent(event: Event) {
        val stamped = event.copy(updatedAt = nowMillis())
        stamped.icalUid?.let { db.tombstoneDao().delete(Tombstone.EVENT, it) }
        db.eventDao().update(stamped)
    }

    suspend fun deleteEvent(event: Event) {
        db.withTransaction {
            event.icalUid?.let {
                db.tombstoneDao().upsert(Tombstone(Tombstone.EVENT, it, nowMillis()))
            }
            db.eventDao().delete(event)
        }
    }

    /** How many instances a recurring series currently has. */
    suspend fun countSeriesEvents(seriesId: String): Int =
        db.eventDao().countBySeriesId(seriesId)

    /**
     * Deletes a recurring series from [event] onward: every instance with
     * the same seriesId whose startTime is >= [event]'s startTime. Returns
     * the undo buffer like [deleteEventsWithUndo].
     */
    suspend fun deleteSeriesFrom(event: Event): DeletedEvents {
        val sid = event.seriesId ?: return deleteEventsWithUndo(listOf(event))
        val doomed = db.eventDao().getBySeriesId(sid)
            .filter { it.startTime >= event.startTime }
        return deleteEventsWithUndo(doomed)
    }

    // --- Undo support ---

    /**
     * Deletes [events] and returns an in-memory buffer of the rows plus
     * their contact links, so the UI can offer Undo. The buffer is held
     * by the ViewModel and dropped on timeout / navigation.
     *
     * M16: a deletion tombstone is written per event (same transaction)
     * so the delete propagates to sync partners.
     */
    suspend fun deleteEventsWithUndo(events: List<Event>): DeletedEvents {
        if (events.isEmpty()) return DeletedEvents(emptyList(), emptyList())
        val refs = db.eventDao().getRefsForEvents(events.map { it.id })
        val now = nowMillis()
        db.withTransaction {
            events.forEach { e ->
                e.icalUid?.let {
                    db.tombstoneDao().upsert(Tombstone(Tombstone.EVENT, it, now))
                }
                db.eventDao().delete(e)
            }
        }
        return DeletedEvents(events, refs)
    }

    /**
     * Restores a [DeletedEvents] buffer: rows are re-inserted with their
     * original ids (upsert REPLACE keeps explicit ids), then links are
     * re-inserted. Links whose contact vanished meanwhile are dropped so
     * the foreign keys never fail. The restore stamps a fresh updatedAt
     * (it is a modification) and clears the delete tombstones.
     */
    suspend fun restoreDeletedEvents(deleted: DeletedEvents) {
        if (deleted.events.isEmpty()) return
        val now = nowMillis()
        db.withTransaction {
            val liveContactIds = db.contactDao().getAll().map { it.id }.toSet()
            deleted.events.forEach { e ->
                e.icalUid?.let { db.tombstoneDao().delete(Tombstone.EVENT, it) }
                db.eventDao().upsert(e.copy(updatedAt = now))
            }
            db.eventDao().insertRefs(deleted.refs.filter { it.contactId in liveContactIds })
        }
    }

    /**
     * Deletes [contact] and returns an in-memory buffer of the row plus
     * its event links, for Undo. M16: writes a deletion tombstone.
     */
    suspend fun deleteContactWithUndo(contact: Contact): DeletedContact {
        val refs = db.contactDao().getRefsForContact(contact.id)
        db.withTransaction {
            contact.vcardUid?.let {
                db.tombstoneDao().upsert(Tombstone(Tombstone.CONTACT, it, nowMillis()))
            }
            db.contactDao().delete(contact)
        }
        return DeletedContact(contact, refs)
    }

    /** Restores a [DeletedContact] buffer, dropping links to vanished events. */
    suspend fun restoreDeletedContact(deleted: DeletedContact) {
        val now = nowMillis()
        db.withTransaction {
            val liveEventIds = db.eventDao().getAll().map { it.id }.toSet()
            deleted.contact.vcardUid?.let {
                db.tombstoneDao().delete(Tombstone.CONTACT, it)
            }
            db.contactDao().upsert(deleted.contact.copy(updatedAt = now))
            db.eventDao().insertRefs(deleted.refs.filter { it.eventId in liveEventIds })
        }
    }

    // --- Contact merging ---

    /**
     * Merges [absorbedIds] into [survivorId] atomically: their event links
     * are repointed to the survivor (existing survivor links win on
     * conflict), each blank personal field on the survivor is filled from
     * the absorbed contacts (first non-blank wins, in [absorbedIds] order),
     * and the absorbed rows are deleted. The survivor is updated with the
     * non-destructive @Update so its own links are never disturbed.
     */
    suspend fun mergeContacts(survivorId: Long, absorbedIds: List<Long>) {
        if (absorbedIds.isEmpty()) return
        val now = nowMillis()
        db.withTransaction {
            val survivor = db.contactDao().getById(survivorId) ?: return@withTransaction
            val absorbed = absorbedIds.mapNotNull { db.contactDao().getById(it) }
            if (absorbed.isEmpty()) return@withTransaction
            absorbed.forEach { db.contactDao().repointRefs(it.id, survivorId) }
            val merged = survivor.copy(
                phone = survivor.phone.takeUnless { it.isNullOrBlank() }
                    ?: absorbed.firstNonBlank { it.phone },
                email = survivor.email.takeUnless { it.isNullOrBlank() }
                    ?: absorbed.firstNonBlank { it.email },
                address = survivor.address.takeUnless { it.isNullOrBlank() }
                    ?: absorbed.firstNonBlank { it.address },
                notes = survivor.notes.takeUnless { it.isNullOrBlank() }
                    ?: absorbed.firstNonBlank { it.notes },
                relationshipTagId = survivor.relationshipTagId
                    ?: absorbed.firstOrNull { it.relationshipTagId != null }?.relationshipTagId,
                // M16: the merge is a modification for sync purposes.
                updatedAt = now
            )
            db.contactDao().update(merged)
            // M16: absorbed contacts are deletions from sync's perspective.
            absorbed.forEach { a ->
                a.vcardUid?.let {
                    db.tombstoneDao().upsert(Tombstone(Tombstone.CONTACT, it, now))
                }
                // The delete cascade clears any refs the repoint left behind.
                db.contactDao().delete(a)
            }
        }
    }

    /** One-shot full event list — used by the export filter. */
    suspend fun getAllEvents(): List<Event> = db.eventDao().getAll()

    /**
     * Bulk "set relationship" (M15.2 batch action): applies [tagId] (null
     * clears) to every contact in [ids] in a single transaction. Stamps
     * updatedAt like any other modification (M16 sync); no tombstones —
     * these are edits, not deletions.
     */
    suspend fun setRelationshipForContacts(ids: Set<Long>, tagId: Long?) {
        if (ids.isEmpty()) return
        val now = nowMillis()
        db.withTransaction {
            ids.forEach { id ->
                db.contactDao().getById(id)?.let { c ->
                    db.contactDao().update(
                        c.copy(relationshipTagId = tagId, updatedAt = now)
                    )
                }
            }
        }
    }

    /** One-shot event with its linked contacts — used by export. */
    suspend fun getEventWithContacts(id: Long): EventWithContacts? =
        db.eventDao().getWithContacts(id)

    /**
     * Contact display names keyed by event id — used for the timeline tile
     * subtitle ("with Dave, Maria"). One query per call; the caller
     * re-invokes when contacts change so renames propagate.
     */
    suspend fun getContactNamesForEvents(eventIds: List<Long>): Map<Long, List<String>> {
        if (eventIds.isEmpty()) return emptyMap()
        val refs = db.eventDao().getRefsForEvents(eventIds)
        if (refs.isEmpty()) return emptyMap()
        val namesById = db.contactDao().getAll().associate { it.id to it.displayName }
        return refs.groupBy({ it.eventId }, { namesById[it.contactId].orEmpty() })
    }

    /** Finds an event by its stable iCal UID (import dedupe). */
    suspend fun getEventByIcalUid(uid: String): Event? = db.eventDao().getByIcalUid(uid)

    /** How many events currently use [typeId]; used before deleting a type. */
    suspend fun countEventsWithType(typeId: Long): Int =
        db.eventDao().countByTypeId(typeId)

    suspend fun linkContactToEvent(eventId: Long, contactId: Long) {
        db.eventDao().linkContact(EventContactCrossRef(eventId, contactId))
        // M16: link membership is part of the event's sync state.
        db.eventDao().getById(eventId)?.let {
            db.eventDao().update(it.copy(updatedAt = nowMillis()))
        }
    }

    suspend fun unlinkContactFromEvent(eventId: Long, contactId: Long) {
        db.eventDao().unlinkContact(eventId, contactId)
        // M16: link membership is part of the event's sync state.
        db.eventDao().getById(eventId)?.let {
            db.eventDao().update(it.copy(updatedAt = nowMillis()))
        }
    }

    // --- Contacts ---

    fun observeContactsAlphabetical(): Flow<List<Contact>> =
        db.contactDao().observeAllAlphabetical()

    fun observeContactsByRecentInteraction(): Flow<List<Contact>> =
        db.contactDao().observeByRecentInteraction()

    fun observeContactsByInteractionCount(): Flow<List<Contact>> =
        db.contactDao().observeByInteractionCount()

    fun observeContactsByRelationship(tagId: Long): Flow<List<Contact>> =
        db.contactDao().observeByRelationship(tagId)

    fun observeContactsSortedByRelationship(): Flow<List<Contact>> =
        db.contactDao().observeSortedByRelationship()

    /** Contacts sorted per [sort]; used by the contacts list screen. */
    fun observeContactsSorted(sort: ContactSort): Flow<List<Contact>> = when (sort) {
        ContactSort.ALPHABETICAL -> observeContactsAlphabetical()
        ContactSort.RECENT -> observeContactsByRecentInteraction()
        ContactSort.FREQUENT -> observeContactsByInteractionCount()
        ContactSort.RELATIONSHIP -> observeContactsSortedByRelationship()
    }

    fun searchContacts(query: String): Flow<List<Contact>> =
        db.contactDao().searchByName(query)

    fun observeContactWithEvents(id: Long): Flow<ContactWithEvents?> =
        db.contactDao().observeWithEvents(id)

    suspend fun saveContact(contact: Contact): Long {
        // Central updatedAt stamp (M16) — see saveEvent.
        val stamped = contact.copy(updatedAt = nowMillis())
        stamped.vcardUid?.let { db.tombstoneDao().delete(Tombstone.CONTACT, it) }
        return db.contactDao().upsert(stamped)
    }

    /**
     * Non-destructive update for EXISTING contacts. Unlike [saveContact]
     * (upsert = REPLACE = DELETE+INSERT), @Update modifies the row in place
     * so the CASCADE foreign key on event_contact_cross_ref never fires and
     * the contact's event links survive an edit.
     */
    suspend fun updateContact(contact: Contact) {
        val stamped = contact.copy(updatedAt = nowMillis())
        stamped.vcardUid?.let { db.tombstoneDao().delete(Tombstone.CONTACT, it) }
        db.contactDao().update(stamped)
    }

    suspend fun getContact(id: Long): Contact? = db.contactDao().getById(id)

    /** One-shot full contact list — used by the export filter. */
    suspend fun getAllContacts(): List<Contact> = db.contactDao().getAll()

    /** Finds a contact by its stable vCard UID (import dedupe). */
    suspend fun getContactByVcardUid(uid: String): Contact? =
        db.contactDao().getByVcardUid(uid)

    /** Case-insensitive email lookup (linking imported attendees). */
    suspend fun getContactByEmail(email: String): Contact? =
        db.contactDao().getByEmail(email)

    suspend fun getContactByDeviceId(deviceId: Long): Contact? =
        db.contactDao().getByDeviceId(deviceId)

    suspend fun deleteContact(contact: Contact) {
        db.withTransaction {
            contact.vcardUid?.let {
                db.tombstoneDao().upsert(Tombstone(Tombstone.CONTACT, it, nowMillis()))
            }
            db.contactDao().delete(contact)
        }
    }

    // --- Event types ---

    fun observeEventTypes(): Flow<List<EventType>> = db.eventTypeDao().observeAll()

    /**
     * Saves an event type.
     *
     * INVARIANT (v0.18.0 regression guard): existing type rows are updated
     * IN PLACE — never deleted and re-inserted. `events.eventTypeId` is a
     * foreign key with ON DELETE SET NULL, and Room implements
     * `@Insert(onConflict = REPLACE)` as delete+insert, so replacing an
     * existing type silently nulls every event's type (the "editing a type
     * resets its events to None" bug). Fresh types (id == 0) insert normally.
     */
    suspend fun saveEventType(type: EventType): Long {
        if (type.id != 0L && db.eventTypeDao().getById(type.id) != null) {
            db.eventTypeDao().update(type)
            return type.id
        }
        return db.eventTypeDao().upsert(type)
    }

    /**
     * Duplicates events (v0.18.0): each copy is a fresh row (new id, new
     * icalUid) with identical fields and the same linked attendees. A
     * recurring-series instance becomes a standalone event (series link
     * cleared), mirroring the edit/drag detach convention — duplicating an
     * instance never clones the whole series.
     */
    suspend fun duplicateEvents(events: List<Event>) {
        if (events.isEmpty()) return
        db.withTransaction {
            events.forEach { event ->
                val copy = event.copy(
                    id = 0,
                    icalUid = UUID.randomUUID().toString(),
                    seriesId = null,
                    recurrenceRule = null,
                    updatedAt = nowMillis()
                )
                val newId = db.eventDao().upsert(copy)
                val contactIds = db.eventDao()
                    .getRefsForEvents(listOf(event.id))
                    .map { it.contactId }
                if (contactIds.isNotEmpty()) {
                    db.eventDao().insertRefs(contactIds.map { EventContactCrossRef(newId, it) })
                }
            }
        }
    }

    /**
     * Finds an event type by name (case-insensitive) or creates it with
     * [colorArgb]. Used when importing typed events.
     */
    suspend fun findOrCreateEventType(name: String, colorArgb: Int): EventType {
        val clean = name.trim()
        db.eventTypeDao().getByName(clean)?.let { return it }
        val id = db.eventTypeDao().upsert(EventType(name = clean, colorArgb = colorArgb))
        return db.eventTypeDao().getById(id) ?: EventType(id = id, name = clean, colorArgb = colorArgb)
    }

    /** All event types — including seeded defaults — can be deleted. */
    suspend fun deleteEventType(type: EventType) {
        db.eventTypeDao().delete(type)
    }

    // --- Relationship tags ---

    fun observeRelationshipTags(): Flow<List<RelationshipTag>> =
        db.relationshipTagDao().observeAll()

    suspend fun saveRelationshipTag(tag: RelationshipTag): Long =
        db.relationshipTagDao().upsert(tag)

    /**
     * Finds a relationship tag by name (case-insensitive) or creates it.
     * Used when importing tagged contacts.
     */
    suspend fun findOrCreateRelationshipTag(name: String): RelationshipTag {
        val clean = name.trim()
        db.relationshipTagDao().getByName(clean)?.let { return it }
        val id = db.relationshipTagDao().upsert(RelationshipTag(name = clean))
        return db.relationshipTagDao().getById(id)
            ?: RelationshipTag(id = id, name = clean)
    }

    /** All tags — including seeded defaults — can be deleted. */
    suspend fun deleteRelationshipTag(tag: RelationshipTag) {
        db.relationshipTagDao().delete(tag)
    }

    /**
     * Persists a manual tag ordering: the list's positions become the tags'
     * sortOrder values. Called after up/down moves in Settings.
     */
    suspend fun saveTagOrder(ordered: List<RelationshipTag>) {
        ordered.forEachIndexed { index, tag ->
            if (tag.sortOrder != index) {
                db.relationshipTagDao().update(tag.copy(sortOrder = index))
            }
        }
    }

    // --- Theme ---

    fun observeTheme(): Flow<ThemeSettings?> = db.themeSettingsDao().observe()

    suspend fun saveTheme(settings: ThemeSettings) = db.themeSettingsDao().save(settings)

    // --- Themes (v0.21) ---
    //
    // A theme is the 4 appearance colors plus the 10 default-type
    // colors. The current colors (theme row + type rows) are whatever
    // theme is active, possibly with unsaved edits; the saved Custom
    // theme (custom* columns + customColorArgb) only changes on Save.
    // Presets are data, so a later preset (Dark) is one more entry in
    // THEME_PRESETS — no migration, and user-added types are never
    // touched by any of this (they are theme-agnostic).

    /** Applies a built-in preset to the current colors. Durations untouched. */
    suspend fun applyThemePreset(preset: ThemePreset) {
        val current = db.themeSettingsDao().get() ?: return
        val next = current.copy(
            background = preset.background,
            foreground = preset.foreground,
            primary = preset.primary,
            font = preset.font,
            selectedThemeId = preset.id
        )
        if (next != current) db.themeSettingsDao().save(next)
        db.eventTypeDao().observeAll().first()
            .filter { it.isDefault }
            .forEach { type ->
                val color = preset.typeColors[type.name] ?: return@forEach
                if (color != type.colorArgb) {
                    db.eventTypeDao().update(type.copy(colorArgb = color))
                }
            }
    }

    /** Applies the saved Custom theme to the current colors. */
    suspend fun applySavedCustomTheme() {
        val current = db.themeSettingsDao().get() ?: return
        val next = current.copy(
            background = current.customBackground,
            foreground = current.customForeground,
            primary = current.customAccent,
            font = current.customFont,
            selectedThemeId = THEME_CUSTOM
        )
        if (next != current) db.themeSettingsDao().save(next)
        db.eventTypeDao().observeAll().first()
            .filter { it.isDefault }
            .forEach { type ->
                val color = type.customColorArgb ?: return@forEach
                if (color != type.colorArgb) {
                    db.eventTypeDao().update(type.copy(colorArgb = color))
                }
            }
    }

    /** Snapshots the current colors as the saved Custom theme. */
    suspend fun saveCustomThemeSnapshot() {
        val current = db.themeSettingsDao().get() ?: return
        db.themeSettingsDao().save(
            current.copy(
                customBackground = current.background,
                customForeground = current.foreground,
                customAccent = current.primary,
                customFont = current.font,
                selectedThemeId = THEME_CUSTOM
            )
        )
        db.eventTypeDao().observeAll().first()
            .filter { it.isDefault }
            .forEach { type ->
                if (type.customColorArgb != type.colorArgb) {
                    db.eventTypeDao().update(type.copy(customColorArgb = type.colorArgb))
                }
            }
    }

    /**
     * Restores the current colors to the persisted selected theme.
     * Used when Settings is left with unsaved edits (the temporary
     * Custom is discarded) and when Settings is entered (self-healing
     * after a crash mid-edit: current colors must always equal the
     * persisted theme outside an open Settings session). A no-op
     * when current colors already match.
     */
    suspend fun reconcileThemeToPersisted() {
        val current = db.themeSettingsDao().get() ?: return
        when (current.selectedThemeId) {
            THEME_CUSTOM -> applySavedCustomTheme()
            else -> THEME_PRESETS.find { it.id == current.selectedThemeId }
                ?.let { applyThemePreset(it) }
        }
    }

    // --- App settings ---

    /** Emits the current settings; falls back to defaults until seeded. */
    fun observeAppSettings(): Flow<AppSettings> =
        db.appSettingsDao().observe().map { it ?: DEFAULT_APP_SETTINGS }

    suspend fun saveAppSettings(settings: AppSettings) =
        db.appSettingsDao().save(settings.sanitized())

    /** Persists the Home Stats Types|Contacts toggle. */
    suspend fun setStatsMode(mode: StatsMode) {
        val current = db.appSettingsDao().get() ?: DEFAULT_APP_SETTINGS
        saveAppSettings(current.copy(statsMode = mode.name))
    }

    /** All event↔contact links for the given events (Home contact stats). */
    suspend fun getContactRefsForEvents(eventIds: List<Long>): List<EventContactCrossRef> =
        db.eventDao().getRefsForEvents(eventIds)

    /** Flow over all attendee links; re-emits whenever any link changes. */
    fun observeAllRefs(): Flow<List<EventContactCrossRef>> =
        db.eventDao().observeAllRefs()

    // --- M16 partner sync ---

    /** Current time in epoch millis, the source of all updatedAt stamps. */
    private fun nowMillis(): Long = System.currentTimeMillis()

    /**
     * Sync engine only: writes an event WITHOUT touching updatedAt — the
     * incoming record's timestamp is authoritative for last-writer-wins.
     * Superseded tombstones for the UID are cleared.
     */
    suspend fun writeEventForSync(event: Event) {
        event.icalUid?.let { db.tombstoneDao().delete(Tombstone.EVENT, it) }
        if (event.id == 0L) db.eventDao().upsert(event)
        else db.eventDao().update(event)
    }

    /**
     * Sync engine only: writes a contact WITHOUT touching updatedAt.
     * Superseded tombstones for the UID are cleared.
     */
    suspend fun writeContactForSync(contact: Contact) {
        contact.vcardUid?.let { db.tombstoneDao().delete(Tombstone.CONTACT, it) }
        if (contact.id == 0L) db.contactDao().upsert(contact)
        else db.contactDao().update(contact)
    }

    /** Sync engine only: raw row delete (the tombstone is written separately). */
    suspend fun deleteEventRow(event: Event) = db.eventDao().delete(event)
    suspend fun deleteContactRow(contact: Contact) = db.contactDao().delete(contact)

    /** Replace all of an event's contact links (sync apply). */
    suspend fun setEventContactLinks(eventId: Long, contactIds: List<Long>) {
        db.withTransaction {
            val current = db.eventDao().getRefsForEvents(listOf(eventId))
            current.forEach { db.eventDao().unlinkContact(it.eventId, it.contactId) }
            db.eventDao().insertRefs(contactIds.map { EventContactCrossRef(eventId, it) })
        }
    }

    /** Tombstones newer than [since] — the delete half of a sync delta. */
    suspend fun getTombstonesSince(since: Long): List<Tombstone> =
        db.tombstoneDao().getSince(since)

    suspend fun getTombstone(recordType: String, uid: String): Tombstone? =
        db.tombstoneDao().get(recordType, uid)

    suspend fun writeTombstone(tombstone: Tombstone) =
        db.tombstoneDao().upsert(tombstone)

    /** Events changed since [since] — the event half of a sync delta. */
    suspend fun getEventsUpdatedSince(since: Long): List<Event> =
        db.eventDao().getUpdatedSince(since)

    /** Contacts changed since [since] — the contact half of a sync delta. */
    suspend fun getContactsUpdatedSince(since: Long): List<Contact> =
        db.contactDao().getUpdatedSince(since)

    /** Case-insensitive event-type lookup that never creates (sync apply). */
    suspend fun findEventTypeByName(name: String): EventType? =
        db.eventTypeDao().getByName(name.trim())

    /** Case-insensitive tag lookup that never creates (sync apply). */
    suspend fun findTagByName(name: String): RelationshipTag? =
        db.relationshipTagDao().getByName(name.trim())

    // --- Partners ---

    fun observePartners(): Flow<List<Partner>> = db.partnerDao().observeAll()

    suspend fun getPartners(): List<Partner> = db.partnerDao().getAll()

    suspend fun getPartner(uuid: String): Partner? = db.partnerDao().getByUuid(uuid)

    suspend fun savePartner(partner: Partner) = db.partnerDao().upsert(partner)

    suspend fun deletePartner(partner: Partner) = db.partnerDao().delete(partner)

    suspend fun deletePartnerByUuid(uuid: String) {
        db.partnerDao().getByUuid(uuid)?.let { db.partnerDao().delete(it) }
    }

    suspend fun renamePartner(uuid: String, name: String) {
        db.partnerDao().getByUuid(uuid)?.let {
            db.partnerDao().upsert(it.copy(displayName = name))
        }
    }

    suspend fun setPartnerSyncContacts(uuid: String, enabled: Boolean) {
        db.partnerDao().getByUuid(uuid)?.let {
            db.partnerDao().upsert(it.copy(syncContacts = enabled))
        }
    }

    /** Advances a partner's sync cursor (monotonic) and records last-seen IP. */
    suspend fun updatePartnerSyncState(uuid: String, cursor: Long, ip: String?) {
        db.partnerDao().getByUuid(uuid)?.let {
            db.partnerDao().upsert(
                it.copy(
                    lastSyncAt = maxOf(it.lastSyncAt, cursor),
                    lastKnownIp = ip ?: it.lastKnownIp
                )
            )
        }
    }

    /**
     * The per-install stable sync identity. Generated once on first use
     * and never rotated; a fresh install (new database) is a new identity.
     */
    suspend fun getOrCreateDeviceUuid(): String {
        val s = db.appSettingsDao().get() ?: DEFAULT_APP_SETTINGS
        if (s.deviceUuid.isNotBlank()) return s.deviceUuid
        val fresh = UUID.randomUUID().toString()
        saveAppSettings(s.copy(deviceUuid = fresh))
        return fresh
    }

    // --- First-run seeding ---

    /** Inserts defaults only when the tables are still empty. Safe to call on every launch. */
    suspend fun seedDefaultsIfNeeded() {
        if (db.eventTypeDao().count() == 0) {
            // v0.21: a fresh install starts on the Light theme, so the
            // seeded type colors are Light's (Original stays available
            // as a preset). The saved Custom starts as Light too.
            DEFAULT_EVENT_TYPES.forEach { type ->
                val light = LIGHT_THEME_PRESET.typeColors[type.name]
                db.eventTypeDao().upsert(
                    type.copy(
                        colorArgb = light ?: type.colorArgb,
                        customColorArgb = light ?: type.colorArgb
                    )
                )
            }
        }
        if (db.relationshipTagDao().count() == 0) {
            DEFAULT_TAGS.forEach { db.relationshipTagDao().upsert(it) }
        }
        if (db.themeSettingsDao().get() == null) {
            // v0.21: first-run default is Light.
            db.themeSettingsDao().save(
                ThemeSettings(
                    background = LIGHT_THEME_PRESET.background,
                    foreground = LIGHT_THEME_PRESET.foreground,
                    primary = LIGHT_THEME_PRESET.primary,
                    secondary = DEFAULT_THEME.secondary,
                    tertiary = DEFAULT_THEME.tertiary,
                    font = LIGHT_THEME_PRESET.font,
                    selectedThemeId = THEME_LIGHT,
                    customBackground = LIGHT_THEME_PRESET.background,
                    customForeground = LIGHT_THEME_PRESET.foreground,
                    customAccent = LIGHT_THEME_PRESET.primary,
                    customFont = LIGHT_THEME_PRESET.font
                )
            )
        }
        if (db.appSettingsDao().get() == null) {
            db.appSettingsDao().save(DEFAULT_APP_SETTINGS)
        }
    }

    /**
     * One-time data fix (v0.18.0, NOT a schema change — no Room migration):
     * renames the default "Contact" type to "Visit" and adds the new
     * "Errand" default. Guarded so it never touches user-modified data:
     * only renames when a type named "Contact" exists and no "Visit" does
     * (events keep their type via the row id; color and default duration
     * are untouched), and only adds "Errand" when no such type exists.
     * Safe to call on every launch; runs once in practice. Called from
     * MainActivity next to [seedDefaultsIfNeeded].
     */
    suspend fun applyTypeRenamesIfNeeded() {
        val contact = db.eventTypeDao().getByName("Contact")
        if (contact != null && db.eventTypeDao().getByName("Visit") == null) {
            // update() — never upsert/REPLACE here (see saveEventType).
            db.eventTypeDao().update(contact.copy(name = "Visit"))
        }
        if (db.eventTypeDao().getByName("Errand") == null) {
            db.eventTypeDao().upsert(
                EventType(name = "Errand", colorArgb = 0xFF84CC16.toInt(), isDefault = true)
            )
        }
    }

    companion object {
        /** 0xAARRGGBB colors for the ten default event types. */
        val DEFAULT_EVENT_TYPES = listOf(
            EventType(name = "Visit", colorArgb = 0xFF3B82F6.toInt(), isDefault = true),
            EventType(name = "Call", colorArgb = 0xFF22C55E.toInt(), isDefault = true),
            EventType(name = "Text", colorArgb = 0xFF14B8A6.toInt(), isDefault = true),
            EventType(name = "Date", colorArgb = 0xFFEC4899.toInt(), isDefault = true),
            EventType(name = "Appointment", colorArgb = 0xFFF59E0B.toInt(), isDefault = true),
            EventType(name = "Note", colorArgb = 0xFF8B5CF6.toInt(), isDefault = true),
            EventType(name = "Work", colorArgb = 0xFFEF4444.toInt(), isDefault = true),
            EventType(name = "Meal", colorArgb = 0xFFF97316.toInt(), isDefault = true),
            EventType(name = "Travel", colorArgb = 0xFF06B6D4.toInt(), isDefault = true),
            EventType(name = "Errand", colorArgb = 0xFF84CC16.toInt(), isDefault = true)
        )

        val DEFAULT_TAGS = listOf(
            RelationshipTag(name = "family", isDefault = true),
            RelationshipTag(name = "friend", isDefault = true),
            RelationshipTag(name = "co-worker", isDefault = true),
            RelationshipTag(name = "peer", isDefault = true),
            RelationshipTag(name = "stranger", isDefault = true)
        )

        /** Dark "onyx" default palette matching the app's name. */
        val DEFAULT_THEME = ThemeSettings(
            background = 0xFF121212.toInt(),
            foreground = 0xFF1E1E1E.toInt(),
            primary = 0xFFBB86FC.toInt(),
            secondary = 0xFF03DAC6.toInt(),
            tertiary = 0xFFCF6679.toInt(),
            font = 0xFFE0E0E0.toInt(),
            selectedThemeId = THEME_ORIGINAL,
            customBackground = 0xFF121212.toInt(),
            customForeground = 0xFF1E1E1E.toInt(),
            customAccent = 0xFFBB86FC.toInt(),
            customFont = 0xFFE0E0E0.toInt()
        )

        // --- Theme presets (v0.21) ---
        //
        // Stable string ids, never list positions, so a preset added
        // later (Dark) can't shift anyone's stored selection. A preset
        // is the 4 appearance colors plus the default-type colors by
        // type name; durations are never part of a theme.

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_ORIGINAL = "original"
        const val THEME_CUSTOM = "custom"

        /** A built-in theme preset (Custom is not one — its colors live in the DB). */
        data class ThemePreset(
            val id: String,
            val label: String,
            val background: Int,
            val foreground: Int,
            val primary: Int,
            val font: Int,
            /** Default-type colors by type name. */
            val typeColors: Map<String, Int>
        )

        /** Light theme, based on the PMG default theme (colors from Master, 2026-10-29). */
        val LIGHT_THEME_PRESET = ThemePreset(
            id = THEME_LIGHT,
            label = "Light",
            background = 0xFFFCF8F7.toInt(),
            foreground = 0xFFF1EDEC.toInt(),
            primary = 0xFFA7004E.toInt(),
            font = 0xFF1C1817.toInt(),
            typeColors = mapOf(
                "Appointment" to 0xFFFDB910.toInt(),
                "Call" to 0xFF6AB03E.toInt(),
                "Date" to 0xFFF95071.toInt(),
                "Errand" to 0xFF96732F.toInt(),
                "Meal" to 0xFFDE840A.toInt(),
                "Note" to 0xFF878B8C.toInt(),
                "Text" to 0xFF6AB03E.toInt(),
                "Travel" to 0xFF7E083C.toInt(),
                "Visit" to 0xFF6AB03E.toInt(),
                "Work" to 0xFFBE73D8.toInt()
            )
        )

        /** Dark theme, based on the PMG default dark theme (colors from Master, 2026-10-30). */
        val DARK_THEME_PRESET = ThemePreset(
            id = THEME_DARK,
            label = "Dark",
            background = 0xFF111111.toInt(),
            foreground = 0xFF1F1F1F.toInt(),
            primary = 0xFF8F0042.toInt(),
            font = 0xFFF5F3F4.toInt(),
            typeColors = mapOf(
                "Appointment" to 0xFFEDC65D.toInt(),
                "Call" to 0xFF76B281.toInt(),
                "Date" to 0xFFE37385.toInt(),
                "Errand" to 0xFFF2EBE1.toInt(),
                "Meal" to 0xFFE3CFB4.toInt(),
                "Note" to 0xFF898A8A.toInt(),
                "Text" to 0xFF76B281.toInt(),
                "Travel" to 0xFFEDC6D7.toInt(),
                "Visit" to 0xFF76B281.toInt(),
                "Work" to 0xFFE19CDF.toInt()
            )
        )

        /** Original theme: the app's long-standing defaults. */
        val ORIGINAL_THEME_PRESET = ThemePreset(
            id = THEME_ORIGINAL,
            label = "Original",
            background = DEFAULT_THEME.background,
            foreground = DEFAULT_THEME.foreground,
            primary = DEFAULT_THEME.primary,
            font = DEFAULT_THEME.font,
            typeColors = DEFAULT_EVENT_TYPES.associate { it.name to it.colorArgb }
        )

        /** Built-in presets in dropdown order (selection is stored by id, so order is free). */
        val THEME_PRESETS = listOf(LIGHT_THEME_PRESET, DARK_THEME_PRESET, ORIGINAL_THEME_PRESET)

        /** Default behavior settings: full-day calendar, 16h (6am–10pm) fill window. */
        val DEFAULT_APP_SETTINGS = AppSettings(
            calendarStartHour = 0,
            calendarEndHour = 24,
            fillWindowStartHour = 6,
            fillWindowEndHour = 22
        )
    }
}
