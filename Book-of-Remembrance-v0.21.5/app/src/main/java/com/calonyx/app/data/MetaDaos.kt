package com.calonyx.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EventTypeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(type: EventType): Long

    @Update
    suspend fun update(type: EventType)

    /** Default types are protected from deletion in the repository. */
    @Delete
    suspend fun delete(type: EventType)

    @Query("SELECT * FROM event_types ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<EventType>>

    @Query("SELECT * FROM event_types WHERE id = :id")
    suspend fun getById(id: Long): EventType?

    /** Case-insensitive name match — used when importing typed events. */
    @Query("SELECT * FROM event_types WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getByName(name: String): EventType?

    @Query("SELECT COUNT(*) FROM event_types")
    suspend fun count(): Int
}

@Dao
interface RelationshipTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: RelationshipTag): Long

    @Update
    suspend fun update(tag: RelationshipTag)

    @Delete
    suspend fun delete(tag: RelationshipTag)

    @Query("SELECT * FROM relationship_tags ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<RelationshipTag>>

    @Query("SELECT * FROM relationship_tags WHERE id = :id")
    suspend fun getById(id: Long): RelationshipTag?

    /** Case-insensitive name match — used when importing tagged contacts. */
    @Query("SELECT * FROM relationship_tags WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun getByName(name: String): RelationshipTag?

    @Query("SELECT COUNT(*) FROM relationship_tags")
    suspend fun count(): Int
}

@Dao
interface ThemeSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: ThemeSettings)

    @Query("SELECT * FROM theme_settings WHERE id = 1")
    fun observe(): Flow<ThemeSettings?>

    @Query("SELECT * FROM theme_settings WHERE id = 1")
    suspend fun get(): ThemeSettings?
}

@Dao
interface AppSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: AppSettings)

    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettings?
}

/**
 * Remembered sync partners (M16). One row per paired device, keyed by
 * the partner install's stable UUID.
 */
@Dao
interface PartnerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(partner: Partner)

    @Delete
    suspend fun delete(partner: Partner)

    @Query("SELECT * FROM partners WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): Partner?

    @Query("SELECT * FROM partners ORDER BY displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Partner>>

    @Query("SELECT * FROM partners ORDER BY displayName COLLATE NOCASE ASC")
    suspend fun getAll(): List<Partner>

    @Query("SELECT COUNT(*) FROM partners")
    suspend fun count(): Int
}

/** Deletion tombstones (M16) — see [Tombstone]. */
@Dao
interface TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tombstone: Tombstone)

    @Query("SELECT * FROM tombstones WHERE recordType = :type AND uid = :uid LIMIT 1")
    suspend fun get(type: String, uid: String): Tombstone?

    /** Tombstones newer than [since] — the delete half of a sync delta. */
    @Query("SELECT * FROM tombstones WHERE deletedAt > :since ORDER BY deletedAt ASC")
    suspend fun getSince(since: Long): List<Tombstone>

    @Query("DELETE FROM tombstones WHERE recordType = :type AND uid = :uid")
    suspend fun delete(type: String, uid: String)
}
