package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A calendar event. Times are stored as epoch milliseconds so the day
 * cascade, month fill percentages, and iCal export all share one source.
 */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = EventType::class,
            parentColumns = ["id"],
            childColumns = ["eventTypeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("eventTypeId"), Index("startTime")]
)
data class Event(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val location: String? = null,
    val description: String? = null,
    val eventTypeId: Long? = null,
    val status: EventStatus = EventStatus.UNMARKED,
    /**
     * Stable iCalendar UID for import/export deduplication. Null for rows
     * created before milestone 7; generated on export.
     */
    val icalUid: String? = null,
    /**
     * RFC 5545 recurrence rule (e.g. "FREQ=WEEKLY;INTERVAL=1") when this
     * event belongs to a recurring series. Null for one-off events.
     */
    val recurrenceRule: String? = null,
    /**
     * UUID shared by every instance of a recurring series. Null for
     * one-off events and for instances detached by editing.
     */
    val seriesId: String? = null,
    /**
     * Last modification time (epoch millis), stamped on every write.
     * Powers M16 partner sync: deltas are records with updatedAt newer
     * than the partner's sync cursor; conflicts resolve last-writer-wins.
     */
    val updatedAt: Long = 0
)
