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
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: Contact): Long

    @Update
    suspend fun update(contact: Contact)

    @Delete
    suspend fun delete(contact: Contact)

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: Long): Contact?

    @Query("SELECT * FROM contacts WHERE deviceContactId = :deviceId")
    suspend fun getByDeviceId(deviceId: Long): Contact?

    /** Lookup by stable vCard UID — powers import deduplication. */
    @Query("SELECT * FROM contacts WHERE vcardUid = :uid LIMIT 1")
    suspend fun getByVcardUid(uid: String): Contact?

    /** Case-insensitive email match — used to link imported attendees. */
    @Query("SELECT * FROM contacts WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getByEmail(email: String): Contact?

    /** Full contact list for export filtering (one-shot). */
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<Contact>

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAllAlphabetical(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE displayName LIKE '%' || :query || '%' ORDER BY displayName COLLATE NOCASE ASC")
    fun searchByName(query: String): Flow<List<Contact>>

    /**
     * Contacts ordered by most recent interaction: the latest event start
     * time among their linked events (contacts with no events sort last).
     */
    @Query(
        """SELECT c.* FROM contacts c
           LEFT JOIN event_contact_cross_ref r ON r.contactId = c.id
           LEFT JOIN events e ON e.id = r.eventId
           GROUP BY c.id
           ORDER BY MAX(e.startTime) DESC"""
    )
    fun observeByRecentInteraction(): Flow<List<Contact>>

    /** Contacts ordered by number of linked events (interaction frequency). */
    @Query(
        """SELECT c.* FROM contacts c
           LEFT JOIN event_contact_cross_ref r ON r.contactId = c.id
           GROUP BY c.id
           ORDER BY COUNT(r.eventId) DESC"""
    )
    fun observeByInteractionCount(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE relationshipTagId = :tagId ORDER BY displayName COLLATE NOCASE ASC")
    fun observeByRelationship(tagId: Long): Flow<List<Contact>>

    /**
     * All contacts ordered by the user's manual tag ordering (untagged sort
     * last), then tag name as a tiebreak, then alphabetically within each tag.
     */
    @Query(
        """SELECT c.* FROM contacts c
           LEFT JOIN relationship_tags t ON t.id = c.relationshipTagId
           ORDER BY (t.id IS NULL), t.sortOrder ASC, t.name COLLATE NOCASE ASC,
                    c.displayName COLLATE NOCASE ASC"""
    )
    fun observeSortedByRelationship(): Flow<List<Contact>>

    /** All event links for one contact — captured before delete for undo. */
    @Query("SELECT * FROM event_contact_cross_ref WHERE contactId = :contactId")
    suspend fun getRefsForContact(contactId: Long): List<EventContactCrossRef>

    /**
     * Repoints a contact's links to another contact (contact merging).
     * OR IGNORE keeps the survivor's existing links when both contacts
     * share an event; leftover rows are cleaned by the delete cascade.
     */
    @Query("UPDATE OR IGNORE event_contact_cross_ref SET contactId = :newId WHERE contactId = :oldId")
    suspend fun repointRefs(oldId: Long, newId: Long)

    @Transaction
    @Query("SELECT * FROM contacts WHERE id = :id")
    fun observeWithEvents(id: Long): Flow<ContactWithEvents?>

    /**
     * Contacts modified after [since] — the contact half of an M16 sync
     * delta. Ordered by update time so cursors advance monotonically.
     */
    @Query("SELECT * FROM contacts WHERE updatedAt > :since ORDER BY updatedAt ASC")
    suspend fun getUpdatedSince(since: Long): List<Contact>
}
