package com.calonyx.app.data.importexport

import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Contact
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.EventType
import com.calonyx.app.data.EventWithContacts
import com.calonyx.app.data.RelationshipTag
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Orchestrates CalOnyx import/export: filtering + file building for
 * export, parsing + dedupe + matching for import.
 */
class ImportExportManager(private val repository: CalOnyxRepository) {

    // --- export ---

    /**
     * Combinable export filters (ANDed). Null [typeIds]/[contactIds] means
     * "all". [NO_TYPE_ID] in [typeIds] keeps typeless events. The contact
     * checklist uses ANY-semantics: an event passes if any of its linked
     * contacts is checked. [includeUnlinkedEvents] (v0.19.2) controls
     * whether events with zero linked contacts pass at all.
     */
    data class ExportSpec(
        val fromMillis: Long? = null,
        val toMillis: Long? = null,
        val typeIds: Set<Long>? = null,
        val contactIds: Set<Long>? = null,
        val includeContacts: Boolean = true,
        val includeUnlinkedEvents: Boolean = true
    ) {
        companion object {
            const val NO_TYPE_ID = -1L
        }
    }

    data class ExportPreview(
        val events: List<EventWithContacts>,
        val contacts: List<Contact>
    )

    suspend fun previewExport(spec: ExportSpec): ExportPreview {
        var events = repository.getAllEvents()
        val from = spec.fromMillis
        val to = spec.toMillis
        if (from != null || to != null) {
            val lo = from ?: Long.MIN_VALUE
            val hi = to ?: Long.MAX_VALUE
            events = events.filter { it.startTime < hi && it.endTime > lo }
        }
        spec.typeIds?.let { ids ->
            events = events.filter { e ->
                if (e.eventTypeId == null) ExportSpec.NO_TYPE_ID in ids
                else e.eventTypeId in ids
            }
        }
        val withContacts = events.mapNotNull { repository.getEventWithContacts(it.id) }
        val filtered = withContacts.filter { ewc ->
            val contactOk = spec.contactIds == null ||
                ewc.contacts.isEmpty() || ewc.contacts.any { it.id in spec.contactIds }
            // v0.19.2: optionally drop events with no linked contacts.
            val unlinkedOk = spec.includeUnlinkedEvents || ewc.contacts.isNotEmpty()
            contactOk && unlinkedOk
        }
        val contacts = filtered.flatMap { it.contacts }.distinctBy { it.id }
        return ExportPreview(filtered, contacts)
    }

    /** Builds the .ics text; stamps stable UIDs onto rows that lack them. */
    suspend fun buildExportIcs(preview: ExportPreview): String {
        val types: Map<Long, EventType> =
            repository.observeEventTypes().first().associateBy { it.id }
        val ensured = preview.events.map { ewc ->
            val uid = ewc.event.icalUid ?: UUID.randomUUID().toString().also { newUid ->
                repository.updateEvent(ewc.event.copy(icalUid = newUid))
            }
            val contacts = ewc.contacts.map { ensureContactUid(it) }
            ewc.copy(event = ewc.event.copy(icalUid = uid), contacts = contacts)
        }
        // Recurring series export as one VEVENT each (with RRULE); the
        // rest export as plain one-off VEVENTs.
        val singles = ensured.filter { it.event.seriesId == null }
        val series = ensured.filter { it.event.seriesId != null }
            .groupBy { it.event.seriesId!! }.values.toList()
        return IcsGenerator.generate(singles, series, types)
    }

    /** Builds the .vcf text for the preview's contacts. */
    suspend fun buildExportVcf(contacts: List<Contact>): String {
        val tags: Map<Long, String> =
            repository.observeRelationshipTags().first().associate { it.id to it.name }
        val withTags = contacts.map { c ->
            val ensured = ensureContactUid(c)
            ensured to ensured.relationshipTagId?.let { tags[it] }
        }
        return VcardCodec.generate(withTags)
    }

    /**
     * Builds the human-readable journal text (v0.19.0): described events in
     * [fromMillis, toMillis) of the selected types (NO_TYPE_ID keeps
     * typeless ones), grouped by day, earliest first. Recurring series are
     * already materialized as dated rows, so each instance appears under
     * its own date; instances without a description are dropped.
     */
    suspend fun buildJournalText(
        fromMillis: Long,
        toMillis: Long,
        typeIds: Set<Long>
    ): String {
        val types: Map<Long, EventType> =
            repository.observeEventTypes().first().associateBy { it.id }
        val zone = java.time.ZoneId.systemDefault()
        val events = repository.getAllEvents()
            .filter { it.startTime < toMillis && it.endTime > fromMillis }
            .filter { e ->
                if (e.eventTypeId == null) ExportSpec.NO_TYPE_ID in typeIds
                else e.eventTypeId in typeIds
            }
            .filter { !it.description.isNullOrBlank() }
            .sortedBy { it.startTime }
        val entries = events.mapNotNull { e ->
            val ewc = repository.getEventWithContacts(e.id) ?: return@mapNotNull null
            JournalEntry(
                date = java.time.Instant.ofEpochMilli(e.startTime).atZone(zone).toLocalDate(),
                title = e.title,
                description = e.description!!,
                contactNames = ewc.contacts.map { it.displayName },
                typeName = e.eventTypeId?.let { types[it]?.name }
            )
        }
        return formatJournal(entries)
    }

    private suspend fun ensureContactUid(c: Contact): Contact {
        if (c.vcardUid != null) return c
        val uid = UUID.randomUUID().toString()
        repository.updateContact(c.copy(vcardUid = uid))
        return c.copy(vcardUid = uid)
    }

    // --- import ---

    data class IcsImportPreview(val total: Int, val alreadyImported: Int)
    data class VcfImportPreview(val total: Int, val alreadyImported: Int)

    data class ImportSummary(
        val eventsFound: Int = 0,
        val eventsImported: Int = 0,
        val eventsSkipped: Int = 0,
        val contactsFound: Int = 0,
        val contactsImported: Int = 0,
        val contactsSkipped: Int = 0,
        val contactsAutoCreated: Int = 0
    )

    suspend fun previewIcsImport(text: String): IcsImportPreview {
        val parsed = IcsParser.parse(text)
        var skipped = 0
        for (p in parsed) {
            if (p.uid.isNotBlank() && repository.getEventByIcalUid(p.uid) != null) skipped++
        }
        return IcsImportPreview(parsed.size, skipped)
    }

    suspend fun previewVcfImport(text: String): VcfImportPreview {
        val parsed = VcardCodec.parse(text)
        var skipped = 0
        for (p in parsed) {
            val dupe = repository.getContactByVcardUid(p.uid) != null ||
                (p.email != null && repository.getContactByEmail(p.email) != null)
            if (dupe) skipped++
        }
        return VcfImportPreview(parsed.size, skipped)
    }

    /**
     * Imports events from .ics text. Re-importing the same file is safe:
     * events whose UID already exists are skipped. A VEVENT carrying an
     * RRULE becomes a real recurring series (rule + seriesId stored,
     * instances generated) instead of plain unlinked instances.
     */
    suspend fun importIcs(text: String, autoCreateContacts: Boolean): ImportSummary {
        val parsed = IcsParser.parse(text)
        val now = System.currentTimeMillis()
        val typeCache = mutableMapOf<String, EventType>()
        var imported = 0
        var skipped = 0
        var autoCreated = 0

        // Group expanded RRULE instances back into their series. The
        // "#n" suffix is ours (digits only), so a foreign UID that merely
        // ends in "#<digits>" without an RRULE still imports as a single.
        val seriesGroups = linkedMapOf<String, MutableList<ParsedEvent>>()
        val singles = mutableListOf<ParsedEvent>()
        for (p in parsed) {
            if (p.rrule != null) {
                val base = p.uid.replace(Regex("#\\d+$"), "")
                seriesGroups.getOrPut(base) { mutableListOf() }.add(p)
            } else {
                singles.add(p)
            }
        }

        for (p in singles) {
            val r = importOne(p, null, null, typeCache, autoCreateContacts, now)
            imported += r.imported
            skipped += r.skipped
            autoCreated += r.autoCreated
        }
        for ((baseUid, instances) in seriesGroups) {
            // The VEVENT's own UID doubles as the series id, so a second
            // import of the same file is a no-op for the whole series.
            if (repository.countSeriesEvents(baseUid) > 0) {
                skipped += instances.size
                continue
            }
            val rrule = instances.first().rrule
            for (p in instances.sortedBy { it.startMillis }) {
                val r = importOne(p, baseUid, rrule, typeCache, autoCreateContacts, now)
                imported += r.imported
                skipped += r.skipped
                autoCreated += r.autoCreated
            }
        }
        return ImportSummary(
            eventsFound = parsed.size,
            eventsImported = imported,
            eventsSkipped = skipped,
            contactsAutoCreated = autoCreated
        )
    }

    private data class OneImportResult(
        val imported: Int,
        val skipped: Int,
        val autoCreated: Int
    )

    /**
     * Imports one parsed event (one instance of a series when [seriesId]
     * is set). Returns counts for the summary.
     */
    private suspend fun importOne(
        p: ParsedEvent,
        seriesId: String?,
        rrule: String?,
        typeCache: MutableMap<String, EventType>,
        autoCreateContacts: Boolean,
        now: Long
    ): OneImportResult {
        val uid = p.uid.ifBlank { UUID.randomUUID().toString() }
        if (repository.getEventByIcalUid(uid) != null) {
            return OneImportResult(0, 1, 0)
        }
        val typeId = p.xType?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            typeCache.getOrPut(name.lowercase()) {
                repository.findOrCreateEventType(
                    name,
                    p.xTypeColor ?: DEFAULT_IMPORT_TYPE_COLOR
                )
            }.id
        }
        val eventId = repository.saveEvent(
            Event(
                title = p.title.ifBlank { "Imported event" },
                startTime = p.startMillis,
                endTime = p.endMillis.coerceAtLeast(p.startMillis),
                location = p.location,
                description = p.description,
                eventTypeId = typeId,
                status = resolveStatus(p, now),
                icalUid = uid,
                recurrenceRule = rrule,
                seriesId = seriesId
            )
        )
        val contactIds = mutableSetOf<Long>()
        var autoCreated = 0
        for (cuid in p.contactUids) {
            repository.getContactByVcardUid(cuid)?.let { contactIds.add(it.id) }
        }
        for (a in p.attendees) {
            val existing = a.email?.let { repository.getContactByEmail(it) }
            if (existing != null) {
                contactIds.add(existing.id)
            } else if (autoCreateContacts &&
                (!a.name.isNullOrBlank() || !a.email.isNullOrBlank())
            ) {
                val name = a.name?.trim().takeUnless { it.isNullOrBlank() }
                val display = name ?: a.email!!.trim()
                // Same fallback as the vCard parser: an attendee CN is only
                // a display name, so split it for the editor's name fields.
                // (Skipped when the display is just an email address.)
                val (autoFirst, autoLast) =
                    name?.let { splitDisplayName(it) } ?: (null to null)
                val newId = repository.saveContact(
                    Contact(
                        displayName = display,
                        firstName = autoFirst,
                        lastName = autoLast,
                        email = a.email?.trim(),
                        vcardUid = UUID.randomUUID().toString()
                    )
                )
                autoCreated++
                contactIds.add(newId)
            }
        }
        contactIds.forEach { repository.linkContactToEvent(eventId, it) }
        return OneImportResult(1, 0, autoCreated)
    }

    /** Imports contacts from .vcf text; duplicates (by UID or email) are skipped. */
    suspend fun importVcf(text: String): ImportSummary {
        val parsed = VcardCodec.parse(text)
        var imported = 0
        var skipped = 0
        val tagCache = mutableMapOf<String, RelationshipTag>()

        for (p in parsed) {
            val dupe = repository.getContactByVcardUid(p.uid) != null ||
                (p.email != null && repository.getContactByEmail(p.email) != null)
            if (dupe) {
                skipped++
                continue
            }
            val tagId = p.relationship?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                tagCache.getOrPut(name.lowercase()) {
                    repository.findOrCreateRelationshipTag(name)
                }.id
            }
            repository.saveContact(
                Contact(
                    displayName = p.displayName,
                    firstName = p.firstName,
                    lastName = p.lastName,
                    phone = p.phone,
                    email = p.email,
                    address = p.address,
                    notes = p.notes,
                    relationshipTagId = tagId,
                    vcardUid = p.uid
                )
            )
            imported++
        }
        return ImportSummary(
            contactsFound = parsed.size,
            contactsImported = imported,
            contactsSkipped = skipped
        )
    }

    /**
     * Status resolution (Master's rules): CalOnyx's own X-CALONYX-STATUS
     * wins; otherwise future events are always unmarked; past foreign
     * events map CONFIRMED→happened, CANCELLED→didn't happen, everything
     * else→unmarked.
     */
    private fun resolveStatus(p: ParsedEvent, now: Long): EventStatus {
        p.xStatus?.let {
            return when (it.trim().lowercase()) {
                "happened" -> EventStatus.HAPPENED_RECORDED
                "didnt_happen", "didn't_happen", "didnothappen" ->
                    EventStatus.DID_NOT_HAPPEN
                else -> EventStatus.UNMARKED
            }
        }
        if (p.endMillis > now) return EventStatus.UNMARKED
        return when (p.status?.trim()?.uppercase()) {
            "CONFIRMED" -> EventStatus.HAPPENED_RECORDED
            "CANCELLED" -> EventStatus.DID_NOT_HAPPEN
            else -> EventStatus.UNMARKED
        }
    }

    companion object {
        /** Fallback color for imported event types that carry no color. */
        const val DEFAULT_IMPORT_TYPE_COLOR = 0xFF3B82F6.toInt()
    }
}
