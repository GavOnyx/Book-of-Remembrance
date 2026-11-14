package com.calonyx.app.data

import androidx.room.TypeConverter

/** Room converters for types stored in non-primitive columns. */
class Converters {
    @TypeConverter
    fun eventStatusToString(status: EventStatus): String = status.name

    @TypeConverter
    fun stringToEventStatus(value: String): EventStatus =
        runCatching { EventStatus.valueOf(value) }.getOrDefault(EventStatus.UNMARKED)
}
