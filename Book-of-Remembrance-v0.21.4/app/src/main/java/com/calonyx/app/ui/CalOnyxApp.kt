package com.calonyx.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.data.Partner
import com.calonyx.app.ui.calendar.CalendarScreen
import com.calonyx.app.ui.calendar.CalendarViewModel
import com.calonyx.app.ui.calendar.EventEditorScreen
import com.calonyx.app.ui.components.CalendarTabIcon
import com.calonyx.app.ui.components.ContactsTabIcon
import com.calonyx.app.ui.components.GearIcon
import com.calonyx.app.ui.components.HomeTabIcon
import com.calonyx.app.ui.contacts.ContactDetailScreen
import com.calonyx.app.ui.contacts.ContactEditorScreen
import com.calonyx.app.ui.contacts.ContactsScreen
import com.calonyx.app.ui.contacts.ContactsViewModel
import com.calonyx.app.ui.home.HomeScreen
import com.calonyx.app.ui.home.HomeViewModel
import com.calonyx.app.ui.settings.HelpScreen
import com.calonyx.app.ui.settings.PartnerListScreen
import com.calonyx.app.ui.settings.PartnerSyncScreen
import com.calonyx.app.ui.settings.SettingsScreen
import com.calonyx.app.ui.settings.SettingsViewModel
import com.calonyx.app.ui.settings.ShareScreen
import com.calonyx.app.ui.theme.CalOnyxTheme

private enum class Tab { HOME, CALENDAR, CONTACTS, SETTINGS }

/**
 * Navigation destinations. The app uses a manual back stack (no navigation
 * library in this build's dependency set): pushing opens a screen, popping
 * goes back, and the stack root is a tab. Settings is the rightmost tab.
 */
private sealed interface Dest {
    data class Tab(val tab: com.calonyx.app.ui.Tab) : Dest
    data class EventEditor(val eventId: Long?, val newStart: Long, val newEnd: Long) : Dest
    data class ContactDetail(val contactId: Long) : Dest
    data class ContactEditor(val contactId: Long?) : Dest
    /** Device-to-device share screen (M15). */
    data object Share : Dest
    /** Help / info page (v0.15.1). */
    data object Help : Dest
    /** M16 partner list (multiple partners). */
    data object PartnerList : Dest
    /** M16 sync-with-partner screen (single partner). */
    data class PartnerSync(val partnerUuid: String) : Dest
}

/**
 * CalOnyx root: bottom navigation across Home / Calendar / Contacts /
 * Settings, with the event editor, contact timeline, and contact editor
 * as overlay destinations on the back stack. The system back button pops
 * the stack; from a secondary tab it returns to Home, and only exits the
 * app from the Home tab with an empty stack.
 */
@Composable
fun CalOnyxApp(
    repository: CalOnyxRepository,
    homeViewModel: HomeViewModel,
    calendarViewModel: CalendarViewModel,
    contactsViewModel: ContactsViewModel,
    settingsViewModel: SettingsViewModel
) {
    CalOnyxTheme(repository = repository) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            var backStack by remember { mutableStateOf(listOf<Dest>(Dest.Tab(Tab.HOME))) }
            val top = backStack.last()

            fun navigate(dest: Dest) {
                backStack = backStack + dest
            }

            fun selectTab(tab: Tab) {
                backStack = when (backStack.last()) {
                    is Dest.Tab -> backStack.dropLast(1) + Dest.Tab(tab)
                    else -> backStack + Dest.Tab(tab)
                }
            }

            /**
             * Handles a back press. Returns true if the press was consumed.
             * From a secondary tab goes to Home; from Home with an empty
             * stack returns false so the system exits the app.
             */
            fun goBack(): Boolean {
                val current = backStack.last()
                return when {
                    current is Dest.Tab && current.tab != Tab.HOME -> {
                        // A tab is always at the stack root, so collapse to Home.
                        backStack = listOf(Dest.Tab(Tab.HOME))
                        contactsViewModel.selectContact(null)
                        true
                    }
                    backStack.size > 1 -> {
                        if (current is Dest.ContactDetail) {
                            contactsViewModel.selectContact(null)
                        }
                        backStack = backStack.dropLast(1)
                        true
                    }
                    else -> false
                }
            }

            /** Pops everything down to the nearest tab (used after deletes). */
            fun popToTab() {
                val idx = backStack.indexOfLast { it is Dest.Tab }
                backStack = if (idx >= 0) backStack.take(idx + 1)
                            else listOf(Dest.Tab(Tab.HOME))
                contactsViewModel.selectContact(null)
            }

            val canGoBack = backStack.size > 1 ||
                (top as? Dest.Tab)?.tab != Tab.HOME
            BackHandler(enabled = canGoBack) { goBack() }

            val eventTypes by calendarViewModel.eventTypes.collectAsState()
            val eventTypeColors: Map<Long, Color> = remember(eventTypes) {
                eventTypes.associate { it.id to Color(it.colorArgb) }
            }

            fun openEditor(eventId: Long) {
                navigate(Dest.EventEditor(eventId = eventId, newStart = 0L, newEnd = 0L))
            }
            fun openNewEvent(start: Long, end: Long) {
                navigate(Dest.EventEditor(eventId = null, newStart = start, newEnd = end))
            }
            fun openContactDetail(id: Long) {
                contactsViewModel.selectContact(id)
                navigate(Dest.ContactDetail(id))
            }

            when (val dest = top) {
                is Dest.EventEditor -> {
                    EventEditorScreen(
                        eventId = dest.eventId,
                        initialStartMillis = dest.newStart,
                        initialEndMillis = dest.newEnd,
                        repository = repository,
                        onDismiss = { goBack() },
                        onOpenContact = ::openContactDetail,
                        onDeleteEvent = { event, deleteFuture ->
                            // Buffer the delete for undo, then land on the
                            // Calendar tab where the undo snackbar lives.
                            calendarViewModel.deleteEventWithScope(event, deleteFuture)
                            popToTab()
                            selectTab(Tab.CALENDAR)
                        }
                    )
                }
                is Dest.ContactDetail -> {
                    ContactDetailScreen(
                        viewModel = contactsViewModel,
                        eventTypeColors = eventTypeColors,
                        onEditEvent = ::openEditor,
                        onEditContact = { navigate(Dest.ContactEditor(it)) },
                        onBack = { goBack() },
                        contentPadding = PaddingValues(0.dp)
                    )
                }
                is Dest.ContactEditor -> {
                    ContactEditorScreen(
                        viewModel = contactsViewModel,
                        contactId = dest.contactId,
                        onDismiss = { goBack() },
                        onDeleted = { popToTab() },
                        contentPadding = PaddingValues(0.dp)
                    )
                }
                is Dest.Share -> {
                    ShareScreen(
                        viewModel = settingsViewModel,
                        onBack = { goBack() }
                    )
                }
                is Dest.Help -> {
                    HelpScreen(
                        onBack = { goBack() }
                    )
                }
                is Dest.PartnerList -> {
                    PartnerListScreen(
                        viewModel = settingsViewModel,
                        onBack = { goBack() },
                        onSyncPartner = { uuid -> navigate(Dest.PartnerSync(uuid)) }
                    )
                }
                is Dest.PartnerSync -> {
                    PartnerSyncScreen(
                        viewModel = settingsViewModel,
                        partnerUuid = dest.partnerUuid,
                        onBack = { goBack() }
                    )
                }
                is Dest.Tab -> {
                    Scaffold(
                        bottomBar = {
                            // v0.21.1: icons and labels are the font color in
                            // every state, and the selected-tab pill is the
                            // accent color. (The pill previously fell back to
                            // Material's default secondaryContainer gray,
                            // which no appearance setting controlled.)
                            val navBarColors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primary
                            )
                            NavigationBar {
                                NavigationBarItem(
                                    selected = dest.tab == Tab.HOME,
                                    onClick = { selectTab(Tab.HOME) },
                                    icon = {
                                        HomeTabIcon(tint = MaterialTheme.colorScheme.onSurface)
                                    },
                                    label = { Text("Home") },
                                    colors = navBarColors
                                )
                                NavigationBarItem(
                                    selected = dest.tab == Tab.CALENDAR,
                                    onClick = { selectTab(Tab.CALENDAR) },
                                    icon = {
                                        CalendarTabIcon(tint = MaterialTheme.colorScheme.onSurface)
                                    },
                                    label = { Text("Calendar") },
                                    colors = navBarColors
                                )
                                NavigationBarItem(
                                    selected = dest.tab == Tab.CONTACTS,
                                    onClick = { selectTab(Tab.CONTACTS) },
                                    icon = {
                                        ContactsTabIcon(tint = MaterialTheme.colorScheme.onSurface)
                                    },
                                    label = { Text("Contacts") },
                                    colors = navBarColors
                                )
                                NavigationBarItem(
                                    selected = dest.tab == Tab.SETTINGS,
                                    onClick = { selectTab(Tab.SETTINGS) },
                                    icon = {
                                        GearIcon(tint = MaterialTheme.colorScheme.onSurface)
                                    },
                                    label = { Text("Settings") },
                                    colors = navBarColors
                                )
                            }
                        }
                    ) { inner ->
                        when (dest.tab) {
                            Tab.HOME -> HomeScreen(
                                viewModel = homeViewModel,
                                onEditEvent = ::openEditor,
                                onSelectDate = { date ->
                                    calendarViewModel.selectDate(date)
                                    selectTab(Tab.CALENDAR)
                                },
                                contentPadding = inner
                            )
                            Tab.CALENDAR -> CalendarScreen(
                                viewModel = calendarViewModel,
                                onEditEvent = ::openEditor,
                                onNewEvent = ::openNewEvent,
                                contentPadding = inner
                            )
                            Tab.CONTACTS -> ContactsScreen(
                                viewModel = contactsViewModel,
                                onSelectContact = ::openContactDetail,
                                onAddContact = { navigate(Dest.ContactEditor(null)) },
                                contentPadding = inner
                            )
                            Tab.SETTINGS -> SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = null,
                                contentPadding = inner,
                                onShareClick = { navigate(Dest.Share) },
                                onHelpClick = { navigate(Dest.Help) },
                                onSyncPartnerClick = { partners ->
                                    if (partners.size == 1) {
                                        navigate(Dest.PartnerSync(partners[0].uuid))
                                    } else {
                                        navigate(Dest.PartnerList)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
