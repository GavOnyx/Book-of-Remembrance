// The link between an event and a contact, stamped with when the link was made (drives the Recent sort).

package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Many-to-many link between events and contacts: one event can involve many
 * people, and one person appears across many events (their timeline).
 */
@Entity(
    tableName = "event_contact_cross_ref",
    primaryKeys = ["eventId", "contactId"],
    foreignKeys = [
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Contact::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EventContactCrossRef(
    val eventId: Long,
    val contactId: Long,
    /**
     * When this contact was added to this event (epoch millis, v0.21.6).
     * Drives the contacts "Recent" sort: most recently *added* first,
     * not the latest event start time. Backfilled from the event's
     * last-modified time (MIGRATION_12_13); new links stamp the time
     * they were created, one shared stamp per save operation so contacts
     * added together share the top spot and sort alphabetically there.
     * Existing links keep their stamp when an event is re-saved — only
     * newly added contacts get a fresh one.
     */
    val linkedAt: Long = 0L
)
