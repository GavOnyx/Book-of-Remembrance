package com.calonyx.app.data.importexport

/** A single unfolded `NAME;PARAM=VAL:VALUE` content line. */
internal data class ContentLine(
    val name: String,
    val params: Map<String, String>,
    val value: String
)

/** RFC 5545 unfolding: lines starting with space/tab continue the previous. */
internal fun unfoldLines(text: String): List<String> {
    val out = mutableListOf<String>()
    for (line in text.split(Regex("\r\n|\n|\r"))) {
        if (line.isEmpty()) continue
        if ((line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
            out[out.size - 1] += line.substring(1)
        } else {
            out.add(line)
        }
    }
    return out
}

/** Splits NAME;PARAM=VAL:VALUE, quote-aware so colons inside quotes survive. */
internal fun parseContentLine(line: String): ContentLine? {
    var inQuotes = false
    var colon = -1
    for (i in line.indices) {
        when (line[i]) {
            '"' -> inQuotes = !inQuotes
            ':' -> if (!inQuotes) {
                colon = i
                break
            }
        }
    }
    if (colon < 0) return null
    val nameAndParams = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = splitParams(nameAndParams)
    if (parts.isEmpty()) return null
    val params = parts.drop(1).mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) null
        else part.substring(0, eq).uppercase() to unquote(part.substring(eq + 1))
    }.toMap()
    // RFC 6350 §3.2 property grouping (e.g. "item1.TEL"): the group prefix
    // is not part of the property name. Calendar lines (RFC 5545) never
    // use groups, so this is a no-op for the ICS parser.
    val propName = parts[0].substringAfterLast('.').uppercase()
    return ContentLine(propName, params, value)
}

private fun splitParams(s: String): List<String> {
    val out = mutableListOf<String>()
    var inQuotes = false
    var start = 0
    for (i in s.indices) {
        when (s[i]) {
            '"' -> inQuotes = !inQuotes
            ';' -> if (!inQuotes) {
                out.add(s.substring(start, i))
                start = i + 1
            }
        }
    }
    out.add(s.substring(start))
    return out
}

private fun unquote(s: String): String =
    if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1)
    else s

/** Reverses RFC 5545 TEXT escaping. */
internal fun unescapeText(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n', 'N' -> sb.append('\n')
                '\\' -> sb.append('\\')
                ';' -> sb.append(';')
                ',' -> sb.append(',')
                else -> {
                    sb.append(c)
                    sb.append(s[i + 1])
                }
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

/** Escapes a TEXT value per RFC 5545 §3.3.11. */
internal fun escapeText(s: String): String = s
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r", "")
    .replace("\n", "\\n")

/**
 * Folds a content line at 75 octets per RFC 5545 §3.1, counting UTF-8
 * bytes so multi-byte characters are never split.
 */
internal fun foldLine(line: String): String {
    if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
    val sb = StringBuilder()
    var lineStart = 0
    var byteCount = 0
    var i = 0
    while (i < line.length) {
        val cp = line.codePointAt(i)
        val cpBytes =
            String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        if (byteCount + cpBytes > 75 && i > lineStart) {
            sb.append(line.substring(lineStart, i)).append("\r\n ")
            lineStart = i
            byteCount = 1 // the continuation space
        }
        byteCount += cpBytes
        i += Character.charCount(cp)
    }
    sb.append(line.substring(lineStart))
    return sb.toString()
}
