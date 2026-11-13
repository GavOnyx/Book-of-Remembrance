package com.calonyx.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A remembered sync partner (M16 partner sync). Each install is identified
 * by a stable random UUID ([uuid]) generated once on first run — never the
 * IP address, which changes every time the device joins a network.
 *
 * [lastSyncAt] is the per-partner sync cursor: millis up to which this
 * device has received and applied the partner's changes. 0 = never synced
 * (the next sync does a full two-way exchange).
 */
@Entity(tableName = "partners")
data class Partner(
    @PrimaryKey val uuid: String,
    val displayName: String,
    /** Last IP the partner was reached at — a reconnect hint only. */
    val lastKnownIp: String? = null,
    val lastSyncAt: Long = 0,
    /** Master's toggle: whether contact deltas are exchanged with this partner. */
    val syncContacts: Boolean = true,
    val createdAt: Long = 0
)
