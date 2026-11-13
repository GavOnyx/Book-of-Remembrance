package com.calonyx.app.data

import androidx.room.Entity

/**
 * A deletion tombstone (M16 partner sync). When an event or contact is
 * deleted, a tombstone is written so the delete propagates to partners:
 * without it, the deleted record would simply come back from the other
 * device's copy on the next sync.
 *
 * Keyed by the record's stable UID ([uid] — icalUid/vcardUid), never the
 * local row id, which differs per device. Tombstones ride the delta
 * protocol and are never synced as records themselves.
 */
@Entity(
    tableName = "tombstones",
    primaryKeys = ["recordType", "uid"]
)
data class Tombstone(
    /** 'event' or 'contact'. */
    val recordType: String,
    val uid: String,
    val deletedAt: Long
) {
    companion object {
        const val EVENT = "event"
        const val CONTACT = "contact"
    }
}
