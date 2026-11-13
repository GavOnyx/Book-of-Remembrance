package com.calonyx.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The customizable UI color palette. Stored as a singleton row (id = 1).
 * Every visual element in the app draws from these values; event-type
 * colors are separate and live on [EventType].
 *
 * v0.21 (themes): the row also carries the selected theme and the
 * saved Custom theme. [selectedThemeId] is a stable string id
 * ("light", "original", "custom") — never a list position, so a
 * preset added later (Dark) can't shift anyone's selection. The
 * `custom*` columns are the saved Custom theme's appearance: the
 * current [background]/[foreground]/[primary]/[font] are whatever
 * theme is active (possibly with unsaved edits), while the custom*
 * columns only change on Save. The saved Custom theme's default-type
 * colors live on [EventType.customColorArgb].
 */
@Entity(tableName = "theme_settings")
data class ThemeSettings(
    @PrimaryKey val id: Int = 1,
    /** Colors as 0xAARRGGBB. */
    val background: Int,
    val foreground: Int,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val font: Int,
    /** Stable id of the selected theme; see the class doc. */
    @ColumnInfo(defaultValue = "'custom'")
    val selectedThemeId: String = "custom",
    /** Saved Custom theme appearance (only changed on Save). */
    @ColumnInfo(defaultValue = "0")
    val customBackground: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val customForeground: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val customAccent: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val customFont: Int = 0
)
