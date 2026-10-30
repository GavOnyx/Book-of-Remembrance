package com.calonyx.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Event::class,
        Contact::class,
        EventContactCrossRef::class,
        EventType::class,
        RelationshipTag::class,
        ThemeSettings::class,
        AppSettings::class,
        Partner::class,
        Tombstone::class
    ],
    version = 12,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class CalOnyxDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun contactDao(): ContactDao
    abstract fun eventTypeDao(): EventTypeDao
    abstract fun relationshipTagDao(): RelationshipTagDao
    abstract fun themeSettingsDao(): ThemeSettingsDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun partnerDao(): PartnerDao
    abstract fun tombstoneDao(): TombstoneDao

    companion object {
        @Volatile
        private var instance: CalOnyxDatabase? = null

        /** v1 → v2: adds the app_settings singleton table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_settings` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`calendarStartHour` INTEGER NOT NULL, " +
                        "`calendarEndHour` INTEGER NOT NULL, " +
                        "`fillWindowStartHour` INTEGER NOT NULL, " +
                        "`fillWindowEndHour` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /**
         * v2 → v3: adds firstName/lastName/notes to contacts and
         * contactSortOrder to app_settings. All contact columns are nullable
         * so existing rows need no backfill; the sort column gets a default.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `firstName` TEXT")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `lastName` TEXT")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `notes` TEXT")
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `contactSortOrder` " +
                        "TEXT NOT NULL DEFAULT 'ALPHABETICAL'"
                )
            }
        }

        /**
         * v3 → v4: two changes in one migration.
         * - The yellow "happened – unrecorded" status was removed; existing
         *   rows map to HAPPENED_RECORDED (it did happen). Status is stored
         *   as a String via Converters, so a plain UPDATE suffices.
         * - Adds the textScale column to app_settings (REAL, default 1.0).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `events` SET `status` = 'HAPPENED_RECORDED' " +
                        "WHERE `status` = 'HAPPENED_UNRECORDED'"
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `textScale` " +
                        "REAL NOT NULL DEFAULT 1.0"
                )
            }
        }

        /**
         * v4 → v5: adds `email` to contacts and the color-palette columns
         * (`customSwatches`, `removedSwatches`) to app_settings.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `email` TEXT")
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `customSwatches` " +
                        "TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `removedSwatches` " +
                        "TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v5 → v6: import/export support.
         * - Stable `icalUid` on events and `vcardUid` on contacts for
         *   import deduplication (nullable; generated on export).
         * - `autoCreateContactsOnImport` toggle on app_settings (default on).
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `icalUid` TEXT")
                db.execSQL("ALTER TABLE `contacts` ADD COLUMN `vcardUid` TEXT")
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `autoCreateContactsOnImport` " +
                        "INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * v6 → v7: milestone 9.
         * - `defaultDurationMinutes` on event types (default 60).
         * - `sortOrder` on relationship tags for manual ordering. Existing
         *   tags keep sortOrder 0 and the DAO tie-breaks alphabetically, so
         *   the initial order matches the old alphabetical sort.
         * - `statsPastDays` on app_settings (default 7).
         * - Seeds the new default "Travel" event type for existing installs
         *   (fresh installs get it via seedDefaultsIfNeeded).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `event_types` ADD COLUMN `defaultDurationMinutes` " +
                        "INTEGER NOT NULL DEFAULT 60"
                )
                db.execSQL(
                    "ALTER TABLE `relationship_tags` ADD COLUMN `sortOrder` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `statsPastDays` " +
                        "INTEGER NOT NULL DEFAULT 7"
                )
                // 0xFF06B6D4 (Travel cyan) as a signed 32-bit int.
                db.execSQL(
                    "INSERT INTO `event_types` " +
                        "(`name`, `colorArgb`, `isDefault`, `defaultDurationMinutes`) " +
                        "SELECT 'Travel', -16337196, 1, 60 " +
                        "WHERE NOT EXISTS " +
                        "(SELECT 1 FROM `event_types` WHERE LOWER(`name`) = 'travel')"
                )
            }
        }

        /**
         * v7 → v8: milestone 12 (recurring events).
         * - `recurrenceRule` (RFC 5545 RRULE string) and `seriesId` (UUID
         *   shared by a series' instances) on events. Both nullable; all
         *   existing rows are one-off events, so no backfill is needed.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `recurrenceRule` TEXT")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `seriesId` TEXT")
            }
        }

        /**
         * v8 → v9: v0.13.3 patch (Stats Types|Contacts toggle).
         * - `statsMode` on app_settings ("TYPE"/"CONTACT", default "TYPE").
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `statsMode` " +
                        "TEXT NOT NULL DEFAULT 'TYPE'"
                )
            }
        }

        /**
         * v9 → v10: milestone 16 (partner sync).
         * - `updatedAt` (INTEGER millis) on events and contacts, stamped
         *   on every write; existing rows are backfilled to migration time
         *   so the "every record has an updatedAt" invariant holds.
         * - `deviceUuid` on app_settings: the per-install stable sync
         *   identity, generated lazily on first use (blank until then).
         * - New `partners` table (remembered sync partners) and
         *   `tombstones` table (deletion markers for sync).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `events` ADD COLUMN `updatedAt` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `contacts` ADD COLUMN `updatedAt` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `deviceUuid` " +
                        "TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `partners` (" +
                        "`uuid` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`lastKnownIp` TEXT, " +
                        "`lastSyncAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`syncContacts` INTEGER NOT NULL DEFAULT 1, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`uuid`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tombstones` (" +
                        "`recordType` TEXT NOT NULL, " +
                        "`uid` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`recordType`, `uid`))"
                )
                db.execSQL(
                    "UPDATE `events` SET `updatedAt` = " +
                        "(strftime('%s','now')*1000)"
                )
                db.execSQL(
                    "UPDATE `contacts` SET `updatedAt` = " +
                        "(strftime('%s','now')*1000)"
                )
            }
        }

        /**
         * v10 → v11: milestone 20 (journal mode).
         * - `calendarJournalMode` on app_settings (default off = timeline).
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `calendarJournalMode` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v11 → v12: v0.21 theme system.
         * - `selectedThemeId` + `custom*` on theme_settings, and
         *   `customColorArgb` on event_types. The backfill makes the
         *   install's current colors its saved Custom theme and
         *   selects Custom, so the update changes nothing visually.
         *   Column defaults mirror the entity @ColumnInfo defaults.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `theme_settings` ADD COLUMN `selectedThemeId` " +
                        "TEXT NOT NULL DEFAULT 'custom'"
                )
                db.execSQL(
                    "ALTER TABLE `theme_settings` ADD COLUMN `customBackground` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `theme_settings` ADD COLUMN `customForeground` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `theme_settings` ADD COLUMN `customAccent` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `theme_settings` ADD COLUMN `customFont` " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE `theme_settings` SET " +
                        "`customBackground` = `background`, " +
                        "`customForeground` = `foreground`, " +
                        "`customAccent` = `primary`, " +
                        "`customFont` = `font`, " +
                        "`selectedThemeId` = 'custom'"
                )
                db.execSQL(
                    "ALTER TABLE `event_types` ADD COLUMN `customColorArgb` INTEGER"
                )
                db.execSQL(
                    "UPDATE `event_types` SET `customColorArgb` = `colorArgb`"
                )
            }
        }

        fun getInstance(context: Context): CalOnyxDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CalOnyxDatabase::class.java,
                    "calonyx.db"
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                    MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12
                ).build().also { instance = it }
            }
    }
}
