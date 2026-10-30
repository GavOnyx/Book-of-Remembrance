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
    val contactId: Long
)
