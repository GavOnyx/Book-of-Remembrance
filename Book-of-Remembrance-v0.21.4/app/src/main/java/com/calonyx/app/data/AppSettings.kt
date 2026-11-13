package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Contacts-list sort orders. Persisted by name in [AppSettings.contactSortOrder]. */
enum class ContactSort(val label: String) {
    ALPHABETICAL("A–Z"),
    RECENT("Recent"),
    FREQUENT("Frequent"),
    RELATIONSHIP("Relationship");

    companion object {
        fun fromName(name: String?): ContactSort =
            values().firstOrNull { it.name == name } ?: ALPHABETICAL
    }
}

/**
 * User-configurable app behavior. Stored as a singleton row (id = 1).
 * There is no settings UI yet — these values are seeded with defaults and
 * every screen reads them from [CalOnyxRepository.observeAppSettings], so a
 * future settings screen only needs to write new values here.
 *
 * Hours are whole hours on a 24h clock: [calendarStartHour, calendarEndHour)
 * is the visible range of the calendar day timeline, and
 * [fillWindowStartHour, fillWindowEndHour) is the window the Home month
 * fill-% view measures each day against.
 *
 * [textScale] multiplies body text sizes on detail screens; the settings
 * screen (milestone 6) will let the user adjust it.
 */
@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val calendarStartHour: Int = 0,
    val calendarEndHour: Int = 24,
    val fillWindowStartHour: Int = 6,
    val fillWindowEndHour: Int = 22,
    /** Persisted contacts-list sort, one of [ContactSort] names. */
    val contactSortOrder: String = ContactSort.ALPHABETICAL.name,
    /** Multiplier for text sizes app-wide (applied to the theme Typography). */
    val textScale: Float = 1f,
    /** User-added color swatches, comma-separated ARGB ints. */
    val customSwatches: String = "",
    /** Preset swatch colors the user deleted, comma-separated ARGB ints. */
    val removedSwatches: String = "",
    /** Auto-create contacts for unknown attendees/imported people. */
    val autoCreateContactsOnImport: Boolean = true,
    /** Stats section: how many past days (0–30) to include alongside today. */
    val statsPastDays: Int = 7,
    /** Stats section view, one of [StatsMode] names. */
    val statsMode: String = StatsMode.TYPE.name,
    /**
     * Stable per-install UUID for M16 partner sync identity. Generated
     * once on first run (see repository.getOrCreateDeviceUuid) and never
     * rotated — a fresh install is a new identity. Blank until generated.
     */
    val deviceUuid: String = "",
    /**
     * Milestone 20: calendar day-view mode. False = the timeline cascade,
     * true = the journal card view. Toggled by the calendar top-bar
     * button (not a Settings row); persists across leaving the tab and
     * across restarts, so the calendar reopens in the mode it was left in.
     */
    val calendarJournalMode: Boolean = false
) {
    /** Clamps every field into a sane range; used before persisting. */
    fun sanitized(): AppSettings {        val calStart = calendarStartHour.coerceIn(0, 23)
        val calEnd = calendarEndHour.coerceIn(calStart + 1, 24)
        val fillStart = fillWindowStartHour.coerceIn(0, 23)
        val fillEnd = fillWindowEndHour.coerceIn(fillStart + 1, 24)
        return copy(
            calendarStartHour = calStart,
            calendarEndHour = calEnd,
            fillWindowStartHour = fillStart,
            fillWindowEndHour = fillEnd,
            contactSortOrder = ContactSort.fromName(contactSortOrder).name,
            textScale = textScale.coerceIn(0.5f, 3f),
            statsPastDays = statsPastDays.coerceIn(0, 30),
            statsMode = StatsMode.fromName(statsMode).name
        )
    }
}

/** Home Stats section view. Persisted by name in [AppSettings.statsMode]. */
enum class StatsMode(val label: String) {
    TYPE("Types"),
    CONTACT("Contacts");

    companion object {
        fun fromName(name: String?): StatsMode =
            values().firstOrNull { it.name == name } ?: TYPE
    }
}

/** Parses a comma-separated ARGB-int list as stored by [AppSettings]. */
fun parseSwatchList(raw: String): List<Int> =
    raw.split(",").mapNotNull { it.trim().toIntOrNull() }

/** Serializes ARGB ints for [AppSettings] swatch columns. */
fun swatchListToString(colors: List<Int>): String = colors.joinToString(",")

/**
 * The effective color-picker palette: presets minus user-removed ones,
 * plus user-added custom swatches.
 */
fun availableSwatches(settings: AppSettings, presets: List<Int>): List<Int> {
    val removed = parseSwatchList(settings.removedSwatches).toSet()
    return (presets.filterNot { it in removed } + parseSwatchList(settings.customSwatches))
        .distinct()
}
