package com.calonyx.app.data.importexport

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One journal line's worth of data: a described event instance on a date.
 * [typeName] is null for typeless events.
 */
data class JournalEntry(
    val date: LocalDate,
    val title: String,
    val description: String,
    val contactNames: List<String>,
    val typeName: String?
)

/**
 * Formats journal entries as human-readable plain text (v0.19.1).
 *
 * Days are headed by `MMMM d, yyyy` (e.g. "October 12, 2026"); days with
 * no entries are omitted by the caller. Each entry is one indented line:
 * `{Title}: {description}`, plus ` — {Type} with {contacts}.` when a
 * type and/or contacts are present. Contact lists use the Oxford comma
 * for 3+ names; "with" is always lowercase. Type-only entries get no
 * trailing period; entries with neither type nor contacts are the bare
 * description. Descriptions are kept verbatim (trailing whitespace
 * trimmed so entries can't split lines).
 *
 * The title is omitted (with its colon) when it merely repeats the
 * type or the contacts — the title fallback chain's doing. See
 * [showTitle]: a title equal to the type name, or to the contacts
 * joined either the old comma way ("Ben, Ana") or the journal way
 * ("Ben and Ana"), is dropped. Comparison ignores case and
 * surrounding spaces.
 */
fun formatJournal(entries: List<JournalEntry>): String {
    if (entries.isEmpty()) return ""
    val headerFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    val byDay = entries.groupBy { it.date }.toSortedMap()
    val sb = StringBuilder()
    byDay.forEach { (date, dayEntries) ->
        sb.append(date.format(headerFmt)).append('\n')
        dayEntries.forEach { e ->
            sb.append("  ")
            if (showTitle(e)) sb.append(e.title.trim()).append(": ")
            sb.append(e.description.trimEnd())
            val hasContacts = e.contactNames.isNotEmpty()
            if (e.typeName != null || hasContacts) {
                sb.append(" — ")
                if (e.typeName != null) {
                    sb.append(e.typeName)
                    if (hasContacts) sb.append(' ')
                }
                if (hasContacts) {
                    sb.append("with ").append(joinContactNames(e.contactNames)).append('.')
                }
            }
            sb.append('\n')
        }
        sb.append('\n')
    }
    return sb.toString().trimEnd() + "\n"
}

/**
 * Whether the entry's title earns its `Title: ` prefix. A blank title
 * never does. A title that only repeats the type name, or the contacts
 * (joined either the old comma way or the journal way — stored titles
 * predate the v0.20.6 fallback change, so both forms must match), is
 * omitted with its colon.
 */
private fun showTitle(e: JournalEntry): Boolean {
    val t = e.title.trim()
    if (t.isEmpty()) return false
    if (e.typeName != null && t.equals(e.typeName.trim(), ignoreCase = true)) return false
    if (e.contactNames.isNotEmpty()) {
        val names = e.contactNames.map { it.trim() }
        if (t.equals(names.joinToString(", "), ignoreCase = true)) return false
        if (t.equals(joinContactNames(names), ignoreCase = true)) return false
    }
    return true
}

/** "Mom" / "Mom and Whitney McMurrin" / "Jane Doe, Robert Locke, and Harmony Liddle". */
fun joinContactNames(names: List<String>): String = when (names.size) {
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + ", and " + names.last()
}
