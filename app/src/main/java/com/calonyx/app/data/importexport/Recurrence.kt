package com.calonyx.app.data.importexport

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Recurrence frequencies CalOnyx supports (subset of RFC 5545 FREQ). */
enum class RecurFreq { DAILY, WEEKLY, MONTHLY, YEARLY }

/** A parsed RFC 5545 RRULE value. */
data class ParsedRRule(
    val freq: RecurFreq,
    val interval: Int,
    val count: Int?,
    val untilMillis: Long?
)

/**
 * The recurrence choice made in the event editor's Repeat dialog.
 * [freq] null means "does not repeat".
 */
data class RecurrenceChoice(
    val freq: RecurFreq? = null,
    val interval: Int = 1,
    val end: RecurEndChoice = RecurEndChoice.Never
)

sealed interface RecurEndChoice {
    data object Never : RecurEndChoice
    data class After(val count: Int) : RecurEndChoice
    data class Until(val date: LocalDate) : RecurEndChoice
}

/**
 * Builds the RFC 5545 RRULE string for this choice, or null when the
 * event does not repeat.
 */
fun RecurrenceChoice.toRRule(): String? {
    val f = freq ?: return null
    val sb = StringBuilder("FREQ=${f.name};INTERVAL=${interval.coerceAtLeast(1)}")
    when (val e = end) {
        is RecurEndChoice.After -> sb.append(";COUNT=${e.count.coerceAtLeast(1)}")
        is RecurEndChoice.Until -> {
            // End of the picked day, so events on that date are included.
            val untilMillis = e.date.atTime(LocalTime.MAX)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            sb.append(";UNTIL=${Recurrence.formatUntilUtc(untilMillis)}")
        }
        RecurEndChoice.Never -> {}
    }
    return sb.toString()
}

/** Short human summary for the editor's Repeat button, e.g. "Every 2 weeks". */
fun RecurrenceChoice.summary(): String {
    val f = freq ?: return "Does not repeat"
    val n = interval.coerceAtLeast(1)
    val base = when (f) {
        RecurFreq.DAILY -> if (n == 1) "Daily" else "Every $n days"
        RecurFreq.WEEKLY -> if (n == 1) "Weekly" else "Every $n weeks"
        RecurFreq.MONTHLY -> if (n == 1) "Monthly" else "Every $n months"
        RecurFreq.YEARLY -> if (n == 1) "Yearly" else "Every $n years"
    }
    val tail = when (val e = end) {
        is RecurEndChoice.After -> ", ${e.count.coerceAtLeast(1)} times"
        is RecurEndChoice.Until -> ", until " + e.date.format(
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
        )
        RecurEndChoice.Never -> ""
    }
    return base + tail
}

/**
 * Shared RRULE parsing + instance expansion. Used by the .ics importer
 * (milestone 7) and by recurring-event creation (milestone 12) so both
 * honor identical bounds.
 */
object Recurrence {

    /** Safety caps for instance generation. */
    const val MAX_INSTANCES = 500
    const val MAX_DAYS = 730L

    private val DATE_TIME_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val UTC_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun parseRRule(value: String): ParsedRRule? {
        var freq: RecurFreq? = null
        var interval = 1
        var count: Int? = null
        var until: Long? = null
        for (part in value.split(";")) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            when (part.substring(0, eq).uppercase()) {
                "FREQ" -> freq =
                    runCatching { RecurFreq.valueOf(part.substring(eq + 1).uppercase()) }.getOrNull()
                "INTERVAL" ->
                    interval = part.substring(eq + 1).toIntOrNull()?.coerceAtLeast(1) ?: 1
                "COUNT" -> count = part.substring(eq + 1).toIntOrNull()?.coerceAtLeast(1)
                "UNTIL" -> until = parseUntil(part.substring(eq + 1))
            }
        }
        return freq?.let { ParsedRRule(it, interval, count, until) }
    }

    private fun parseUntil(value: String): Long? {
        val v = value.trim()
        return try {
            when {
                v.endsWith("Z") || v.endsWith("z") ->
                    LocalDateTime.parse(v.dropLast(1), DATE_TIME_FMT)
                        .atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
                v.length == 8 ->
                    LocalDate.parse(v, DATE_FMT).atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                else ->
                    LocalDateTime.parse(v, DATE_TIME_FMT).atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Expands [rule] from [start], stepping in [start]'s zone so DST
     * transitions keep the local wall-clock time. Returns
     * (startMillis, endMillis) pairs, earliest first.
     */
    fun expandInstances(
        start: ZonedDateTime,
        durationMillis: Long,
        rule: ParsedRRule,
        maxInstances: Int = MAX_INSTANCES,
        maxDays: Long = MAX_DAYS
    ): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var current = start
        val horizon = start.plusDays(maxDays)
        var n = 0
        while (n < maxInstances) {
            if (rule.count != null && n >= rule.count) break
            val millis = current.toInstant().toEpochMilli()
            if (rule.untilMillis != null && millis > rule.untilMillis) break
            if (current.isAfter(horizon)) break
            out.add(millis to millis + durationMillis)
            current = when (rule.freq) {
                RecurFreq.DAILY -> current.plusDays(rule.interval.toLong())
                RecurFreq.WEEKLY -> current.plusWeeks(rule.interval.toLong())
                RecurFreq.MONTHLY -> current.plusMonths(rule.interval.toLong())
                RecurFreq.YEARLY -> current.plusYears(rule.interval.toLong())
            }
            n++
        }
        return out
    }

    /** Formats epoch millis as a UTC RRULE UNTIL value. */
    fun formatUntilUtc(millis: Long): String =
        UTC_FMT.format(Instant.ofEpochMilli(millis))
}
