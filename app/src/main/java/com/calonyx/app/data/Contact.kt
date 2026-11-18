package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A contact known to CalOnyx. Contacts may be linked to a device contact
 * (via [deviceContactId], the ContactsProvider _ID) for read/write sync,
 * but the app works fully from this local table when access is denied.
 */
@Entity(
    tableName = "contacts",
    foreignKeys = [
        ForeignKey(
            entity = RelationshipTag::class,
            parentColumns = ["id"],
            childColumns = ["relationshipTagId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("relationshipTagId")]
)
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val relationshipTagId: Long? = null,
    /** ContactsProvider _ID when synced with a device contact, else null. */
    val deviceContactId: Long? = null,
    val lastSyncedAt: Long? = null,
    /**
     * Stable vCard UID for import/export deduplication. Null for rows
     * created before milestone 7; generated on export.
     */
    val vcardUid: String? = null,
    /**
     * Last modification time (epoch millis), stamped on every write.
     * Powers M16 partner sync (see [Event.updatedAt]).
     */
    val updatedAt: Long = 0
) {
    companion object {
        /** Builds the display name from first/last name, falling back to [fallback]. */
        fun displayNameFor(firstName: String?, lastName: String?, fallback: String = ""): String {
            val joined = listOf(firstName?.trim(), lastName?.trim())
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
            return joined.ifBlank { fallback.trim() }
        }
    }
}
