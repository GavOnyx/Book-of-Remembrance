package com.calonyx.app.data.importexport

import com.calonyx.app.data.Contact
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One event row as carried by an M16 partner-sync delta. Unlike
 * [IcsParser.ParsedEvent] (which expands RRULEs into instances for
 * import), this is strictly one database row: recurring instances keep
 * their own seriesId/recurrenceRule and sync as ordinary fields.
 */
data class SyncEvent(
    val uid: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val description: String?,
    val location: String?,
    /** Event-type NAME (types don't sync; the receiver matches locally). */
    val typeName: String?,
    /** Raw X-CALONYX-STATUS value ("happened"/"didnt_happen"/"unmarked"). */
    val xStatus: String?,
    val updatedAt: Long,
    val seriesId: String?,
    val rrule: String?,
    /** Stable contact UIDs linked to this event. */
    val contactUids: List<String>
)

/**
 * Row-level sync codec (M16). Serializes one event row to exactly one
 * VEVENT — no RRULE expansion, no series collapsing — so the other side
 * reconstructs identical rows. Reuses the M7 line helpers
 * ([unfoldLines], [parseContentLine], [unescapeText], [escapeText],
 * [foldLine]) and the same VEVENT vocabulary as [IcsGenerator], plus two
 * sync-only properties:
 * - X-CALONYX-UPDATED-AT: modification stamp for delta cursors and
 *   last-writer-wins.
 * - X-CALONYX-SERIES-ID: the recurring series UUID (IcsGenerator folds a
 *   series into one VEVENT; sync needs per-row fidelity).
 *
 * Contacts reuse [VcardCodec] directly (it never expands anything).
 */
object SyncCodec {

    private val UTC_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun utc(millis: Long): String = UTC_FMT.format(Instant.ofEpochMilli(millis))

    private fun parseUtc(s: String): Long? = try {
        LocalDateTime.parse(s.trim(), UTC_FMT).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    /**
     * Serializes [events] (each with its linked contacts) as one VCALENDAR
     * containing exactly one VEVENT per row.
     */
    fun eventsToIcs(
        events: List<Pair<Event, List<Contact>>>,
        types: Map<Long, EventType>
    ): String {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(foldLine(s)).append("\r\n")
        }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:${IcsGenerator.PRODID}")
        line("CALSCALE:GREGORIAN")
        for ((event, contacts) in events) {
            val uid = event.icalUid ?: continue // caller ensures UIDs first
            line("BEGIN:VEVENT")
            line("UID:$uid")
            line("DTSTAMP:${utc(System.currentTimeMillis())}")
            line("DTSTART:${utc(event.startTime)}")
            line("DTEND:${utc(event.endTime)}")
            event.recurrenceRule?.takeIf { it.isNotBlank() }?.let {
                line("RRULE:$it")
            }
            event.seriesId?.takeIf { it.isNotBlank() }?.let {
                line("X-CALONYX-SERIES-ID:$it")
            }
            line("SUMMARY:${escapeText(event.title)}")
            event.description?.takeIf { it.isNotBlank() }?.let {
                line("DESCRIPTION:${escapeText(it)}")
            }
            event.location?.takeIf { it.isNotBlank() }?.let {
                line("LOCATION:${escapeText(it)}")
            }
            event.eventTypeId?.let { types[it] }?.let {
                line("X-CALONYX-TYPE:${escapeText(it.name)}")
            }
            line("X-CALONYX-STATUS:${xStatus(event.status)}")
            line("X-CALONYX-UPDATED-AT:${event.updatedAt}")
            for (c in contacts) {
                val cuid = c.vcardUid ?: continue
                val cn = escapeText(c.displayName)
                val target = if (!c.email.isNullOrBlank()) "mailto:${c.email!!.trim()}"
                             else "urn:uuid:$cuid"
                line("ATTENDEE;CN=\"$cn\";X-CALONYX-CONTACT-UID=$cuid:$target")
                line("X-CALONYX-CONTACT-UID:$cuid")
            }
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    private fun xStatus(status: com.calonyx.app.data.EventStatus): String = when (status) {
        com.calonyx.app.data.EventStatus.HAPPENED_RECORDED -> "happened"
        com.calonyx.app.data.EventStatus.DID_NOT_HAPPEN -> "didnt_happen"
        com.calonyx.app.data.EventStatus.UNMARKED -> "unmarked"
    }

    /**
     * Parses sync-delta VEVENTs WITHOUT RRULE expansion: one VEVENT yields
     * exactly one [SyncEvent]. Events missing UID or DTSTART are skipped.
     */
    fun parseEventsIcs(text: String): List<SyncEvent> {
        val out = mutableListOf<SyncEvent>()
        var inEvent = false
        var props = mutableListOf<ContentLine>()
        for (raw in unfoldLines(text)) {
            val line = parseContentLine(raw) ?: continue
            when {
                line.name == "BEGIN" && line.value.equals("VEVENT", ignoreCase = true) -> {
                    inEvent = true
                    props = mutableListOf()
                }
                line.name == "END" && line.value.equals("VEVENT", ignoreCase = true) -> {
                    if (inEvent) buildSyncEvent(props)?.let { out.add(it) }
                    inEvent = false
                }
                inEvent -> props.add(line)
            }
        }
        return out
    }

    private fun buildSyncEvent(props: List<ContentLine>): SyncEvent? {
        val uid = props.firstOrNull { it.name == "UID" }?.value?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val start = props.firstOrNull { it.name == "DTSTART" }?.value
            ?.let(::parseUtc) ?: return null
        val end = props.firstOrNull { it.name == "DTEND" }?.value
            ?.let(::parseUtc) ?: (start + 3_600_000)
        val contactUids = (
            props.filter { it.name == "X-CALONYX-CONTACT-UID" }
                .map { it.value.trim() } +
            props.filter { it.name == "ATTENDEE" }
                .mapNotNull { it.params["X-CALONYX-CONTACT-UID"]?.trim() }
            ).filter { it.isNotEmpty() }.distinct()
        return SyncEvent(
            uid = uid,
            startMillis = start,
            endMillis = end.coerceAtLeast(start),
            title = props.firstOrNull { it.name == "SUMMARY" }?.value
                ?.let(::unescapeText)?.trim().orEmpty(),
            description = props.firstOrNull { it.name == "DESCRIPTION" }?.value
                ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() },
            location = props.firstOrNull { it.name == "LOCATION" }?.value
                ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() },
            typeName = props.firstOrNull { it.name == "X-CALONYX-TYPE" }?.value
                ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() },
            xStatus = props.firstOrNull { it.name == "X-CALONYX-STATUS" }?.value
                ?.trim()?.takeIf { it.isNotEmpty() },
            updatedAt = props.firstOrNull { it.name == "X-CALONYX-UPDATED-AT" }?.value
                ?.trim()?.toLongOrNull() ?: 0L,
            seriesId = props.firstOrNull { it.name == "X-CALONYX-SERIES-ID" }?.value
                ?.trim()?.takeIf { it.isNotEmpty() },
            rrule = props.firstOrNull { it.name == "RRULE" }?.value
                ?.trim()?.takeIf { it.isNotEmpty() },
            contactUids = contactUids
        )
    }
}
