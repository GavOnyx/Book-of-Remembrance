package com.calonyx.app.ui.settings
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.Partner
import com.calonyx.app.ui.components.BackIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun lastSyncedLabel(lastSyncAt: Long): String =
    if (lastSyncAt <= 0) "Never synced"
    else "Last synced " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        .format(Date(lastSyncAt))

/**
 * Partner list (M16): shown from "Sync with partner" when more than one
 * partner is remembered. Per partner: name, last-synced time, a Sync
 * button, rename, forget (remove), and the per-partner contacts toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerListScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onSyncPartner: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val partners by viewModel.observePartners().collectAsState(initial = emptyList())
    var renameTarget by remember { mutableStateOf<Partner?>(null) }
    var forgetTarget by remember { mutableStateOf<Partner?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sync partners") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        BackIcon(tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
            )
        }
    ) { inner ->
        if (partners.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "No sync partners yet.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Share with another device and check “Remember as sync " +
                        "partner” on both sides to pair.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(partners, key = { it.uuid }) { partner ->
                PartnerCard(
                    partner = partner,
                    onSync = { onSyncPartner(partner.uuid) },
                    onRename = { renameTarget = partner },
                    onForget = { forgetTarget = partner },
                    onToggleContacts = {
                        viewModel.setPartnerSyncContacts(partner.uuid, it)
                    }
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    renameTarget?.let { partner ->
        // v0.20.7: TextFieldValue state so the keyboard's composing
        // region survives recompositions (backspace space-eating fix).
        var name by remember(partner.uuid) { mutableStateOf(TextFieldValue(partner.displayName)) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename partner") },
            text = {
                OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = name.text.trim()
                        if (clean.isNotEmpty()) {
                            viewModel.renamePartner(partner.uuid, clean)
                        }
                        renameTarget = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    forgetTarget?.let { partner ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget ${partner.displayName}?") },
            text = {
                Text(
                    "This removes them from your partner list. Your events " +
                        "and contacts stay as they are."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removePartner(partner.uuid)
                        forgetTarget = null
                    }
                ) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text("Keep") }
            }
        )
    }
}

@Composable
private fun PartnerCard(
    partner: Partner,
    onSync: () -> Unit,
    onRename: () -> Unit,
    onForget: () -> Unit,
    onToggleContacts: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(partner.displayName, style = MaterialTheme.typography.titleLarge)
            Text(
                lastSyncedLabel(partner.lastSyncAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSync, modifier = Modifier.fillMaxWidth()) {
                Text("Sync with ${partner.displayName}")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                    Text("Rename")
                }
                OutlinedButton(onClick = onForget, modifier = Modifier.weight(1f)) {
                    Text("Forget")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sync contacts", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When off, only events sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = partner.syncContacts,
                    onCheckedChange = onToggleContacts
                )
            }
        }
    }
}

/**
 * Sync-with-partner screen (M16): while open, this device advertises
 * itself and listens for the partner (mDNS discovery + manual IP
 * fallback). Tap to sync: a two-way delta merge runs, then a plain-
 * language summary is shown. Manual sync only — nothing runs in the
 * background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerSyncScreen(
    viewModel: SettingsViewModel,
    partnerUuid: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val partners by viewModel.observePartners().collectAsState(initial = emptyList())
    val partner = partners.firstOrNull { it.uuid == partnerUuid }

    var selfUuid by remember { mutableStateOf<String?>(null) }
    var selfName by remember { mutableStateOf(defaultDeviceName()) }
    var phase by remember { mutableStateOf<SyncPhase>(SyncPhase.Idle) }
    // True while WE started an outbound sync: inbound listener progress
    // must not clobber our phase display. (This used to be inferred from
    // the phase value itself, which dropped the inbound sync's own Done —
    // leaving the spinner running forever after a successful receive.)
    var outboundActive by remember { mutableStateOf(false) }
    // v0.20.7: TextFieldValue state so the keyboard's composing
    // region survives recompositions (backspace space-eating fix).
    var manualIp by remember { mutableStateOf(TextFieldValue("")) }
    val discovered = remember { mutableStateMapOf<String, DiscoveredPeer>() }

    // Identity + network presence live exactly while this screen is open.
    LaunchedEffect(partnerUuid) {
        selfUuid = viewModel.getOrCreateDeviceUuid()
        selfName = defaultDeviceName()
    }
    DisposableEffect(selfUuid) {
        val uuid = selfUuid ?: return@DisposableEffect onDispose { }
        val nsd = PartnerNsd(context, uuid, selfName)
        nsd.startAdvertise()
        nsd.startDiscovery(
            onPeer = { discovered[it.uuid] = it },
            onLost = { discovered.remove(it.uuid) }
        )
        val listener = SyncListener(
            repo = viewModel.repository,
            selfUuid = { uuid },
            selfName = { selfName },
            onPhase = { sp ->
                // Inbound-sync progress surfaces only when we didn't start
                // an outbound sync ourselves (which owns the display).
                if (!outboundActive) phase = sp
            }
        )
        listener.start(scope)
        onDispose {
            listener.stop()
            nsd.stopDiscovery()
            nsd.stopAdvertise()
        }
    }

    fun startSync(host: String) {
        val p = partner ?: return
        val uuid = selfUuid ?: return
        outboundActive = true
        scope.launch(Dispatchers.IO) {
            try {
                val result = runSyncAsConnector(
                    host = host,
                    partner = p,
                    repo = viewModel.repository,
                    selfUuid = uuid,
                    selfName = selfName,
                    onPhase = { phase = it }
                )
                phase = SyncPhase.Done(result.summary())
            } catch (e: Exception) {
                phase = SyncPhase.Error(e.message ?: "Sync failed.")
            } finally {
                outboundActive = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sync with partner") },
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            if (partner == null) {
                Text(
                    "This partner is no longer in your list.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack) { Text("Back") }
                return@Column
            }
            Text(
                partner.displayName,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                lastSyncedLabel(partner.lastSyncAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!partner.syncContacts) {
                Text(
                    "Contacts sync is off for this partner — events only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(24.dp))

            val found = discovered[partnerUuid]
            when (val ph = phase) {
                is SyncPhase.Idle, is SyncPhase.Discovering -> {
                    if (found != null) {
                        Text(
                            "${partner.displayName} is on this network.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { startSync(found.host) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Sync now") }
                    } else {
                        Text(
                            "Looking for ${partner.displayName} on this Wi-Fi " +
                                "network…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(24.dp))
                        partner.lastKnownIp?.let { lastIp ->
                            OutlinedButton(
                                onClick = { startSync(lastIp) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Try last known address ($lastIp)") }
                            Spacer(Modifier.height(12.dp))
                        }
                        OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            label = { Text("Their address, if you know it") },
                            placeholder = { Text("192.168.1.5") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { startSync(manualIp.text) },
                            enabled = manualIp.text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Connect") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Both devices must be on the same Wi-Fi network, " +
                            "and your partner must also be viewing this " +
                            "screen. Nothing syncs in the background — " +
                            "this screen is the sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is SyncPhase.Connecting, is SyncPhase.Syncing -> {
                    val detail = when (ph) {
                        is SyncPhase.Connecting -> "Connecting…"
                        is SyncPhase.Syncing -> ph.detail
                        else -> ""
                    }
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(detail, style = MaterialTheme.typography.bodyLarge)
                }
                is SyncPhase.Done -> {
                    Text(
                        ph.summary,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { phase = SyncPhase.Idle },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sync again") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Back") }
                }
                is SyncPhase.Error -> {
                    Text(
                        ph.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { phase = SyncPhase.Idle },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Try again") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Back") }
                }
            }
        }
    }
}
