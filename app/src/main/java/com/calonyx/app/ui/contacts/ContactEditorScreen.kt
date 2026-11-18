package com.calonyx.app.ui.contacts
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.Contact
import kotlinx.coroutines.launch

/**
 * Full contact editor: first/last name, phone, address, notes, and the
 * relationship tag. Used both for creating (contactId == null) and editing.
 * Saving derives displayName from the names; at least one name is required.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ContactEditorScreen(
    viewModel: ContactsViewModel,
    contactId: Long?,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val tags by viewModel.relationshipTags.collectAsState()

    var loaded by remember { mutableStateOf(contactId == null) }
    // v0.20.7: TextFieldValue state (not plain String) so the keyboard's
    // composing region survives recompositions — the String-state form
    // destroyed it on every keystroke, the mechanism behind the
    // backspace space-eating bug.
    var firstName by remember { mutableStateOf(TextFieldValue("")) }
    var lastName by remember { mutableStateOf(TextFieldValue("")) }
    var phone by remember { mutableStateOf(TextFieldValue("")) }
    var email by remember { mutableStateOf(TextFieldValue("")) }
    var address by remember { mutableStateOf(TextFieldValue("")) }
    var notes by remember { mutableStateOf(TextFieldValue("")) }
    var tagId by remember { mutableLongStateOf(-1L) }
    var existing by remember { mutableStateOf<Contact?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    // v0.19.1: same keyboard/inset treatment as the event editor
    // (v0.17.1) — with Notes focused the field must scroll above the
    // keyboard instead of sitting behind it at max scroll.
    val scope = rememberCoroutineScope()
    val notesBiv = remember { BringIntoViewRequester() }

    LaunchedEffect(contactId) {
        if (contactId != null) {
            viewModel.getContact(contactId)?.let { c ->
                existing = c
                firstName = TextFieldValue(c.firstName ?: "")
                lastName = TextFieldValue(c.lastName ?: "")
                phone = TextFieldValue(c.phone ?: "")
                email = TextFieldValue(c.email ?: "")
                address = TextFieldValue(c.address ?: "")
                notes = TextFieldValue(c.notes ?: "")
                tagId = c.relationshipTagId ?: -1L
            }
            loaded = true
        }
    }

    fun doSave() {
        val display = Contact.displayNameFor(firstName.text, lastName.text)
        if (display.isBlank()) {
            nameError = true
            return
        }
        viewModel.saveContactFull(
            id = contactId ?: 0L,
            firstName = firstName.text,
            lastName = lastName.text,
            phone = phone.text,
            email = email.text,
            address = address.text,
            notes = notes.text,
            relationshipTagId = tagId.takeIf { it >= 0 }
        ) { onDismiss() }
    }

    Scaffold(
        modifier = modifier.padding(contentPadding),
        topBar = {
            TopAppBar(
                title = { Text(if (contactId == null) "New contact" else "Edit contact") },
                navigationIcon = {
                    TextButton(onClick = onDismiss) { Text("‹ Back") }
                },
                actions = {
                    if (contactId != null) {
                        TextButton(onClick = { showDeleteConfirm = true }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = ::doSave) { Text("Save") }
                }
            )
        }
    ) { inner ->
        if (!loaded) {
            Text(
                "Loading…",
                modifier = Modifier.padding(inner).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                // v0.19.1: exactly one bottom inset, mirroring the event
                // editor (v0.17.1). Top/side insets come from the Scaffold;
                // the bottom is the union of nav bar + IME (safeContent),
                // so the keyboard is compensated once — no dead zone, and
                // bring-into-view lands the field above the keyboard.
                .padding(
                    top = inner.calculateTopPadding(),
                    start = inner.calculateStartPadding(LocalLayoutDirection.current),
                    end = inner.calculateEndPadding(LocalLayoutDirection.current)
                )
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = firstName,
                onValueChange = { firstName = it; nameError = false },
                label = { Text("First name") },
                singleLine = true,
                isError = nameError,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = lastName,
                onValueChange = { lastName = it; nameError = false },
                label = { Text("Last name") },
                singleLine = true,
                isError = nameError,
                supportingText = if (nameError) {
                    { Text("Enter at least a first or last name") }
                } else null,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = email,
                onValueChange = { email = it },
                label = { Text("Email (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = address,
                onValueChange = { address = it },
                label = { Text("Address (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (optional)") },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(notesBiv)
                    .onFocusChanged {
                        if (it.isFocused) scope.launch { notesBiv.bringIntoView() }
                    }
            )
            Text("Relationship", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FilterChip(
                    selected = tagId < 0,
                    onClick = { tagId = -1L },
                    label = { Text("None") }
                )
                tags.forEach { tag ->
                    FilterChip(
                        selected = tagId == tag.id,
                        onClick = { tagId = if (tagId == tag.id) -1L else tag.id },
                        label = { Text(tag.name) }
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val toDelete = existing
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete contact?") },
            text = {
                Text(
                    "“${toDelete?.displayName ?: "This contact"}” and its event links " +
                        "will be removed. You can undo this for a few seconds."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (toDelete != null) viewModel.deleteContactWithUndo(toDelete) { onDeleted() }
                    else onDeleted()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
