package com.calonyx.app.data

/**
 * Lifecycle status of an event, mirrored from the blueprint's color key:
 * - UNMARKED: not happened / no status yet (no color dot)
 * - HAPPENED_RECORDED: happened (green)
 * - DID_NOT_HAPPEN: the event didn't end up happening (red)
 *
 * (A former HAPPENED_UNRECORDED/yellow state was removed in milestone 5;
 * existing rows were migrated to HAPPENED_RECORDED.)
 */
enum class EventStatus {
    UNMARKED,
    HAPPENED_RECORDED,
    DID_NOT_HAPPEN
}
