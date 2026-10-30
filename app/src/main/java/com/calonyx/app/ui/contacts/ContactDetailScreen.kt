package com.calonyx.app.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calonyx.app.ui.components.EmailIcon
import com.calonyx.app.ui.components.EventListItem
import com.calonyx.app.ui.components.PhoneIcon
import com.calonyx.app.ui.components.SmsIcon
import com.calonyx.app.ui.EventDisplay

/**
 * Contact timeline: general info plus their events, most recent first.
 * Tapping an event opens it in the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    viewModel: ContactsViewModel,
    eventTypeColors: Map<Long, Color>,
    onEditEvent: (Long) -> Unit,
    onEditContact: (Long) -> Unit,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val detail by viewModel.selectedContact.collectAsState()

    Scaffold(
        modifier = modifier.padding(contentPadding),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        detail?.contact?.displayName ?: "Contact",
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                },
                actions = {
                    detail?.contact?.let { contact ->
                        TextButton(onClick = { onEditContact(contact.id) }) { Text("Edit") }
                    }
                }
            )
        }
    ) { inner ->
        val contact = detail?.contact
        if (contact == null) {
            Text(
                "Loading…",
                modifier = Modifier.padding(inner).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Scaffold
        }
        val events = (detail?.events ?: emptyList())
            .sortedByDescending { it.startTime }
        val displays = events.map { event ->
            val color = event.eventTypeId?.let { eventTypeColors[it] } ?: Color(0xFFBB86FC)
            EventDisplay(event, null, color)
        }

        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            // Field order: name → relationship → phone → email → address → notes.
            // Text sizes come from the theme Typography (scaled app-wide by
            // the user's text-size setting).
            // The whole screen (info + events) scrolls as one list so a long
            // note can be fully read and the events stay reachable.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    val context = LocalContext.current
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Details are long-press selectable (each field copyable)
                // without entering edit mode. The dial/SMS/email buttons
                // still receive taps normally inside the container.
                SelectionContainer {
                    Column {
                Text(
                    contact.displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val tagName = viewModel.tagNameFor(contact.relationshipTagId)
                if (tagName != "—") {
                    Text(
                        tagName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            phone,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { dialPhone(context, phone) }) {
                            PhoneIcon(tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { smsPhone(context, phone) }) {
                            SmsIcon(tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                contact.email?.takeIf { it.isNotBlank() }?.let { email ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Text(
                            email,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { emailTo(context, email) }) {
                            EmailIcon(tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                contact.address?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                contact.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                    }
                }
                Text(
                    "${events.size} linked event${if (events.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                    }
                }
            if (displays.isEmpty()) {
                item {
                Text(
                    "No events linked to this contact yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
                }
            } else {
                items(displays, key = { it.event.id }) { display ->
                    EventListItem(display = display, onClick = { onEditEvent(display.event.id) })
                }
            }
            }
        }
    }
}

/** Opens the dialer with [raw] pre-filled. Never places a call — no CALL_PHONE permission needed. */
private fun dialPhone(context: android.content.Context, raw: String) {
    val number = raw.filter { it.isDigit() || it == '+' }
    fireExternalIntent(
        context,
        android.content.Intent(
            android.content.Intent.ACTION_DIAL,
            android.net.Uri.parse("tel:$number")
        ),
        "No dialer app found"
    )
}

/** Opens the SMS app addressed to [raw]. */
private fun smsPhone(context: android.content.Context, raw: String) {
    val number = raw.filter { it.isDigit() || it == '+' }
    fireExternalIntent(
        context,
        android.content.Intent(
            android.content.Intent.ACTION_SENDTO,
            android.net.Uri.parse("smsto:$number")
        ),
        "No messaging app found"
    )
}

/** Opens the default email app addressed to [address]. */
private fun emailTo(context: android.content.Context, address: String) {
    fireExternalIntent(
        context,
        android.content.Intent(
            android.content.Intent.ACTION_SENDTO,
            android.net.Uri.parse("mailto:${address.trim()}")
        ),
        "No email app found"
    )
}

/**
 * Fires [intent], showing a brief toast instead of crashing when no app
 * can handle it (e.g. a device without telephony).
 */
private fun fireExternalIntent(
    context: android.content.Context,
    intent: android.content.Intent,
    missingMessage: String
) {
    try {
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, missingMessage, android.widget.Toast.LENGTH_SHORT).show()
        }
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, missingMessage, android.widget.Toast.LENGTH_SHORT).show()
    }
}
