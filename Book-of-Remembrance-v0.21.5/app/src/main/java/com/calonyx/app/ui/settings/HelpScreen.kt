package com.calonyx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calonyx.app.ui.components.BackIcon

/**
 * Help / info page. Master's own copy, reproduced verbatim from
 * ~/workspace/your_files/Help-page-copy.md (v0.21.1) — do not rewrite the
 * wording. The opening quote stays center-spaced, as in the app.
 *
 * This is a top-level destination (not under the tab Scaffold), so its own
 * Scaffold applies the system insets exactly once. Body text uses
 * MaterialTheme.typography, so the user's theme and text-size setting
 * apply automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Help") },
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
            Spacer(Modifier.height(8.dp))
            Text(
                text = "\u201CFor a Book of Remembrance we have written among us, " +
                    "according to the pattern given by the finger of God\u201D",
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Text(
                text = "\u2014 Moses 6:46",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
            )
            Text(
                text = "Welcome to the Book of Remembrance app (aka Remembrance). " +
                    "This was designed with the Area Book / Preach My Gospel app in mind " +
                    "as developed by the LDS church (contacts are integrated with events, " +
                    "and events can be marked as 'happened', etc). But this is not just a " +
                    "calendar app \u2014 it is a way to record and plan your life.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            HelpSection(
                title = "The Home View",
                body = "\u2022 A month overview shows how much of your day is scheduled/planned for.\n" +
                    "\u2022 A donut chart shows how (or with whom) you spend your time.\n" +
                    "\u2022 Any events with an unmarked status from the recent past are shown in a list."
            )
            HelpSection(
                title = "The Calendar View",
                body = "\u2022 Tap on a blank space to add an event, or press the plus button.\n" +
                    "\u2022 Tap on an event to view and/or edit the details.\n" +
                    "\u2022 Events have titles, types, start/end times, statuses, locations, descriptions, and associated contacts. " +
                    "This allows for keeping a very detailed record of your life \u2014 including who you spend your time with.\n" +
                    "\u2022 Press and hold an event to multi-select and/or bulk edit selected events.\n" +
                    "\u2022 Press the Journal icon at the top left to access the Journal View"
            )
            HelpSection(
                title = "The Journal View",
                body = "\u2022 In the Journal view, a greater emphasis is placed on the details of events. " +
                    "Everything is saved as you type and update events. " +
                    "Use the Calendar view to plan your day and the Journal view to flush out the details.\n" +
                    "\u2022 The large colored dot next to each event title is a drop down to select the Status " +
                    "(unmarked, happened, or didn't happen). Tap an event title to edit it. " +
                    "The drop down to the right of the title is to select an event type. " +
                    "The start and end times are also selectable tor editing. " +
                    "And tap on the 'add contacts' text or the 'with [contact name]' to update the associated contacts.\n" +
                    "\u2022 Press the calendar button in the top left to access the calendar view."
            )
            HelpSection(
                title = "The Contacts View",
                body = "\u2022 Import your phone's contacts via the 'sync' button on the top bar.\n" +
                    "\u2022 Tap any contact to view its details.\n" +
                    "\u2022 Press and hold a contact to multi-select to merge or bulk edit.\n" +
                    "\u2022 Set a contact's relationship while editing for clear sorting and management.\n" +
                    "\u2022 A list of all the events associated with a contact is shown below the contact's details."
            )
            HelpSection(
                title = "Settings",
                body = "\u2022 Change the app appearance.\n" +
                    "\u2022 Manage event and relationship types.\n" +
                    "\u2022 Change calendar start and end times.\n" +
                    "\u2022 Import/export data."
            )
            HelpSection(
                title = "Day fill Window",
                body = "This setting affects what time-frame the month overview counts as the day. " +
                    "For example, you may not want to worry about what you fill your time with before 4 AM, " +
                    "or after 11 PM because it's almost always sleep. Or maybe most of your life is already " +
                    "planned for and all you want to worry about is how you've used your time in the afternoon."
            )
            HelpSection(
                title = "Export events",
                body = "This exports the events in a machine-readable format (an ics file). " +
                    "It will also export all contacts associated with any of the events by default."
            )
            HelpSection(
                title = "Export journal",
                body = "This exports the events in a human-readable format meant to imitate a journal. " +
                    "Only events with a description/report are included. " +
                    "This is to encourage accurate and detailed record keeping."
            )
            HelpSection(
                title = "Appearance",
                body = "Select from a default theme \u2014 the Light and Dark themes are based on the default PMG themes. " +
                    "Make a custom theme by editing an existing theme and then pressing the save button " +
                    "which appears near the theme drop down selector. " +
                    "Whatever the theme colors are currently set to will become the new Custom theme " +
                    "after pressing the save button."
            )
            HelpSection(
                title = "Importing Events/Contacts",
                body = "Be sure to import contacts before importing events. " +
                    "Events with contacts that aren't in your list will lose those links unless the auto-create toggle is on."
            )
            HelpSection(
                title = "Device Sharing",
                body = "share events and contacts with another Remembrance user either as a single transfer, " +
                    "or remember them as a sync partner \u2014 useful for spouses and companions alike."
            )
            HelpSection(
                title = "Privacy",
                body = "Everything stays on your device. There is no account, no cloud, and no tracking. " +
                    "Network permissions only allow the app to send/receive data from other Book of Remembrance " +
                    "users, and only if you explicitly tell it to."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(bottom = 16.dp)
    )
}
