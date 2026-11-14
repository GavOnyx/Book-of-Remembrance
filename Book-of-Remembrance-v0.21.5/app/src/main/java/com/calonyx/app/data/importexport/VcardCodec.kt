package com.calonyx.app.data.importexport

import com.calonyx.app.data.Contact
import java.util.UUID

/**
 * Fallback for vCards (or attendee CN values) that carry only a display
 * name: the first whitespace-separated token becomes the first name and
 * the remainder becomes the last name. A single token keeps lastName null.
 */
internal fun splitDisplayName(display: String): Pair<String?, String?> {
    val tokens = display.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null to null
    val first = tokens.first()
    val last = tokens.drop(1).joinToString(" ").takeIf { it.isNotEmpty() }
    return first to last
}

/** One contact parsed from a .vcf file. */
data class ParsedContact(
    val uid: String,
    val firstName: String?,
    val lastName: String?,
    val displayName: String,
    val phone: String?,
    val email: String?,
    val address: String?,
    val notes: String?,
    /** CalOnyx relationship tag name from X-CALONYX-RELATIONSHIP. */
    val relationship: String?,
    /** Modification stamp from X-CALONYX-UPDATED-AT (M16 sync); null if absent. */
    val updatedAt: Long?
)

/**
 * Hand-written vCard 4.0 (RFC 6350) parser/generator — no external
 * libraries. Covers the fields CalOnyx stores: FN/N, TEL, EMAIL, ADR,
 * NOTE, UID, plus X-CALONYX-RELATIONSHIP for the tag round-trip.
 */
object VcardCodec {

    fun parse(text: String): List<ParsedContact> {
        val out = mutableListOf<ParsedContact>()
        var inCard = false
        var firstName: String? = null
        var lastName: String? = null
        var fn: String? = null
        var phone: String? = null
        var email: String? = null
        var address: String? = null
        var notes: String? = null
        var uid: String? = null
        var relationship: String? = null
        var updatedAt: Long? = null

        fun flush() {
            if (!inCard) return
            val display = fn?.takeIf { it.isNotBlank() }
                ?: Contact.displayNameFor(firstName, lastName, "")
                    .takeIf { it.isNotBlank() }
                ?: return // no usable name — skip the card
            // Real-world vCards usually carry only FN with an empty N:
            // derive first/last from the display name so the contact
            // editor's name fields are filled, not just the details view.
            val (effFirst, effLast) =
                if (firstName.isNullOrBlank() && lastName.isNullOrBlank()) {
                    splitDisplayName(display)
                } else {
                    firstName to lastName
                }
            out.add(
                ParsedContact(
                    uid = uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    firstName = effFirst,
                    lastName = effLast,
                    displayName = display,
                    phone = phone,
                    email = email,
                    address = address,
                    notes = notes,
                    relationship = relationship,
                    updatedAt = updatedAt
                )
            )
        }

        for (raw in unfoldLines(text)) {
            val line = parseContentLine(raw) ?: continue
            when {
                line.name == "BEGIN" && line.value.equals("VCARD", ignoreCase = true) -> {
                    inCard = true
                    firstName = null; lastName = null; fn = null
                    phone = null; email = null; address = null
                    notes = null; uid = null; relationship = null
                    updatedAt = null
                }
                line.name == "END" && line.value.equals("VCARD", ignoreCase = true) -> {
                    flush()
                    inCard = false
                }
                inCard -> when (line.name) {
                    "FN" -> if (fn == null) {
                        fn = unescapeText(line.value).trim()
                    }
                    "N" -> {
                        // N:Last;First;Middle;Prefix;Suffix
                        val parts = line.value.split(";")
                        lastName = parts.getOrNull(0)
                            ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
                        firstName = parts.getOrNull(1)
                            ?.let(::unescapeText)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    "TEL" -> if (phone == null) {
                        phone = unescapeText(line.value).trim()
                            .takeIf { it.isNotEmpty() }
                    }
                    "EMAIL" -> if (email == null) {
                        email = unescapeText(line.value).trim()
                            .takeIf { it.isNotEmpty() }
                    }
                    "ADR" -> if (address == null) {
                        // ADR:POBox;Extended;Street;City;Region;Postal;Country
                        address = line.value.split(";")
                            .map { unescapeText(it).trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(", ")
                            .takeIf { it.isNotEmpty() }
                    }
                    "NOTE" -> {
                        val n = unescapeText(line.value).trim()
                            .takeIf { it.isNotEmpty() }
                        notes = if (notes == null) n
                                else listOfNotNull(notes, n).joinToString("\n")
                    }
                    "UID" -> if (uid == null) {
                        uid = line.value.trim().takeIf { it.isNotEmpty() }
                    }
                    "X-CALONYX-RELATIONSHIP" -> if (relationship == null) {
                        relationship = unescapeText(line.value).trim()
                            .takeIf { it.isNotEmpty() }
                    }
                    // M16: partner-sync modification stamp.
                    "X-CALONYX-UPDATED-AT" -> if (updatedAt == null) {
                        updatedAt = line.value.trim().toLongOrNull()
                    }
                }
            }
        }
        return out
    }

    /**
     * @param contactsWithTags pairs of contact and its relationship tag
     *   name (null when untagged). Every contact must already carry a
     *   non-null [Contact.vcardUid] (the manager ensures this).
     */
    fun generate(contactsWithTags: List<Pair<Contact, String?>>): String {
        val sb = StringBuilder()
        fun line(s: String) {
            sb.append(foldLine(s)).append("\r\n")
        }
        for ((c, tagName) in contactsWithTags) {
            val cuid = c.vcardUid ?: continue
            line("BEGIN:VCARD")
            line("VERSION:4.0")
            line("UID:$cuid")
            line("FN:${escapeText(c.displayName)}")
            val n = "${escapeText(c.lastName.orEmpty())};" +
                escapeText(c.firstName.orEmpty()) + ";;;"
            line("N:$n")
            c.phone?.takeIf { it.isNotBlank() }?.let {
                line("TEL;TYPE=voice:${escapeText(it.trim())}")
            }
            c.email?.takeIf { it.isNotBlank() }?.let {
                line("EMAIL:${escapeText(it.trim())}")
            }
            c.address?.takeIf { it.isNotBlank() }?.let {
                line("ADR:;;${escapeText(it.trim())};;;;")
            }
            c.notes?.takeIf { it.isNotBlank() }?.let {
                line("NOTE:${escapeText(it.trim())}")
            }
            // M16: modification stamp for partner-sync (see IcsGenerator).
            line("X-CALONYX-UPDATED-AT:${c.updatedAt}")
            tagName?.takeIf { it.isNotBlank() }?.let {
                line("X-CALONYX-RELATIONSHIP:${escapeText(it.trim())}")
            }
            line("END:VCARD")
        }
        return sb.toString()
    }
}
