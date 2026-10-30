package com.calonyx.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val zone: ZoneId get() = ZoneId.systemDefault()

/**
 * Material3's DatePicker speaks UTC: [rememberDatePickerState] expects
 * `initialSelectedDateMillis` as millis at midnight UTC, and reports
 * `selectedDateMillis` the same way. These helpers bridge that to the
 * device-timezone LocalDates the rest of the app uses. (Passing
 * device-timezone millis straight through shifted picked dates a day
 * behind for timezones west of UTC.)
 */
/** Millis at midnight UTC on this local date — for DatePicker initial state. */
fun LocalDate.toUtcPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** Interpret DatePicker's UTC millis as a LocalDate. */
fun Long.utcPickerMillisToLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Epoch millis for the start of [date] in the device timezone. */
fun LocalDate.startOfDayMillis(): Long =
    atStartOfDay(zone).toInstant().toEpochMilli()

/** Epoch millis for the end (exclusive) of [date]. */
fun LocalDate.endOfDayMillis(): Long =
    plusDays(1).startOfDayMillis()

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun Long.toLocalDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

fun LocalDateTime.toMillis(): Long =
    atZone(zone).toInstant().toEpochMilli()

fun LocalDate.toMillisAt(time: LocalTime): Long =
    LocalDateTime.of(this, time).toMillis()

/**
 * Epoch millis for [hour] (0..24) on this date in the device timezone.
 * Hour 24 means midnight at the start of the next day.
 */
fun LocalDate.millisAtHour(hour: Int): Long =
    if (hour >= 24) plusDays(1).startOfDayMillis() else toMillisAt(LocalTime.of(hour, 0))

fun formatTime(millis: Long): String =
    millis.toLocalDateTime().toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

fun formatDate(millis: Long): String =
    millis.toLocalDate()
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** v0.19.0: e.g. "Oct 15, 2026, 9:41 PM" in the device locale. */
fun formatDateTime(millis: Long): String =
    millis.toLocalDateTime()
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** e.g. "Tue, Oct 6" for day headers. */
fun formatDayHeader(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))

fun greeting(): String {
    return when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
}
