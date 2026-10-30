package com.calonyx.app.data.importexport

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * One event parsed from an .ics file: a single VEVENT, or one instance of
 * an expanded RRULE.
 */
data class ParsedEvent(
    val uid: String,
    val startMillis: Long,
    val endMillis: Long,
    val title: String,
    val description: String?,
    val location: String?,
    /** Raw STATUS property, e.g. CONFIRMED / TENTATIVE / CANCELLED. */
    val status: String?,
    val attendees: List<ParsedAttendee>,
    /** CalOnyx contact UIDs from X-CALONYX-CONTACT-UID lines. */
    val contactUids: List<String>,
    /** CalOnyx event-type name from X-CALONYX-TYPE. */
    val xType: String?,
    /** CalOnyx event-type color from X-CALONYX-TYPE-COLOR. */
    val xTypeColor: Int?,
    /** CalOnyx status from X-CALONYX-STATUS. */
    val xStatus: String?,
    /**
     * Raw RRULE value when this VEVENT recurs (same string on every
     * expanded instance). Null for one-off events — or when the rule used
     * an unsupported FREQ and the event imported as a single instance.
     */
    val rrule: String?
)

data class ParsedAttendee(val name: String?, val email: String?)

/**
 * Hand-written iCalendar (RFC 5545) parser — no external libraries.
 *
 * Handles: line unfolding, property parameters (TZID, VALUE=DATE, CN),
 * text unescaping, UTC (`...Z`) / TZID-aware / floating datetimes,
 * DURATION as an alternative to DTEND, and RRULE expansion for
 * DAILY/WEEKLY/MONTHLY/YEARLY with INTERVAL/COUNT/UNTIL (via [Recurrence],
 * shared with recurring-event creation so both honor identical bounds).
 *
 * Limitations (documented, not bugs): EXDATE/RDATE are ignored, and only
 * the four basic FREQ values expand — anything else imports as a single
 * event at DTSTART.
 */
object IcsParser {

    private val DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun parse(text: String): List<ParsedEvent> {
        val out = mutableListOf<ParsedEvent>()
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
                    if (inEvent) out.addAll(buildEvents(props))
                    inEvent = false
                }
                inEvent -> props.add(line)
            }
        }
        return out
    }

    // --- datetime handling ---

    private data class ParsedDateTime(val zoned: ZonedDateTime, val allDay: Boolean)

    private fun parseDateTime(value: String, params: Map<String, String>): ParsedDateTime? {
        return try {
            when {
                params["VALUE"] == "DATE" -> {
                    val d = LocalDate.parse(value, DATE_FMT)
                    ParsedDateTime(d.atStartOfDay(ZoneId.systemDefault()), allDay = true)
                }
                value.endsWith("Z") || value.endsWith("z") -> {
                    val ldt = LocalDateTime.parse(value.dropLast(1), DATE_TIME_FMT)
                    ParsedDateTime(ldt.atZone(ZoneOffset.UTC), allDay = false)
                }
                else -> {
                    val ldt = LocalDateTime.parse(value, DATE_TIME_FMT)
                    val zone = params["TZID"]?.let { tzid ->
                        runCatching { ZoneId.of(tzid) }.getOrNull()
                    } ?: ZoneId.systemDefault()
                    ParsedDateTime(ldt.atZone(zone), allDay = false)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Parses ISO-8601 durations like P1DT2H30M or P2W. */
    private fun parseDuration(value: String): Long? {
        val m = Regex(
            "P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?",
            RegexOption.IGNORE_CASE
        ).matchEntire(value.trim()) ?: return null
        val weeks = m.groupValues[1].toLongOrNull() ?: 0
        val days = m.groupValues[2].toLongOrNull() ?: 0
        val hours = m.groupValues[3].toLongOrNull() ?: 0
        val minutes = m.groupValues[4].toLongOrNull() ?: 0
        val seconds = m.groupValues[5].toLongOrNull() ?: 0
        val total = weeks * 7 * 24 * 3600 + days * 24 * 3600 +
            hours * 3600 + minutes * 60 + seconds
        return if (total > 0) total * 1000 else null
    }

    // --- event building ---

    private fun buildEvents(props: List<ContentLine>): List<ParsedEvent> {
        val uid = props.firstOrNull { it.name == "UID" }?.value?.trim()
            .takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString()
        val dtStartLine = props.firstOrNull { it.name == "DTSTART" } ?: return emptyList()
        val start = parseDateTime(dtStartLine.value.trim(), dtStartLine.params) ?: return emptyList()

        val dtEndLine = props.firstOrNull { it.name == "DTEND" }
        val durationLine = props.firstOrNull { it.name == "DURATION" }

        val startMillis = start.zoned.toInstant().toEpochMilli()
        val endMillis = when {
            start.allDay -> {
                // All-day: 00:00–23:59 local. A DATE DTEND is exclusive per RFC.
                val endDate = dtEndLine
                    ?.takeIf { it.params["VALUE"] == "DATE" }
                    ?.let { runCatching { LocalDate.parse(it.value.trim(), DATE_FMT) }.getOrNull() }
                    ?.minusDays(1)
                    ?: start.zoned.toLocalDate()
                endDate.atTime(LocalTime.of(23, 59)).atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            }
            dtEndLine != null ->
                parseDateTime(dtEndLine.value.trim(), dtEndLine.params)
                    ?.zoned?.toInstant()?.toEpochMilli()
                    ?: (startMillis + 3_600_000)
            durationLine != null ->
                parseDuration(durationLine.value.trim())?.let { startMillis + it }
                    ?: (startMillis + 3_600_000)
            else -> startMillis + 3_600_000 // foreign events with no end: 1 hour
        }.coerceAtLeast(startMillis)

        val title = props.firstOrNull { it.name == "SUMMARY" }?.value
            ?.let(::unescapeText)?.trim().orEmpty()
        val description = props.firstOrNull { it.name == "DESCRIPTION" }?.value
            ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
        val location = props.firstOrNull { it.name == "LOCATION" }?.value
            ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
        val status = props.firstOrNull { it.name == "STATUS" }?.value?.trim()
            ?.takeIf { it.isNotEmpty() }

        val attendees = props.filter { it.name == "ATTENDEE" }.map { line ->
            val rawValue = line.value.trim()
            val email = when {
                rawValue.startsWith("mailto:", ignoreCase = true) ->
                    rawValue.substring(7).trim().takeIf { it.isNotEmpty() }
                '@' in rawValue && ' ' !in rawValue -> rawValue
                else -> null
            }
            val name = line.params["CN"]?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
            ParsedAttendee(name = name, email = email)
        }

        val contactUids = props
            .filter { it.name == "X-CALONYX-CONTACT-UID" }
            .map { it.value.trim() }
            .filter { it.isNotEmpty() } +
            props.filter { it.name == "ATTENDEE" }
                .mapNotNull { it.params["X-CALONYX-CONTACT-UID"]?.trim() }
                .filter { it.isNotEmpty() }

        val xType = props.firstOrNull { it.name == "X-CALONYX-TYPE" }?.value
            ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
        val xTypeColor = props.firstOrNull { it.name == "X-CALONYX-TYPE-COLOR" }?.value
            ?.let(::parseColorHex)
        val xStatus = props.firstOrNull { it.name == "X-CALONYX-STATUS" }?.value
            ?.trim()?.takeIf { it.isNotEmpty() }

        val base = ParsedEvent(
            uid = uid,
            startMillis = startMillis,
            endMillis = endMillis,
            title = title,
            description = description,
            location = location,
            status = status,
            attendees = attendees,
            contactUids = contactUids.distinct(),
            xType = xType,
            xTypeColor = xTypeColor,
            xStatus = xStatus,
            rrule = null
        )

        val rruleRaw = props.firstOrNull { it.name == "RRULE" }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }
        val rule = rruleRaw?.let { Recurrence.parseRRule(it) }
        return if (rule != null) {
            expand(base, start.zoned, endMillis - startMillis, rule, rruleRaw)
        } else {
            listOf(base)
        }
    }

    private fun expand(
        base: ParsedEvent,
        start: ZonedDateTime,
        durationMillis: Long,
        rule: ParsedRRule,
        rruleRaw: String?
    ): List<ParsedEvent> =
        Recurrence.expandInstances(start, durationMillis, rule).mapIndexed { n, (s, e) ->
            val uid = if (n == 0) base.uid else "${base.uid}#$n"
            base.copy(uid = uid, startMillis = s, endMillis = e, rrule = rruleRaw)
        }

    /** Parses "#RRGGBB" or "RRGGBB" into 0xAARRGGBB. */
    private fun parseColorHex(raw: String): Int? {
        val clean = raw.trim().removePrefix("#")
        if (!clean.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return runCatching { 0xFF000000.toInt() or clean.toInt(16) }.getOrNull()
    }
}
