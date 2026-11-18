package com.calonyx.app.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/** An event together with all people involved in it. */
data class EventWithContacts(
    @Embedded val event: Event,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = EventContactCrossRef::class,
            parentColumn = "eventId",
            entityColumn = "contactId"
        )
    )
    val contacts: List<Contact>
)

/** A contact together with their full event timeline. */
data class ContactWithEvents(
    @Embedded val contact: Contact,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = EventContactCrossRef::class,
            parentColumn = "contactId",
            entityColumn = "eventId"
        )
    )
    val events: List<Event>
)
