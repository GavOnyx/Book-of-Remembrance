package com.calonyx.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calonyx.app.data.Partner
import com.calonyx.app.data.importexport.ImportExportManager.ExportSpec
import com.calonyx.app.ui.components.BackIcon
import com.calonyx.app.ui.components.CopyIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** In-flight share payload built from the export filters. */
private data class PendingShare(
    val ics: String,
    val vcf: String?,
    val eventCount: Int,
    val contactCount: Int
)

private enum class ShareMode(val label: String) {
    RECEIVE("Receive"),
    SEND("Send")
}

/**
 * Device-to-device share screen (M15): Send pushes events/contacts to
 * another device running Remembrance on the same Wi-Fi; Receive listens
 * for one. One-way only — the receiver imports through the existing M7
 * import pipeline (UID dedupe, attendee matching, RRULE expansion).
 *
 * This is a top-level destination (not under the tab Scaffold), so its own
 * Scaffold applies the system insets exactly once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(ShareMode.RECEIVE) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Share with another device") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackIcon(tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ShareMode.values().forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = {
                            Text(
                                m.label,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            when (mode) {
                ShareMode.RECEIVE -> ReceivePanel(viewModel)
                ShareMode.SEND -> SendPanel(viewModel)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Plain-language status line for the current phase. */
@Composable
private fun PhaseLine(phase: SharePhase) {
    val (text, color) = when (phase) {
        SharePhase.Idle -> "" to MaterialTheme.colorScheme.onSurfaceVariant
        is SharePhase.Waiting -> "Waiting for a sender…" to MaterialTheme.colorScheme.primary
        SharePhase.Connecting -> "Connecting…" to MaterialTheme.colorScheme.primary
        SharePhase.Sending -> "Sending…" to MaterialTheme.colorScheme.primary
        SharePhase.Receiving -> "Receiving…" to MaterialTheme.colorScheme.primary
        SharePhase.Importing -> "Importing…" to MaterialTheme.colorScheme.primary
        is SharePhase.Done -> phase.detail to MaterialTheme.colorScheme.primary
        is SharePhase.Syncing -> phase.detail to MaterialTheme.colorScheme.primary
        is SharePhase.Error -> phase.message to MaterialTheme.colorScheme.error
    }
    if (text.isNotEmpty()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/**
 * M16 pairing checkbox: "Remember as sync partner". When checked on both
 * sides of a successful one-time share, the devices exchange app UUIDs and
 * are remembered as sync partners, then run an initial full two-way sync.
 */
@Composable
private fun PartnerCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            "Remember as sync partner",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
    Text(
        "Both sides must check this. After pairing, use Settings → " +
            "Sync with partner for two-way sync.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}
/**
 * Runs the import pipeline on a received payload and returns a plain-
 * language counts phrase, e.g. "12 events and 3 contacts". Callers add
 * their own verb ("Imported …" / "Received …").
 */
private suspend fun importReceivedCounts(
    viewModel: SettingsViewModel,
    ics: String,
    vcf: String?
): String {
    val autoCreate = viewModel.appSettings.first().autoCreateContactsOnImport
    val e = viewModel.applyIcsImport(ics, autoCreate)
    val c = if (!vcf.isNullOrEmpty()) viewModel.applyVcfImport(vcf) else null
    return buildString {
        append("${e.eventsImported} event")
        if (e.eventsImported != 1) append("s")
        val ci = c?.contactsImported ?: 0
        if (ci > 0) {
            append(" and $ci contact")
            if (ci != 1) append("s")
        }
        if (c != null && c.contactsAutoCreated > 0) {
            append(" (${c.contactsAutoCreated} new from attendees)")
        }
        val skipped = e.eventsSkipped + (c?.contactsSkipped ?: 0)
        if (skipped > 0) append(" — $skipped already imported, skipped")
    }.toString()
}

@Composable
private fun ReceivePanel(viewModel: SettingsViewModel) {
    // Fresh 4-digit code each time Receive mode is entered. Single-use and
    // verified before any payload flows, so 4 digits are plenty: an
    // attacker would have to be on the same Wi-Fi during the short window.
    val code = remember { "%04d".format((0..9999).random()) }
    var phase by remember { mutableStateOf<SharePhase>(SharePhase.Idle) }
    // Latched on a successful import so the confirmation popup survives the
    // host looping back to Waiting; cleared by the popup's OK button.
    var receivedCounts by remember { mutableStateOf<String?>(null) }
    // M16 pairing: remember this sender as a sync partner after a
    // successful share, when the sender also checks the box.
    var rememberPartner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val host = remember {
        ShareHost(
            code = code,
            onPhase = { phase = it },
            onPayload = { ics, vcf ->
                val counts = importReceivedCounts(viewModel, ics, vcf)
                receivedCounts = counts
                "Imported $counts."
            },
            partnerOffer = { rememberPartner },
            onPartnerPaired = { peerUuid, peerName, peerIp ->
                viewModel.addPartner(
                    Partner(
                        uuid = peerUuid,
                        displayName = peerName,
                        lastKnownIp = peerIp,
                        lastSyncAt = 0L,
                        syncContacts = true,
                        createdAt = System.currentTimeMillis()
                    )
                )
            },
            ownUuid = { viewModel.getOrCreateDeviceUuid() },
            ownName = { defaultDeviceName() },
            runSyncContinuation = { socket, peerUuid ->
                viewModel.runPairingSyncAsListener(socket, peerUuid) { sp ->
                    if (sp is SyncPhase.Syncing) phase = SharePhase.Syncing(sp.detail)
                }
            }
        )
    }
    // The server lives only while this panel is composed: leaving the
    // screen (or switching to Send) closes the socket.
    DisposableEffect(host) {
        host.start(scope)
        onDispose { host.stop() }
    }

    Text(
        "On the other device, open Settings → Share with another device → " +
            "Send, then enter this address and code.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))
    Text(
        "Your code",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                code,
                style = MaterialTheme.typography.displayMedium,
                fontSize = 56.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
            CopyIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val waiting = phase as? SharePhase.Waiting
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                "Your address: ${waiting?.ip ?: "…"}",
                style = MaterialTheme.typography.bodyLarge
            )
        }
        IconButton(
            onClick = {
                waiting?.ip?.let { clipboard.setText(AnnotatedString(it)) }
            }
        ) {
            CopyIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    PhaseLine(phase)
    PartnerCheckbox(
        checked = rememberPartner,
        onCheckedChange = { rememberPartner = it }
    )
    Text(
        "Keep this screen open while sharing. You can receive more than " +
            "once without leaving.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp)
    )

    // Success confirmation: what arrived, dismissed only via OK.
    receivedCounts?.let { counts ->
        AlertDialog(
            onDismissRequest = { /* dismissed via OK only */ },
            title = { Text("Received") },
            text = { Text("Received $counts.") },
            confirmButton = {
                TextButton(onClick = { receivedCounts = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SendPanel(viewModel: SettingsViewModel) {
    val eventTypes by viewModel.eventTypes.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    var payload by remember { mutableStateOf<PendingShare?>(null) }
    // v0.20.7: TextFieldValue state so the keyboard's composing
    // region survives recompositions (backspace space-eating fix).
    var ip by remember { mutableStateOf(TextFieldValue("")) }
    var code by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf<SharePhase>(SharePhase.Idle) }
    // M16 pairing: remember this receiver as a sync partner after a
    // successful share, when the receiver also checks the box.
    var rememberPartner by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = phase is SharePhase.Connecting || phase is SharePhase.Sending ||
        phase is SharePhase.Syncing

    val p = payload
    if (p == null) {
        Text(
            "Choose which events and contacts to send. The other device " +
                "must be on the Receive screen on the same Wi-Fi network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Choose what to share…") }
    } else {
        Text(
            "${p.eventCount} event(s), ${p.contactCount} contact(s) ready to send.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        TextButton(onClick = { payload = null }) { Text("Choose different…") }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = ip,
            onValueChange = { ip = it },
            keyboardOptions = KeyboardOptions(autoCorrect = false),
            label = { Text("Receiver's address") },
            placeholder = { Text("192.168.1.5") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(4) },
            label = { Text("Code") },
            placeholder = { Text("4 digits") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        PartnerCheckbox(
            checked = rememberPartner,
            onCheckedChange = { rememberPartner = it }
        )
        Button(
            onClick = {
                val offer = rememberPartner
                scope.launch(Dispatchers.IO) {
                    sendShare(
                        host = ip.text,
                        code = code,
                        ics = p.ics,
                        vcf = p.vcf,
                        eventCount = p.eventCount,
                        contactCount = p.contactCount,
                        onPhase = { phase = it },
                        rememberPartner = offer,
                        ownUuid = { viewModel.getOrCreateDeviceUuid() },
                        ownName = { defaultDeviceName() },
                        onPartnerPaired = { peerUuid, peerName, peerIp ->
                            viewModel.addPartner(
                                Partner(
                                    uuid = peerUuid,
                                    displayName = peerName,
                                    lastKnownIp = peerIp,
                                    lastSyncAt = 0L,
                                    syncContacts = true,
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                        },
                        runSyncContinuation = { socket, peerUuid ->
                            viewModel.runPairingSyncAsConnector(socket, peerUuid) { sp ->
                                if (sp is SyncPhase.Syncing) {
                                    phase = SharePhase.Syncing(sp.detail)
                                }
                            }
                        }
                    )
                }
            },
            enabled = ip.text.isNotBlank() && code.length == 4 && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send") }
    }
    PhaseLine(phase)

    if (showDialog) {
        ExportDialog(
            viewModel = viewModel,
            eventTypes = eventTypes,
            contacts = contacts,
            onDismiss = { showDialog = false },
            onConfirm = { spec: ExportSpec ->
                showDialog = false
                scope.launch(Dispatchers.IO) {
                    val preview = viewModel.previewExport(spec)
                    val ics = viewModel.buildExportIcs(preview)
                    val vcf =
                        if (spec.includeContacts && preview.contacts.isNotEmpty())
                            viewModel.buildExportVcf(preview.contacts)
                        else null
                    payload = PendingShare(
                        ics = ics,
                        vcf = vcf,
                        eventCount = preview.events.size,
                        contactCount = preview.contacts.size
                    )
                }
            },
            title = "Share with another device",
            confirmLabel = "Share"
        )
    }
}
