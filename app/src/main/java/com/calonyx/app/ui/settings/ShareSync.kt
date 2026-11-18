package com.calonyx.app.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Device-to-device share transport (M15): plain TCP sockets over the local
 * Wi-Fi network. No Play Services, no new runtime permissions — INTERNET is
 * a normal install-time permission (granted automatically, no prompt).
 *
 * The server listens ONLY while the share screen's Receive mode is open;
 * navigating away closes the socket (see [ShareHost.stop]).
 *
 * Wire protocol (all text is UTF-8):
 *   sender -> receiver: "REMEMBRANCE-SHARE-1\\n"   (magic handshake line)
 *   sender -> receiver: "<4-digit code>\\n"         (typed by the sender)
 *   receiver -> sender: "OK\\n" | "REJECT\\n"
 *   sender -> receiver: 8-byte big-endian ICS length, ICS bytes,
 *                       8-byte big-endian VCF length (0 when absent), VCF bytes
 *   receiver -> sender: "DONE\\n"                  (after import completes)
 *
 * One-way only: the sender pushes, the receiver imports. Two-way merge is
 * a later milestone.
 */

/** Unprivileged port for device-to-device share. Internal only — the UI shows the bare IP address. */
const val SHARE_PORT = 47821

/**
 * Unprivileged port for M16 partner sync. Separate from [SHARE_PORT] so a
 * partner-sync listener never collides with a one-time share receiver.
 */
const val SYNC_PORT = 47822

/** First line a sender must transmit; stray connections are dropped fast. */
private const val HANDSHAKE_MAGIC = "REMEMBRANCE-SHARE-1"

/** Sanity cap on one payload part (256 MB) so a rogue peer can't OOM us. */
private const val MAX_PART_BYTES = 256L * 1024 * 1024

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 90_000
/** How long the receiver waits for PARTNER/NOPARTNER after DONE. */
private const val PAIRING_TIMEOUT_MS = 8_000

/**
 * The device's site-local IPv4 address (what the sender must type), or null
 * when no Wi-Fi connection is available.
 */
fun localWifiIpAddress(): String? {
    return try {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (ifaces.hasMoreElements()) {
            val iface = ifaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return a.hostAddress
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

/** UI phases for the share screen; each carries a plain-language message. */
sealed interface SharePhase {
    data object Idle : SharePhase
    data class Waiting(val ip: String, val code: String) : SharePhase
    data object Connecting : SharePhase
    data object Sending : SharePhase
    data object Receiving : SharePhase
    data object Importing : SharePhase
    /** M16 pairing continuation: the initial full sync after pairing. */
    data class Syncing(val detail: String) : SharePhase
    data class Done(val detail: String) : SharePhase
    data class Error(val message: String) : SharePhase
}

// --- framing helpers ---

internal fun InputStream.readLineUtf8(maxLen: Int): String? {
    val buf = ByteArrayOutputStream()
    while (true) {
        val b = read()
        if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
        if (b == '\n'.code) break
        if (buf.size() > maxLen) throw IOException("Line too long")
        buf.write(b)
    }
    return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
}

internal fun InputStream.readFully(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n == -1) throw EOFException("Connection closed")
        off += n
    }
}

internal fun InputStream.readLongBe(): Long {
    val b = ByteArray(8)
    readFully(b)
    var v = 0L
    for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
    return v
}

internal fun InputStream.readExactly(n: Long): ByteArray {
    require(n in 0..MAX_PART_BYTES) { "Payload part too large" }
    val out = ByteArrayOutputStream()
    var remaining = n
    val buf = ByteArray(8192)
    while (remaining > 0) {
        val r = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
        if (r == -1) throw EOFException("Connection closed")
        out.write(buf, 0, r)
        remaining -= r
    }
    return out.toByteArray()
}

internal fun OutputStream.writeLine(s: String) {
    write((s + "\n").toByteArray(Charsets.UTF_8))
    flush()
}

internal fun OutputStream.writeLongBe(v: Long) {
    for (i in 7 downTo 0) write(((v ushr (i * 8)) and 0xFF).toInt())
}

// --- receiver ---

/**
 * Listens for one sender at a time. Create with `remember` on the Receive
 * UI, start in a [DisposableEffect], and stop on dispose — that is what
 * keeps the server bound only while the sync screen is open.
 *
 * [onPayload] runs on an IO thread and must do the import itself (the
 * existing M7 import pipeline); its return value becomes the Done detail.
 *
 * M16 pairing: when [partnerOffer] is set, the DONE phase is extended —
 * the sender may transmit "PARTNER" plus its app UUID and display name.
 * When both sides checked the box, the UUIDs are persisted via
 * [onPartnerPaired] and [runSyncContinuation] performs the initial full
 * two-way sync on the same code-authenticated socket. When either side's
 * box is unchecked, the one-time flow is byte-identical to before.
 */
class ShareHost(
    val code: String,
    private val onPhase: (SharePhase) -> Unit,
    private val onPayload: suspend (ics: String, vcf: String?) -> String,
    private val partnerOffer: (() -> Boolean)? = null,
    private val onPartnerPaired: (suspend (peerUuid: String, peerName: String, peerIp: String?) -> Unit)? = null,
    private val ownUuid: (suspend () -> String)? = null,
    private val ownName: (suspend () -> String)? = null,
    private val runSyncContinuation: (suspend (socket: Socket, peerUuid: String) -> SyncResult)? = null
) {
    private val server: ServerSocket? = try {
        ServerSocket(SHARE_PORT)
    } catch (_: IOException) {
        null
    }

    @Volatile
    private var running = false
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (server == null) {
            onPhase(
                SharePhase.Error(
                    "Couldn't start listening — is another share already running on this device?"
                )
            )
            return
        }
        running = true
        job = scope.launch(Dispatchers.IO) { acceptLoop() }
    }

    /** Closes the socket (unblocks accept) and cancels the loop. */
    fun stop() {
        running = false
        runCatching { server?.close() }
        job?.cancel()
    }

    private suspend fun acceptLoop() {
        val srv = server ?: return
        while (running) {
            val ip = localWifiIpAddress()
            if (ip == null) {
                onPhase(
                    SharePhase.Error(
                        "No Wi-Fi connection found. Connect to Wi-Fi and reopen this screen."
                    )
                )
                return
            }
            onPhase(SharePhase.Waiting(ip, code))
            val socket = try {
                srv.accept()
            } catch (_: IOException) {
                // Server closed (navigated away) — exit quietly.
                return
            }
            handleOne(socket)
        }
    }

    private suspend fun handleOne(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val inp = s.getInputStream()
                val out = s.getOutputStream()
                if (inp.readLineUtf8(64) != HANDSHAKE_MAGIC) return
                val gotCode = inp.readLineUtf8(16) ?: return
                if (gotCode != code) {
                    out.writeLine("REJECT")
                    return
                }
                out.writeLine("OK")
                onPhase(SharePhase.Receiving)
                val ics = inp.readExactly(inp.readLongBe()).toString(Charsets.UTF_8)
                val vcfBytes = inp.readExactly(inp.readLongBe())
                val vcf = vcfBytes.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)
                onPhase(SharePhase.Importing)
                val detail = onPayload(ics, vcf)
                out.writeLine("DONE")
                onPhase(SharePhase.Done(detail))
                // M16 pairing continuation: the sender may follow DONE with
                // PARTNER (its box was checked) or NOPARTNER; old versions
                // just close, which reads as null. A short timeout keeps
                // this invisible when no pairing is in play.
                try {
                    s.soTimeout = PAIRING_TIMEOUT_MS
                    val cmd = inp.readLineUtf8(16)
                    if (cmd == "PARTNER" && partnerOffer?.invoke() == true &&
                        onPartnerPaired != null && runSyncContinuation != null
                    ) {
                        val peerUuid = inp.readLineUtf8(128)
                        val peerName = inp.readLineUtf8(128) ?: "Unknown device"
                        if (peerUuid != null) {
                            val peerIp = (s.remoteSocketAddress as? InetSocketAddress)
                                ?.address?.hostAddress
                            onPartnerPaired(peerUuid, peerName, peerIp)
                            out.writeLine("PARTNER")
                            out.writeLine(ownUuid?.invoke() ?: "")
                            out.writeLine(ownName?.invoke() ?: "Unknown device")
                            s.soTimeout = READ_TIMEOUT_MS
                            try {
                                val result = runSyncContinuation(s, peerUuid)
                                onPhase(
                                    SharePhase.Done(
                                        "Paired with ${result.partnerName}. ${result.summary()}"
                                    )
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                onPhase(
                                    SharePhase.Error(
                                        "Paired, but the first sync didn't finish " +
                                            "(${e.message ?: "connection lost"}). " +
                                            "Try Sync with partner in Settings."
                                    )
                                )
                            }
                        }
                    } else if (cmd == "PARTNER") {
                        // Sender checked, we didn't: drain its two identity
                        // lines, then decline cleanly.
                        inp.readLineUtf8(128)
                        inp.readLineUtf8(128)
                        out.writeLine("NOPARTNER")
                    }
                    // NOPARTNER / null / anything else: leave the share as-is.
                } catch (_: Exception) {
                    // Sender already gone — the share itself succeeded.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Mid-transfer disconnect or malformed data: back to waiting.
            if (running) {
                onPhase(SharePhase.Waiting(localWifiIpAddress() ?: "?", code))
            }
        }
    }
}

// --- sender ---

/**
 * Connects to a receiver, verifies the pairing code, and pushes the
 * payload. All plain-language outcomes are reported via [onPhase].
 *
 * M16 pairing: when [rememberPartner] is true, after DONE the sender
 * transmits its app UUID and display name; when the receiver also checked
 * the box, the partnership is persisted and [runSyncContinuation] performs
 * the initial full two-way sync on the same socket. When the box is
 * unchecked, the flow is byte-identical to before (one NOPARTNER line is
 * sent, best-effort — old receivers have already closed).
 */
suspend fun sendShare(
    host: String,
    code: String,
    ics: String,
    vcf: String?,
    eventCount: Int,
    contactCount: Int,
    onPhase: (SharePhase) -> Unit,
    rememberPartner: Boolean = false,
    ownUuid: (suspend () -> String)? = null,
    ownName: (suspend () -> String)? = null,
    onPartnerPaired: (suspend (peerUuid: String, peerName: String, peerIp: String?) -> Unit)? = null,
    runSyncContinuation: (suspend (socket: Socket, peerUuid: String) -> SyncResult)? = null
) {
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        // Tolerate a pasted "IP:port": the port is fixed on our side,
        // so strip anything from the first ':' onward. (IPv4 only —
        // the receiver only ever shows a site-local IPv4 address.)
        val target = host.trim().substringBefore(':')
        try {
            onPhase(SharePhase.Connecting)
            val socket = Socket()
            socket.connect(InetSocketAddress(target, SHARE_PORT), CONNECT_TIMEOUT_MS)
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val out = s.getOutputStream()
                val inp = s.getInputStream()
                out.writeLine(HANDSHAKE_MAGIC)
                out.writeLine(code.trim())
                when (inp.readLineUtf8(16)) {
                    "OK" -> Unit
                    "REJECT" -> {
                        onPhase(
                            SharePhase.Error(
                                "The code didn't match. Check the code on the " +
                                    "receiving device and try again."
                            )
                        )
                        return@withContext
                    }
                    else -> {
                        onPhase(
                            SharePhase.Error("The other device didn't respond as expected.")
                        )
                        return@withContext
                    }
                }
                onPhase(SharePhase.Sending)
                val icsBytes = ics.toByteArray(Charsets.UTF_8)
                val vcfBytes = vcf?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                out.writeLongBe(icsBytes.size.toLong())
                out.write(icsBytes)
                out.writeLongBe(vcfBytes.size.toLong())
                out.write(vcfBytes)
                out.flush()
                if (inp.readLineUtf8(16) == "DONE") {
                    val what = buildString {
                        append("Sent $eventCount event")
                        if (eventCount != 1) append("s")
                        if (contactCount > 0) {
                            append(" and $contactCount contact")
                            if (contactCount != 1) append("s")
                        }
                    }
                    if (!rememberPartner) {
                        // Best-effort: tell the receiver no pairing follows.
                        // Old receivers already closed; the write may fail.
                        runCatching { out.writeLine("NOPARTNER") }
                        onPhase(SharePhase.Done("$what. The other device has imported them."))
                    } else {
                        try {
                            s.soTimeout = PAIRING_TIMEOUT_MS
                            out.writeLine("PARTNER")
                            out.writeLine(ownUuid?.invoke() ?: "")
                            out.writeLine(ownName?.invoke() ?: "Unknown device")
                            when (inp.readLineUtf8(16)) {
                                "PARTNER" -> {
                                    val peerUuid = inp.readLineUtf8(128)
                                    val peerName = inp.readLineUtf8(128) ?: "Unknown device"
                                    if (peerUuid != null && onPartnerPaired != null &&
                                        runSyncContinuation != null
                                    ) {
                                        val peerIp = (s.remoteSocketAddress as? InetSocketAddress)
                                            ?.address?.hostAddress
                                        onPartnerPaired(peerUuid, peerName, peerIp)
                                        s.soTimeout = READ_TIMEOUT_MS
                                        try {
                                            val result = runSyncContinuation(s, peerUuid)
                                            onPhase(
                                                SharePhase.Done(
                                                    "$what. Paired with ${result.partnerName}. " +
                                                        result.summary()
                                                )
                                            )
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            onPhase(
                                                SharePhase.Error(
                                                    "Paired, but the first sync didn't finish " +
                                                        "(${e.message ?: "connection lost"}). " +
                                                        "Try Sync with partner in Settings."
                                                )
                                            )
                                        }
                                    } else {
                                        onPhase(SharePhase.Done("$what. The other device has imported them."))
                                    }
                                }
                                else -> {
                                    // NOPARTNER / timeout / old receiver gone.
                                    onPhase(
                                        SharePhase.Done(
                                            "$what. The other device has imported them. " +
                                                "(Partner pairing skipped — the other device " +
                                                "didn't enable it.)"
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                            onPhase(SharePhase.Done("$what. The other device has imported them."))
                        }
                    }
                } else {
                    onPhase(
                        SharePhase.Error("The other device disconnected before finishing.")
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            onPhase(
                SharePhase.Error(
                    "Connection timed out. Make sure both devices are on the " +
                        "same Wi-Fi network."
                )
            )
        } catch (_: UnknownHostException) {
            onPhase(
                SharePhase.Error("Couldn't reach \"$target\". Check the address and try again.")
            )
        } catch (_: ConnectException) {
            onPhase(
                SharePhase.Error(
                    "Couldn't connect. Make sure the other device is on the " +
                        "Receive screen and you're on the same Wi-Fi."
                )
            )
        } catch (e: IOException) {
            onPhase(
                SharePhase.Error("Connection lost (${e.message ?: "network error"}). Try again.")
            )
        }
    }
}
