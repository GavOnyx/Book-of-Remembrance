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
 * Formats journal entries as a self-contained HTML document (v0.21.3).
 * Same content and rules as [formatJournal]: day headings `MMMM d, yyyy`
 * (each an h2 with id="yyyy-MM-dd"), entries as paragraphs with the title
 * in <strong> and the type/contacts suffix in <span class="meta">; the
 * title is omitted when it merely repeats the type or the contacts (see
 * [showTitle]). User-entered text is HTML-escaped (&, <, >); decorative
 * punctuation (the em-dash, curly apostrophes) goes through literally as
 * UTF-8, per the declared charset. Styling is inline in the document so
 * the file renders on its own in any browser or reader.
 */
fun formatJournalHtml(entries: List<JournalEntry>): String {
    val headerFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    val byDay = entries.groupBy { it.date }.toSortedMap()
    val first = byDay.keys.firstOrNull()
    val last = byDay.keys.lastOrNull()
    val range = if (first != null && last != null) rangeLabel(first, last, compact = false) else null
    val docTitle = if (first != null && last != null) "Journal — ${rangeLabel(first, last, compact = true)}" else "Journal"
    val sb = StringBuilder()
    sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
    sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    sb.append("<title>").append(escapeHtml(docTitle)).append("</title>\n<style>\n")
    sb.append(HTML_CSS)
    sb.append("</style>\n</head>\n<body>\n<main>\n<h1>Journal</h1>\n")
    if (range != null) sb.append("<p class=\"range\">").append(escapeHtml(range)).append("</p>\n")
    sb.append('\n')
    byDay.forEach { (date, dayEntries) ->
        sb.append("<h2 id=\"").append(date.toString()).append("\">")
            .append(date.format(headerFmt)).append("</h2>\n")
        dayEntries.forEach { e ->
            sb.append("<p>")
            if (showTitle(e)) sb.append("<strong>").append(escapeHtml(e.title.trim())).append(":</strong> ")
            sb.append(escapeHtml(e.description.trimEnd()))
            val hasContacts = e.contactNames.isNotEmpty()
            if (e.typeName != null || hasContacts) {
                sb.append(" <span class=\"meta\">— ")
                if (e.typeName != null) {
                    sb.append(escapeHtml(e.typeName))
                    if (hasContacts) sb.append(' ')
                }
                if (hasContacts) {
                    sb.append("with ").append(escapeHtml(joinContactNames(e.contactNames))).append('.')
                }
                sb.append("</span>")
            }
            sb.append("</p>\n")
        }
        sb.append('\n')
    }
    sb.append("</main>\n</body>\n</html>\n")
    return sb.toString()
}

/** "October 24, 2026" / "October 24 – 27, 2026" (same month) / "October 28, 2026 – November 3, 2026". */
private fun rangeLabel(first: LocalDate, last: LocalDate, compact: Boolean): String {
    val fullFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    val monthFmt = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)
    if (first == last) return first.format(fullFmt)
    if (first.year == last.year && first.month == last.month) {
        return if (compact) "${first.format(monthFmt)}–${last.dayOfMonth}, ${first.year}"
        else "${first.format(monthFmt)} – ${last.dayOfMonth}, ${first.year}"
    }
    return "${first.format(fullFmt)} – ${last.format(fullFmt)}"
}

/**
 * Escapes only what HTML markup requires (&, <, >); every other character —
 * including the em-dash and curly apostrophes — passes through literally as UTF-8.
 */
private fun escapeHtml(s: String): String {
    if (s.none { it == '&' || it == '<' || it == '>' }) return s
    val sb = StringBuilder(s.length)
    for (c in s) when (c) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        else -> sb.append(c)
    }
    return sb.toString()
}

/** Inline stylesheet for the HTML journal export — the approved example's styling, self-contained in the file. */
private const val HTML_CSS = """  body {
    font-family: Georgia, 'Times New Roman', serif;
    line-height: 1.6;
    color: #1c1817;
    background: #fcf8f7;
    margin: 0;
    padding: 24px 16px;
  }
  main { max-width: 640px; margin: 0 auto; }
  h1 { font-size: 1.4em; margin: 0 0 0.1em; }
  .range { color: #6b625f; margin: 0 0 1em; }
  h2 {
    font-size: 1.15em;
    margin: 1.6em 0 0.4em;
    padding-bottom: 0.2em;
    border-bottom: 1px solid #a7004e;
  }
  p { margin: 0.55em 0 0.55em 1.2em; }
  .meta { color: #6b625f; }
"""

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
