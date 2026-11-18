package com.calonyx.app.data.importexport

import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.EventType
import com.calonyx.app.data.EventWithContacts
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes CalOnyx events as RFC 5545 iCalendar text.
 *
 * CalOnyx-only data travels in X- properties so a CalOnyx re-import
 * restores everything exactly, while foreign calendar apps still read the
 * standard fields (SUMMARY, DTSTART/DTEND, LOCATION, ATTENDEE, ...).
 */
object IcsGenerator {

    const val PRODID = "-//CalOnyx//CalOnyx//EN"

    private val UTC_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun utc(millis: Long): String = UTC_FMT.format(Instant.ofEpochMilli(millis))

    private fun xStatus(status: EventStatus): String = when (status) {
        EventStatus.HAPPENED_RECORDED -> "happened"
        EventStatus.DID_NOT_HAPPEN -> "didnt_happen"
        EventStatus.UNMARKED -> "unmarked"
    }

    /**
     * @param events one-off events with their linked contacts; every event
     *   must already carry a non-null [com.calonyx.app.data.Event.icalUid]
     *   and every contact a non-null
     *   [com.calonyx.app.data.Contact.vcardUid] (the manager ensures this).
     * @param series recurring series; each list holds one series' instances
     *   (earliest first is not required — they are sorted here). A series
     *   exports as a SINGLE VEVENT carrying its RRULE, so foreign apps see
     *   a proper recurring event and CalOnyx re-import restores the series.
     */
    fun generate(
        events: List<EventWithContacts>,
        series: Collection<List<EventWithContacts>>,
        types: Map<Long, EventType>
    ): String {
        val now = utc(System.currentTimeMillis())
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(foldLine(s)).append("\r\n")
        }
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:$PRODID")
        line("CALSCALE:GREGORIAN")
        for ((event, contacts) in events) {
            val uid = event.icalUid ?: continue
            writeEvent(
                sb = sb,
                uid = uid,
                dtStart = event.startTime,
                dtEnd = event.endTime,
                rrule = null,
                event = event,
                contacts = contacts,
                types = types,
                now = now
            )
        }
        for (instances in series) {
            val ordered = instances.sortedBy { it.event.startTime }
            val first = ordered.firstOrNull() ?: continue
            val seriesId = first.event.seriesId ?: continue
            val rrule = first.event.recurrenceRule ?: continue
            // Union of contacts across instances (they're usually identical).
            val contacts = ordered.flatMap { it.contacts }.distinctBy { it.id }
            writeEvent(
                sb = sb,
                uid = seriesId,
                dtStart = first.event.startTime,
                dtEnd = first.event.endTime,
                rrule = rrule,
                event = first.event,
                contacts = contacts,
                types = types,
                now = now
            )
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    /**
     * Writes one VEVENT. Series use the earliest instance's fields; if
     * instances diverged (e.g. one was marked happened), the export keeps
     * the first instance's values — a documented limitation.
     */
    private fun writeEvent(
        sb: StringBuilder,
        uid: String,
        dtStart: Long,
        dtEnd: Long,
        rrule: String?,
        event: com.calonyx.app.data.Event,
        contacts: List<com.calonyx.app.data.Contact>,
        types: Map<Long, EventType>,
        now: String
    ) {
        fun line(s: String) {
            sb.append(foldLine(s)).append("\r\n")
        }
        line("BEGIN:VEVENT")
        line("UID:$uid")
        line("DTSTAMP:$now")
        line("DTSTART:${utc(dtStart)}")
        line("DTEND:${utc(dtEnd)}")
        rrule?.let { line("RRULE:$it") }
        line("SUMMARY:${escapeText(event.title)}")
        event.description?.takeIf { it.isNotBlank() }?.let {
            line("DESCRIPTION:${escapeText(it)}")
        }
        event.location?.takeIf { it.isNotBlank() }?.let {
            line("LOCATION:${escapeText(it)}")
        }
        val type = event.eventTypeId?.let { types[it] }
        type?.let {
            line("X-CALONYX-TYPE:${escapeText(it.name)}")
            line("X-CALONYX-TYPE-COLOR:#%06X".format(it.colorArgb and 0xFFFFFF))
        }
        line("X-CALONYX-STATUS:${xStatus(event.status)}")
        // M16: modification stamp for partner-sync deltas and
        // last-writer-wins. Unknown X- properties are ignored by older
        // parsers, so this is backward compatible.
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
}
