package com.calonyx.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: Event): Long

    @Update
    suspend fun update(event: Event)

    @Delete
    suspend fun delete(event: Event)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): Event?

    /** Events overlapping [from, to) — powers the day cascade and month fill. */
    @Query("SELECT * FROM events WHERE startTime < :to AND endTime > :from ORDER BY startTime ASC")
    fun observeInRange(from: Long, to: Long): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE title LIKE '%' || :query || '%' ORDER BY startTime DESC")
    fun searchByTitle(query: String): Flow<List<Event>>

    /** Full event list for export filtering (one-shot). */
    @Query("SELECT * FROM events ORDER BY startTime ASC")
    suspend fun getAll(): List<Event>

    /** Lookup by stable iCalendar UID — powers import deduplication. */
    @Query("SELECT * FROM events WHERE icalUid = :uid LIMIT 1")
    suspend fun getByIcalUid(uid: String): Event?

    /** All instances of a recurring series, earliest first. */
    @Query("SELECT * FROM events WHERE seriesId = :seriesId ORDER BY startTime ASC")
    suspend fun getBySeriesId(seriesId: String): List<Event>

    /** How many instances a recurring series currently has. */
    @Query("SELECT COUNT(*) FROM events WHERE seriesId = :seriesId")
    suspend fun countBySeriesId(seriesId: String): Int

    @Query("SELECT COUNT(*) FROM events WHERE eventTypeId = :typeId")
    suspend fun countByTypeId(typeId: Long): Int

    @Transaction
    @Query("SELECT * FROM events WHERE id = :id")
    fun observeWithContacts(id: Long): Flow<EventWithContacts?>

    /** One-shot variant for export (resolving linked contacts per event). */
    @Transaction
    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getWithContacts(id: Long): EventWithContacts?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkContact(ref: EventContactCrossRef)

    /** Bulk link insert (undo restores); conflicts can't occur post-cascade. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRefs(refs: List<EventContactCrossRef>)

    /** All contact links for the given events — captured before delete for undo. */
    @Query("SELECT * FROM event_contact_cross_ref WHERE eventId IN (:eventIds)")
    suspend fun getRefsForEvents(eventIds: List<Long>): List<EventContactCrossRef>

    /**
     * v0.20.1: emits on ANY attendee-link change. The day feed watches
     * this so contact names re-resolve after link-only edits (the
     * journal picker writes links without touching the event row).
     */
    @Query("SELECT * FROM event_contact_cross_ref")
    fun observeAllRefs(): Flow<List<EventContactCrossRef>>

    @Query("DELETE FROM event_contact_cross_ref WHERE eventId = :eventId AND contactId = :contactId")
    suspend fun unlinkContact(eventId: Long, contactId: Long)

    /**
     * Events modified after [since] — the event half of an M16 sync delta.
     * Ordered by update time so cursors advance monotonically.
     */
    @Query("SELECT * FROM events WHERE updatedAt > :since ORDER BY updatedAt ASC")
    suspend fun getUpdatedSince(since: Long): List<Event>
}
