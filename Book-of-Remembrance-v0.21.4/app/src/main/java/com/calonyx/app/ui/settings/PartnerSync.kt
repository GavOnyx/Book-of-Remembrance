package com.calonyx.app.ui.settings

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Contact
import com.calonyx.app.data.Event
import com.calonyx.app.data.EventStatus
import com.calonyx.app.data.Partner
import com.calonyx.app.data.Tombstone
import com.calonyx.app.data.importexport.SyncCodec
import com.calonyx.app.data.importexport.VcardCodec
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M16 partner sync: two-way delta merge between remembered partners.
 *
 * Design (Master-approved):
 * - Manual sync only — no background work. Listening happens only while a
 *   sync/receive UI is open.
 * - Partners are identified by a stable per-install UUID (never the IP).
 *   The 4-digit code verifies the initial pairing; the UUID authenticates
 *   later syncs. Sync requests from unrecognized UUIDs are rejected.
 * - Same local network required (no relay server by design); any Wi-Fi
 *   works, internet access not required. mDNS discovery finds remembered
 *   partners; manual IP entry is the fallback.
 * - Merge: per-partner cursors (lastSyncAt), deltas both directions in one
 *   session, last-writer-wins by updatedAt with a deterministic tiebreak,
 *   tombstones for deletes. Records are keyed by UID, never local row id.
 * - Event types do NOT sync: unrecognized type names become None.
 * - Contacts sync only when both sides' syncContacts flag is on.
 *
 * Wire protocol SYNC-1 (all text UTF-8; binary lengths 8-byte big-endian):
 *   connector -> listener: "REMEMBRANCE-SYNC-1\\n"
 *   connector -> listener: "HELLO\\n"
 *   connector -> listener: "<connectorUuid>\\n"
 *   connector -> listener: "<connectorDisplayName>\\n"
 *   connector -> listener: "<connectorCursor> <syncContacts 0|1>\\n"
 *   listener  -> connector: "WELCOME\\n" | "REJECT\\n"
 *   listener  -> connector: "<listenerUuid>\\n"
 *   listener  -> connector: "<listenerDisplayName>\\n"
 *   listener  -> connector: "<listenerCursor> <syncContacts 0|1>\\n"
 *   then the delta exchange (connector first):
 *     sender -> receiver: 8B ICS length + ICS bytes (one VEVENT per row)
 *     sender -> receiver: 8B VCF length + VCF bytes (0 when none)
 *     sender -> receiver: 8B tombstone-block length + UTF-8 lines
 *                         "event|<uid>|<deletedAt>" / "contact|<uid>|<deletedAt>"
 *     receiver applies, then -> "APPLIED <summary>\\n"
 *       (or "APPLY-ERROR <message>\\n" — cursors are NOT advanced then)
 *   then the same three blocks flow listener -> connector.
 *   Both sides set cursor = max(old cursor, max timestamp seen).
 *
 * Pairing continuation (after a v1 one-time share + PARTNER exchange):
 * both sides already know each other's UUID and the socket is
 * code-authenticated, so the sender transmits "SYNC-START\\n", both sides
 * swap "CURSOR <n> <flag>\\n" lines, and the delta exchange runs directly.
 */

/** UI phases for partner sync; each carries a plain-language message. */
sealed interface SyncPhase {
    data object Idle : SyncPhase
    data object Discovering : SyncPhase
    data object Connecting : SyncPhase
    data class Syncing(val detail: String) : SyncPhase
    data class Done(val summary: String) : SyncPhase
    data class Error(val message: String) : SyncPhase
}

/** One side's delta: what changed since the partner's cursor. */
data class SyncDelta(
    val ics: String,
    val vcf: String?,
    val tombstones: List<Tombstone>,
    val eventCount: Int,
    val contactCount: Int
)

data class AppliedCounts(
    val events: Int,
    val contacts: Int,
    val tombstones: Int,
    val maxTs: Long
)

data class SyncResult(
    val sentEvents: Int,
    val sentContacts: Int,
    val sentTombstones: Int,
    val receivedEvents: Int,
    val receivedContacts: Int,
    val receivedTombstones: Int,
    val partnerName: String
) {
    fun summary(): String = buildString {
        append("Synced with $partnerName — sent $sentEvents event")
        if (sentEvents != 1) append("s")
        if (sentContacts > 0) {
            append(" and $sentContacts contact")
            if (sentContacts != 1) append("s")
        }
        append(", received $receivedEvents event")
        if (receivedEvents != 1) append("s")
        if (receivedContacts > 0) {
            append(" and $receivedContacts contact")
            if (receivedContacts != 1) append("s")
        }
        append(".")
    }
}

private const val SYNC_MAGIC = "REMEMBRANCE-SYNC-1"
private const val SYNC_START = "SYNC-START"
private const val MAX_TOMBSTONE_LINES = 100_000

// --- tombstone text block ---

internal fun serializeTombstones(t: List<Tombstone>): String =
    t.joinToString("\n") { "${it.recordType}|${it.uid}|${it.deletedAt}" }

internal fun parseTombstones(s: String): List<Tombstone> =
    s.lineSequence()
        .filter { it.isNotBlank() }
        .take(MAX_TOMBSTONE_LINES)
        .mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 3) return@mapNotNull null
            val ts = p[2].toLongOrNull() ?: return@mapNotNull null
            if (p[0] != Tombstone.EVENT && p[0] != Tombstone.CONTACT) return@mapNotNull null
            Tombstone(p[0], p[1], ts)
        }.toList()

// --- delta building ---

/**
 * Builds this device's delta for [partner]: records with updatedAt newer
 * than [cursor], plus tombstones. Ensures every outgoing record has a
 * stable UID first (assigning one bumps updatedAt, so it is included).
 *
 * [excludeEventUids]/[excludeContactUids]/[excludeTombstones] skip items
 * just applied from the peer in this session (echo avoidance — the peer
 * already has them by construction).
 */
suspend fun buildSyncDelta(
    repo: CalOnyxRepository,
    cursor: Long,
    syncContacts: Boolean,
    excludeEventUids: Set<String> = emptySet(),
    excludeContactUids: Set<String> = emptySet(),
    excludeTombstones: Set<Pair<String, String>> = emptySet()
): SyncDelta = withContext(Dispatchers.IO) {
    // Stable UIDs first: rows without one can't be keyed cross-device.
    for (e in repo.getEventsUpdatedSince(cursor)) {
        if (e.icalUid.isNullOrBlank()) {
            repo.updateEvent(e.copy(icalUid = UUID.randomUUID().toString()))
        }
    }
    val events = repo.getEventsUpdatedSince(cursor)
        .filter { !it.icalUid.isNullOrBlank() && it.icalUid !in excludeEventUids }
    val types = repo.observeEventTypesForSync().associateBy { it.id }
    val pairs = events.map { e ->
        e to (repo.getEventWithContacts(e.id)?.contacts.orEmpty())
    }
    val ics = SyncCodec.eventsToIcs(pairs, types)

    val contacts: List<Contact> = if (syncContacts) {
        // Stable UIDs first (same as events above).
        for (c in repo.getContactsUpdatedSince(cursor)) {
            if (c.vcardUid.isNullOrBlank()) {
                repo.updateContact(c.copy(vcardUid = UUID.randomUUID().toString()))
            }
        }
        val updated = repo.getContactsUpdatedSince(cursor)
            .filter { !it.vcardUid.isNullOrBlank() && it.vcardUid !in excludeContactUids }
        // Referenced contacts: an event's links must resolve on the other
        // side even when the contact itself hasn't changed recently.
        val referencedIds = pairs.flatMap { (_, cs) -> cs.map { it.id } }.toSet()
        val referenced = if (referencedIds.isEmpty()) emptyList()
        else repo.getAllContacts().filter { it.id in referencedIds }
        (updated + referenced).distinctBy { it.id }
            .filter { !it.vcardUid.isNullOrBlank() && it.vcardUid !in excludeContactUids }
    } else emptyList()
    val vcf = if (contacts.isNotEmpty()) {
        VcardCodec.generate(contacts.map { it to null })
    } else null

    val tombstones = repo.getTombstonesSince(cursor)
        .filter { (it.recordType to it.uid) !in excludeTombstones }

    SyncDelta(
        ics = ics,
        vcf = vcf,
        tombstones = tombstones,
        eventCount = events.size,
        contactCount = contacts.size
    )
}

// --- delta applying ---

/**
 * Applies an incoming delta with last-writer-wins merge, keyed by UID.
 *
 * Rules (documented for Master):
 * - Unknown UID → insert.
 * - Known UID → newer updatedAt wins; exact tie → the lexicographically
 *   larger canonical serialization wins (deterministic on both sides).
 * - Incoming tombstone vs local record → newer timestamp wins; on exact
 *   tie the tombstone wins (its 'T' marker sorts after records).
 * - Incoming record vs local tombstone → record wins only if strictly
 *   newer; otherwise the delete stands.
 * - Event types never sync: the incoming type NAME is matched against
 *   local types case-insensitively (trimmed); no match → None (null).
 * - Contact relationship tags likewise match by name or become null
 *   (never created — tags are per-device taxonomy like types).
 * - Per-device fields are never overwritten: a contact's deviceContactId
 *   and lastSyncedAt stay local.
 * - Contact links resolve via vcardUid; unknown UIDs are dropped. When
 *   [syncContacts] is off, no links are applied at all.
 */
suspend fun applySyncDelta(
    repo: CalOnyxRepository,
    ics: String,
    vcf: String?,
    tombstones: List<Tombstone>,
    syncContacts: Boolean
): AppliedCounts = withContext(Dispatchers.IO) {
    var evApplied = 0
    var coApplied = 0
    var toApplied = 0
    var maxTs = 0L
    val appliedEventUids = mutableSetOf<String>()
    val appliedContactUids = mutableSetOf<String>()
    val appliedTombstones = mutableSetOf<Pair<String, String>>()

    // Tombstones first: a delete beats an older record even if the record
    // arrives in the same delta.
    for (t in tombstones) {
        maxTs = maxOf(maxTs, t.deletedAt)
        val localTomb = repo.getTombstone(t.recordType, t.uid)
        if (localTomb != null) {
            if (t.deletedAt > localTomb.deletedAt) {
                repo.writeTombstone(t)
                toApplied++
            }
            appliedTombstones.add(t.recordType to t.uid)
            continue
        }
        val wins: Boolean = when (t.recordType) {
            Tombstone.EVENT -> {
                val local = repo.getEventByIcalUid(t.uid)
                if (local == null) {
                    // Already gone (or never existed): record the tombstone
                    // so it keeps propagating.
                    repo.writeTombstone(t)
                    true
                } else if (t.deletedAt >= local.updatedAt) {
                    // Newer, or exact tie (deterministic: delete wins ties).
                    repo.deleteEventRow(local)
                    repo.writeTombstone(t)
                    true
                } else false
            }
            else -> {
                val local = repo.getContactByVcardUid(t.uid)
                if (local == null) {
                    repo.writeTombstone(t)
                    true
                } else if (t.deletedAt >= local.updatedAt) {
                    repo.deleteContactRow(local)
                    repo.writeTombstone(t)
                    true
                } else false
            }
        }
        if (wins) {
            toApplied++
            appliedTombstones.add(t.recordType to t.uid)
        }
    }

    for (p in SyncCodec.parseEventsIcs(ics)) {
        maxTs = maxOf(maxTs, p.updatedAt)
        // A live local tombstone suppresses the record unless strictly newer.
        val tomb = repo.getTombstone(Tombstone.EVENT, p.uid)
        if (tomb != null && p.updatedAt <= tomb.deletedAt) continue
        val typeId = p.typeName?.let { repo.findEventTypeByName(it)?.id }
        val status = when (p.xStatus?.trim()?.lowercase()) {
            "happened" -> EventStatus.HAPPENED_RECORDED
            "didnt_happen", "didn't_happen", "didnothappen" -> EventStatus.DID_NOT_HAPPEN
            else -> EventStatus.UNMARKED
        }
        val local = repo.getEventByIcalUid(p.uid)
        val incoming = Event(
            id = local?.id ?: 0L,
            title = p.title.ifBlank { "Synced event" },
            startTime = p.startMillis,
            endTime = p.endMillis.coerceAtLeast(p.startMillis),
            location = p.location,
            description = p.description,
            eventTypeId = typeId,
            status = status,
            icalUid = p.uid,
            recurrenceRule = p.rrule,
            seriesId = p.seriesId,
            updatedAt = p.updatedAt
        )
        val shouldWrite = when {
            local == null -> true
            p.updatedAt > local.updatedAt -> true
            p.updatedAt == local.updatedAt &&
                canonicalEvent(incoming) > canonicalEvent(local) -> true
            else -> false
        }
        if (!shouldWrite) continue
        val eventId = if (local == null) {
            repo.writeEventForSync(incoming.copy(id = 0L))
            // writeEventForSync returns Unit; fetch the new id by UID.
            repo.getEventByIcalUid(p.uid)?.id ?: continue
        } else {
            repo.writeEventForSync(incoming)
            local.id
        }
        if (syncContacts) {
            val contactIds = p.contactUids.mapNotNull {
                repo.getContactByVcardUid(it)?.id
            }
            repo.setEventContactLinks(eventId, contactIds)
        } else {
            repo.setEventContactLinks(eventId, emptyList())
        }
        evApplied++
        appliedEventUids.add(p.uid)
    }

    if (syncContacts && !vcf.isNullOrEmpty()) {
        for (p in VcardCodec.parse(vcf)) {
            val incomingTs = p.updatedAt ?: 0L
            maxTs = maxOf(maxTs, incomingTs)
            val tomb = repo.getTombstone(Tombstone.CONTACT, p.uid)
            if (tomb != null && incomingTs <= tomb.deletedAt) continue
            val tagId = p.relationship?.let { repo.findTagByName(it)?.id }
            val local = repo.getContactByVcardUid(p.uid)
            val incoming = Contact(
                id = local?.id ?: 0L,
                displayName = p.displayName,
                firstName = p.firstName,
                lastName = p.lastName,
                phone = p.phone,
                email = p.email,
                address = p.address,
                notes = p.notes,
                relationshipTagId = tagId,
                // Per-device fields are never overwritten by sync.
                deviceContactId = local?.deviceContactId,
                lastSyncedAt = local?.lastSyncedAt,
                vcardUid = p.uid,
                updatedAt = incomingTs
            )
            val shouldWrite = when {
                local == null -> true
                incomingTs > local.updatedAt -> true
                incomingTs == local.updatedAt &&
                    canonicalContact(incoming) > canonicalContact(local) -> true
                else -> false
            }
            if (!shouldWrite) continue
            repo.writeContactForSync(incoming)
            coApplied++
            appliedContactUids.add(p.uid)
        }
    }

    AppliedCounts(evApplied, coApplied, toApplied, maxTs)
}

/** Canonical serialization for the deterministic tiebreak (events). */
private fun canonicalEvent(e: Event): String = listOf(
    e.icalUid.orEmpty(), e.startTime.toString(), e.endTime.toString(),
    e.title, e.description.orEmpty(), e.location.orEmpty(),
    e.eventTypeId?.toString().orEmpty(), e.status.name,
    e.seriesId.orEmpty(), e.recurrenceRule.orEmpty(), e.updatedAt.toString()
).joinToString("\u0001")

/** Canonical serialization for the deterministic tiebreak (contacts). */
private fun canonicalContact(c: Contact): String = listOf(
    c.vcardUid.orEmpty(), c.displayName, c.firstName.orEmpty(),
    c.lastName.orEmpty(), c.phone.orEmpty(), c.email.orEmpty(),
    c.address.orEmpty(), c.notes.orEmpty(),
    c.relationshipTagId?.toString().orEmpty(), c.updatedAt.toString()
).joinToString("\u0001")

// --- transport ---

/**
 * Sends one delta (three length-prefixed blocks) over [out].
 * Shared by the standalone SYNC-1 flow and the pairing continuation.
 */
private fun sendDeltaBlocks(
    out: java.io.OutputStream,
    delta: SyncDelta
) {
    val icsBytes = delta.ics.toByteArray(Charsets.UTF_8)
    val vcfBytes = delta.vcf?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
    val tombBytes = serializeTombstones(delta.tombstones).toByteArray(Charsets.UTF_8)
    out.writeLongBe(icsBytes.size.toLong())
    out.write(icsBytes)
    out.writeLongBe(vcfBytes.size.toLong())
    out.write(vcfBytes)
    out.writeLongBe(tombBytes.size.toLong())
    out.write(tombBytes)
    out.flush()
}

private data class ReceivedDelta(
    val ics: String,
    val vcf: String?,
    val tombstones: List<Tombstone>
)

private fun receiveDeltaBlocks(inp: java.io.InputStream): ReceivedDelta {
    val ics = inp.readExactly(inp.readLongBe()).toString(Charsets.UTF_8)
    val vcfBytes = inp.readExactly(inp.readLongBe())
    val tombBytes = inp.readExactly(inp.readLongBe())
    return ReceivedDelta(
        ics = ics,
        vcf = vcfBytes.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8),
        tombstones = parseTombstones(tombBytes.toString(Charsets.UTF_8))
    )
}

/**
 * The two-way exchange on an already-open socket. The connector sends its
 * delta first (records newer than [peerCursor]); the listener applies it,
 * then sends its own delta (excluding UIDs just applied — the connector
 * already has them by construction).
 *
 * Cursors are advanced only by the caller, after both directions complete
 * cleanly — a failed session retries the same delta next time (idempotent).
 */
suspend fun exchangeDeltas(
    socket: Socket,
    repo: CalOnyxRepository,
    partner: Partner,
    /** This side's cursor for the partner (what we've already received). */
    myCursor: Long,
    /** The peer's cursor for us (what they've already received). */
    peerCursor: Long,
    syncContacts: Boolean,
    isConnector: Boolean,
    onPhase: (SyncPhase) -> Unit
): SyncResult = withContext(Dispatchers.IO) {
    val inp = socket.getInputStream()
    val out = socket.getOutputStream()
    var sentEvents = 0
    var sentContacts = 0
    var sentTombstones = 0
    var applied = AppliedCounts(0, 0, 0, 0)
    var newCursor = myCursor

    if (isConnector) {
        onPhase(SyncPhase.Syncing("Sending changes…"))
        val delta = buildSyncDelta(repo, peerCursor, syncContacts)
        sendDeltaBlocks(out, delta)
        sentEvents = delta.eventCount
        sentContacts = delta.contactCount
        sentTombstones = delta.tombstones.size
        when (inp.readLineUtf8(64)) {
            "APPLIED" -> Unit
            else -> throw IOException("The other device reported an apply error.")
        }
        onPhase(SyncPhase.Syncing("Receiving changes…"))
        val incoming = receiveDeltaBlocks(inp)
        applied = applySyncDelta(repo, incoming.ics, incoming.vcf, incoming.tombstones, syncContacts)
        out.writeLine("APPLIED")
        newCursor = maxOf(newCursor, applied.maxTs, deltaMaxTs(delta))
    } else {
        onPhase(SyncPhase.Syncing("Receiving changes…"))
        val incoming = receiveDeltaBlocks(inp)
        applied = applySyncDelta(repo, incoming.ics, incoming.vcf, incoming.tombstones, syncContacts)
        out.writeLine("APPLIED")
        newCursor = maxOf(newCursor, applied.maxTs)
        onPhase(SyncPhase.Syncing("Sending changes…"))
        val delta = buildSyncDelta(
            repo, peerCursor, syncContacts,
            excludeEventUids = incoming.ics.let {
                SyncCodec.parseEventsIcs(it).map { e -> e.uid }.toSet()
            },
            excludeContactUids = incoming.vcf?.let {
                VcardCodec.parse(it).map { c -> c.uid }.toSet()
            } ?: emptySet(),
            excludeTombstones = incoming.tombstones.map { it.recordType to it.uid }.toSet()
        )
        sendDeltaBlocks(out, delta)
        sentEvents = delta.eventCount
        sentContacts = delta.contactCount
        sentTombstones = delta.tombstones.size
        when (inp.readLineUtf8(64)) {
            "APPLIED" -> Unit
            else -> throw IOException("The other device reported an apply error.")
        }
        newCursor = maxOf(newCursor, deltaMaxTs(delta))
    }
    repo.updatePartnerSyncState(partner.uuid, newCursor, socket.remoteAddressStr())
    SyncResult(
        sentEvents = sentEvents,
        sentContacts = sentContacts,
        sentTombstones = sentTombstones,
        receivedEvents = applied.events,
        receivedContacts = applied.contacts,
        receivedTombstones = applied.tombstones,
        partnerName = partner.displayName
    )
}

/** Max updatedAt across a delta's own records (for cursor advancement). */
private suspend fun deltaMaxTs(delta: SyncDelta): Long = withContext(Dispatchers.IO) {
    var m = 0L
    for (e in SyncCodec.parseEventsIcs(delta.ics)) m = maxOf(m, e.updatedAt)
    delta.vcf?.let { vcf ->
        for (c in VcardCodec.parse(vcf)) m = maxOf(m, c.updatedAt ?: 0L)
    }
    for (t in delta.tombstones) m = maxOf(m, t.deletedAt)
    m
}

private fun Socket.remoteAddressStr(): String? = try {
    (remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress
} catch (_: Exception) {
    null
}

// --- standalone SYNC-1 session ---

/**
 * Connector side of a standalone partner sync (Settings → Sync with
 * partner). Performs the HELLO/WELCOME handshake — the listener rejects
 * unrecognized UUIDs — then runs the two-way exchange.
 */
suspend fun runSyncAsConnector(
    host: String,
    partner: Partner,
    repo: CalOnyxRepository,
    selfUuid: String,
    selfName: String,
    onPhase: (SyncPhase) -> Unit
): SyncResult = withContext(Dispatchers.IO) {
    val target = host.trim().substringBefore(':')
    try {
        onPhase(SyncPhase.Connecting)
        val socket = Socket()
        socket.connect(InetSocketAddress(target, SYNC_PORT), 10_000)
        socket.use { s ->
            s.soTimeout = 90_000
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.writeLine(SYNC_MAGIC)
            out.writeLine("HELLO")
            out.writeLine(selfUuid)
            out.writeLine(selfName)
            out.writeLine("${partner.lastSyncAt} ${if (partner.syncContacts) 1 else 0}")
            when (inp.readLineUtf8(64)) {
                "WELCOME" -> Unit
                "REJECT" -> throw IOException(
                    "This device doesn't recognize you as a partner. " +
                        "Pair again from the share screen."
                )
                else -> throw IOException("The other device didn't respond as expected.")
            }
            val peerUuid = inp.readLineUtf8(128) ?: throw IOException("Connection lost.")
            val peerName = inp.readLineUtf8(128) ?: ""
            val peerCursorLine = inp.readLineUtf8(64) ?: "0 0"
            if (peerUuid != partner.uuid) throw IOException(
                "Connected to an unexpected device (not ${partner.displayName})."
            )
            val peerParts = peerCursorLine.trim().split(' ')
            val peerCursor = peerParts.getOrNull(0)?.toLongOrNull() ?: 0L
            val peerWantsContacts = peerParts.getOrNull(1) == "1"
            val syncContacts = partner.syncContacts && peerWantsContacts
            exchangeDeltas(
                socket = s, repo = repo, partner = partner,
                myCursor = partner.lastSyncAt, peerCursor = peerCursor,
                syncContacts = syncContacts, isConnector = true, onPhase = onPhase
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("Sync failed (${e.message ?: "unknown error"}).")
    }
}

/**
 * Listener for standalone partner syncs. Created on the sync UI and
 * started/stopped with it — like [ShareHost], the server lives only while
 * the UI is open. Unknown UUIDs get REJECT.
 */
class SyncListener(
    private val repo: CalOnyxRepository,
    private val selfUuid: () -> String,
    private val selfName: () -> String,
    private val onPhase: (SyncPhase) -> Unit
) {
    private val server: ServerSocket? = try {
        ServerSocket(SYNC_PORT)
    } catch (_: IOException) {
        null
    }

    @Volatile
    private var running = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (server == null) {
            onPhase(SyncPhase.Error("Couldn't start listening — is a sync already running?"))
            return
        }
        running = true
        job = scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        job?.cancel()
    }

    private suspend fun acceptLoop() {
        val srv = server ?: return
        while (running) {
            val socket = try {
                srv.accept()
            } catch (_: IOException) {
                return // closed on navigate-away
            }
            handleOne(socket)
        }
    }

    private suspend fun handleOne(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 90_000
                val inp = s.getInputStream()
                val out = s.getOutputStream()
                if (inp.readLineUtf8(64) != SYNC_MAGIC) return
                if (inp.readLineUtf8(16) != "HELLO") return
                val peerUuid = inp.readLineUtf8(128) ?: return
                val peerName = inp.readLineUtf8(128) ?: "Unknown device"
                val peerLine = inp.readLineUtf8(64) ?: "0 0"
                val partner = repo.getPartner(peerUuid)
                if (partner == null) {
                    out.writeLine("REJECT")
                    return
                }
                val peerParts = peerLine.trim().split(' ')
                val peerCursor = peerParts.getOrNull(0)?.toLongOrNull() ?: 0L
                val peerWantsContacts = peerParts.getOrNull(1) == "1"
                val syncContacts = partner.syncContacts && peerWantsContacts
                out.writeLine("WELCOME")
                out.writeLine(selfUuid())
                out.writeLine(selfName())
                out.writeLine("${partner.lastSyncAt} ${if (partner.syncContacts) 1 else 0}")
                exchangeDeltas(
                    socket = s, repo = repo, partner = partner,
                    myCursor = partner.lastSyncAt, peerCursor = peerCursor,
                    syncContacts = syncContacts, isConnector = false, onPhase = onPhase
                )
                onPhase(SyncPhase.Done("Synced with $peerName."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Malformed session or disconnect: keep listening.
        }
    }
}

// --- mDNS discovery (any Wi-Fi, same network) ---

/** A discovered Remembrance instance on the local network. */
data class DiscoveredPeer(
    val uuid: String,
    val name: String,
    val host: String,
    val port: Int
)

private const val NSD_SERVICE_TYPE = "_remembrance._tcp."

/**
 * Advertises this install on the LAN and discovers peers, using the
 * framework NsdManager (no new dependency, no new permission). Both run
 * only while the sync UI is open.
 *
 * The TXT record carries the stable app UUID so remembered partners are
 * recognized by identity, not by IP address (which changes per network).
 */
class PartnerNsd(
    context: Context,
    private val selfUuid: String,
    private val selfName: String
) {
    private val nsd =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())

    fun startAdvertise() {
        val info = NsdServiceInfo().apply {
            serviceName = "Remembrance"
            serviceType = NSD_SERVICE_TYPE
            port = SYNC_PORT
            setAttribute("uuid", selfUuid)
            setAttribute("name", selfName)
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, advertiseListener)
    }

    fun stopAdvertise() {
        runCatching { nsd.unregisterService(advertiseListener) }
    }

    private val advertiseListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }

    fun startDiscovery(
        onPeer: (DiscoveredPeer) -> Unit,
        onLost: (DiscoveredPeer) -> Unit
    ) {
        val seen = mutableMapOf<String, DiscoveredPeer>() // keyed by uuid
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceResolved(info: NsdServiceInfo) {
                handler.post {
                    val uuid = info.attributes["uuid"]?.toString(Charsets.UTF_8) ?: return@post
                    if (uuid == selfUuid) return@post // ignore ourselves
                    val name = info.attributes["name"]?.toString(Charsets.UTF_8)
                        ?: info.serviceName
                    val host = info.host?.hostAddress ?: return@post
                    val peer = DiscoveredPeer(uuid, name, host, info.port)
                    seen[uuid] = peer
                    onPeer(peer)
                }
            }
        }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                try {
                    nsd.resolveService(info, resolveListener)
                } catch (_: Exception) {
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                handler.post {
                    // We only know the service name here; drop by matching
                    // resolved entries is best-effort.
                    val gone = seen.values.firstOrNull { it.name == info.serviceName }
                    if (gone != null) {
                        seen.remove(gone.uuid)
                        onLost(gone)
                    }
                }
            }
        }
        activeDiscovery = discoveryListener
        nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    private var activeDiscovery: NsdManager.DiscoveryListener? = null

    fun stopDiscovery() {
        activeDiscovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        activeDiscovery = null
    }
}

// --- misc ---

/** Repository one-shot for event types (delta building needs the map). */
suspend fun CalOnyxRepository.observeEventTypesForSync() =
    observeEventTypes().first()

/** Display name sanitized for the wire (single line, bounded). */
fun sanitizeDisplayName(raw: String): String =
    raw.replace('\n', ' ').replace('\r', ' ').trim().take(64).ifBlank { "Unknown device" }

/** Default friendly name for pairing: the device model. */
fun defaultDeviceName(): String = try {
    Build.MODEL?.takeIf { it.isNotBlank() } ?: "Unknown device"
} catch (_: Exception) {
    "Unknown device"
}
