package com.calonyx.app.ui.contacts
import androidx.compose.foundation.text.KeyboardOptions

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.calonyx.app.data.ContactSort
import com.calonyx.app.ui.components.PlusIcon
import kotlinx.coroutines.launch

/**
 * Contacts tab: searchable, sortable list. Tapping a contact opens their
 * timeline; the FAB opens the full contact editor for a new contact.
 * Long-press starts merge selection: pick 2+ contacts, then Merge folds
 * them into one (links repointed, blank fields filled in).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onSelectContact: (Long) -> Unit,
    onAddContact: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val contacts by viewModel.contacts.collectAsState()
    val tags by viewModel.relationshipTags.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    // v0.20.7: the field holds TextFieldValue (not the ViewModel's
    // String) so the keyboard's composing region survives
    // recompositions — the String-state form destroyed it on every
    // keystroke, the mechanism behind the backspace space-eating bug.
    // The ViewModel String stays the source of truth for filtering;
    // this mirrors it, and re-syncs if it ever changes elsewhere.
    var queryField by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) {
        if (queryField.text != query) queryField = TextFieldValue(query)
    }
    val sort by viewModel.sortOrder.collectAsState()
    val mergeIds by viewModel.mergeSelection.collectAsState()
    val mergeMode = mergeIds.isNotEmpty()
    val pendingUndo by viewModel.pendingUndo.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showSetRelationshipDialog by remember { mutableStateOf(false) }
    // v0.19.0: snap back to the top of a newly sorted list — but only when
    // the list was already at the top when the sort changed.
    val listState = rememberLazyListState()
    var snapToTopOnSort by remember { mutableStateOf(false) }
    LaunchedEffect(sort) {
        if (snapToTopOnSort) {
            snapToTopOnSort = false
            listState.scrollToItem(0)
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Back exits merge mode first.
    BackHandler(enabled = mergeMode) { viewModel.clearMergeSelection() }

    // Undo snackbar for contact deletes. Buffer drops on timeout,
    // dismissal, or leaving this tab.
    LaunchedEffect(pendingUndo) {
        val undone = pendingUndo
        if (undone != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Contact deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
            else viewModel.clearUndo()
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.clearUndo() } }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                val result = viewModel.syncDeviceContacts(context)
                snackbarHostState.showSnackbar(
                    when {
                        result.imported > 0 -> "Imported ${result.imported} contact" +
                            (if (result.imported == 1) "" else "s") +
                            if (result.skipped > 0) " (${result.skipped} already here)" else ""
                        else -> "No new contacts to import"
                    }
                )
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Permission denied — using local contacts only"
                )
            }
        }
    }

    fun onSyncClick() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch {
                val result = viewModel.syncDeviceContacts(context)
                snackbarHostState.showSnackbar(
                    if (result.imported > 0) "Imported ${result.imported} contact" +
                        (if (result.imported == 1) "" else "s")
                    else "No new contacts to import"
                )
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    Scaffold(
        modifier = modifier.padding(contentPadding),
        // Edge-to-edge (M14) lets system insets flow into Compose; the
        // status-bar inset already arrives once via the outer Scaffold's
        // contentPadding above, so the inner default would apply it twice
        // and leave a dead gap at the top. Zeroed to restore the pre-M14
        // layout exactly.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddContact,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                PlusIcon(tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            if (mergeMode) {
                // Contextual merge bar replaces the title row while selecting.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${mergeIds.size} selected",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showMergeDialog = true },
                        enabled = mergeIds.size >= 2
                    ) { Text("Merge") }
                    TextButton(
                        onClick = { showSetRelationshipDialog = true },
                        enabled = mergeIds.isNotEmpty()
                    ) { Text("Set relationship") }
                    TextButton(onClick = { viewModel.clearMergeSelection() }) { Text("✕") }
                }
            } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Contacts",
                    style = MaterialTheme.typography.headlineMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::onSyncClick) { Text("Sync") }
                    // v0.19.3: the menu lives in a Box with its button, so
                    // the popup anchors to the button (right side). As a
                    // bare child of the header Row it anchored to the whole
                    // Row and opened on the LEFT, away from its button.
                    Box {
                        TextButton(onClick = { showSortMenu = true }) {
                            Text("Sort: ${sort.label} ▾")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            ContactSort.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        snapToTopOnSort =
                                            listState.firstVisibleItemIndex == 0 &&
                                                listState.firstVisibleItemScrollOffset == 0
                                        viewModel.setSortOrder(option)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
            }
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = queryField,
                onValueChange = { queryField = it; viewModel.searchQuery.value = it.text },
                placeholder = { Text("Search contacts") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (contacts.isEmpty()) {
                Text(
                    text = if (query.isBlank()) "No contacts yet — tap + to add one."
                           else "No matches.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(contacts, key = { it.id }) { contact ->
                        // Subtitle prefers the relationship tag (distinguishes
                        // "Dave – Family" from "Dave – Peer" at a glance),
                        // falling back to phone, then a subtle dash.
                        val tagName = tags.firstOrNull { it.id == contact.relationshipTagId }?.name
                        val subtitle = tagName
                            ?: contact.phone?.takeIf { it.isNotBlank() }
                            ?: "—"
                        val selected = contact.id in mergeIds
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .combinedClickable(
                                    onClick = {
                                        if (mergeMode) viewModel.toggleMergeSelection(contact.id)
                                        else onSelectContact(contact.id)
                                    },
                                    onLongClick = { viewModel.toggleMergeSelection(contact.id) }
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    if (showMergeDialog) {
        MergeContactsDialog(
            selected = contacts.filter { it.id in mergeIds },
            tagNameFor = viewModel::tagNameFor,
            onMerge = { survivorId ->
                showMergeDialog = false
                viewModel.mergeContacts(survivorId) { name, count ->
                    scope.launch {
                        snackbarHostState.showSnackbar("Merged $count contact" +
                            (if (count == 1) "" else "s") + " into $name")
                    }
                }
            },
            onDismiss = { showMergeDialog = false }
        )
    }

    if (showSetRelationshipDialog) {
        val tags by viewModel.relationshipTags.collectAsState()
        SetRelationshipDialog(
            tags = tags,
            count = mergeIds.size,
            onPick = { tagId ->
                showSetRelationshipDialog = false
                viewModel.setRelationshipForSelection(tagId)
            },
            onDismiss = { showSetRelationshipDialog = false }
        )
    }
}

/**
 * Merge dialog: pick which contact survives (radio list, first selected
 * by default) and confirm. Shows a live per-field preview of exactly
 * what the merged contact will look like, mirroring
 * [CalOnyxRepository.mergeContacts]: the survivor's non-blank values win,
 * blanks are filled from the others, event links move over, and the
 * absorbed contacts are deleted.
 */
@Composable
private fun MergeContactsDialog(
    selected: List<com.calonyx.app.data.Contact>,
    tagNameFor: (Long?) -> String,
    onMerge: (survivorId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var survivorId by remember(selected) { mutableStateOf(selected.firstOrNull()?.id) }
    val survivor = selected.firstOrNull { it.id == survivorId }
    val survivorName = survivor?.displayName ?: ""
    val absorbed = selected.filter { it.id != survivorId }

    /** Winning value for a text field and the contact it comes from. */
    fun winningText(
        pick: (com.calonyx.app.data.Contact) -> String?
    ): Pair<String, com.calonyx.app.data.Contact?> {
        if (survivor != null && !pick(survivor).isNullOrBlank()) {
            return pick(survivor)!! to survivor
        }
        val donor = absorbed.firstOrNull { !pick(it).isNullOrBlank() }
        return (donor?.let(pick) ?: "—") to donor
    }
    val relTagId = survivor?.relationshipTagId
        ?: absorbed.firstOrNull { it.relationshipTagId != null }?.relationshipTagId
    val relSource = if (survivor?.relationshipTagId != null) survivor
    else absorbed.firstOrNull { it.relationshipTagId != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge contacts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "The contact you keep survives. Its blank fields are filled " +
                        "in from the others, their events move over to it, and " +
                        "the other contacts are then deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                selected.forEach { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { survivorId = contact.id }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = survivorId == contact.id,
                            onClick = { survivorId = contact.id }
                        )
                        Text(
                            contact.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                if (absorbed.isNotEmpty()) {
                    Text(
                        absorbed.joinToString(", ") { it.displayName } +
                            " → $survivorName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Text(
                        "Resulting contact:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    MergeFieldRow("Name", survivorName, null)
                    val (phone, phoneSrc) = winningText { it.phone }
                    MergeFieldRow("Phone", phone, phoneSrc?.takeIf { it.id != survivorId }?.displayName)
                    val (email, emailSrc) = winningText { it.email }
                    MergeFieldRow("Email", email, emailSrc?.takeIf { it.id != survivorId }?.displayName)
                    val (address, addressSrc) = winningText { it.address }
                    MergeFieldRow("Address", address, addressSrc?.takeIf { it.id != survivorId }?.displayName)
                    val (notes, notesSrc) = winningText { it.notes }
                    MergeFieldRow("Notes", notes, notesSrc?.takeIf { it.id != survivorId }?.displayName)
                    MergeFieldRow(
                        "Relationship",
                        if (relTagId != null) tagNameFor(relTagId) else "—",
                        relSource?.takeIf { it.id != survivorId }?.displayName
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { survivorId?.let(onMerge) },
                enabled = survivorId != null && absorbed.isNotEmpty()
            ) { Text("Merge", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * One row of the merge preview: the value the merged contact will keep,
 * plus which contact it comes from when that isn't the survivor.
 */
@Composable
private fun MergeFieldRow(label: String, value: String, sourceName: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Column(modifier = Modifier.weight(0.65f)) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (sourceName != null) {
                Text(
                    text = "from $sourceName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Bulk "set relationship" dialog: pick one tag (in the user's manual
 * order, same list the editor uses) to apply to every selected contact,
 * or "None" to clear the tag. One tap applies immediately.
 */
@Composable
private fun SetRelationshipDialog(
    tags: List<com.calonyx.app.data.RelationshipTag>,
    count: Int,
    onPick: (tagId: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set relationship") },
        text = {
            Column {
                Text(
                    "Apply to $count selected contact${if (count == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    item {
                        Text(
                            "None",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(null) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                    items(tags, key = { it.id }) { tag ->
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(tag.id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
