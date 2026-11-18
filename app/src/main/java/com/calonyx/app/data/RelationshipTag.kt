package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A relationship status/tag for contacts (e.g. family, friend, co-worker).
 * Users may create and delete their own tags; defaults are seeded on first run.
 */
@Entity(tableName = "relationship_tags")
data class RelationshipTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    /** Manual ordering set by the user in Settings; drives the relationship contact sort. */
    val sortOrder: Int = 0
)
