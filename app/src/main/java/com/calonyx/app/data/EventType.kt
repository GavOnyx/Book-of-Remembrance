package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-definable event type (e.g. Call, Meal, Work). Each type carries its
 * own color used to visually differentiate events in the calendar cascade.
 * This is separate from the UI theme palette.
 */
@Entity(tableName = "event_types")
data class EventType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Color as 0xAARRGGBB. */
    val colorArgb: Int,
    val isDefault: Boolean = false,
    /** Default event length in minutes, applied when the type is picked in the editor. */
    val defaultDurationMinutes: Int = 60,
    /**
     * v0.21 (themes): this default type's color in the saved Custom
     * theme. Only changed on Save; null means never snapshotted (the
     * current color is used instead). Ignored for user-added types,
     * which are theme-agnostic and keep their color regardless.
     */
    val customColorArgb: Int? = null
)
