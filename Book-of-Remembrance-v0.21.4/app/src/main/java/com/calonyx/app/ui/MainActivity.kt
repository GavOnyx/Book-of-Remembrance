package com.calonyx.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.calonyx.app.data.CalOnyxDatabase
import com.calonyx.app.data.CalOnyxRepository
import com.calonyx.app.ui.calendar.CalendarViewModel
import com.calonyx.app.ui.contacts.ContactsViewModel
import com.calonyx.app.ui.home.HomeViewModel
import com.calonyx.app.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

/**
 * Milestone 2 entry point. Builds the repository, seeds first-run defaults,
 * and hosts the bottom-navigation app (Home / Calendar / Contacts).
 */
class MainActivity : ComponentActivity() {

    private val repository by lazy {
        CalOnyxRepository(CalOnyxDatabase.getInstance(this))
    }

    @Suppress("UNCHECKED_CAST")
    private val viewModelFactory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            when {
                modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                    HomeViewModel(repository)
                modelClass.isAssignableFrom(CalendarViewModel::class.java) ->
                    CalendarViewModel(repository)
                modelClass.isAssignableFrom(ContactsViewModel::class.java) ->
                    ContactsViewModel(repository)
                modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                    SettingsViewModel(repository)
                else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
            } as T
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Milestone 14: draw edge-to-edge behind the system bars (targetSdk
        // 34). Material3 Scaffold/topBar/bottomBar already consume the
        // system-bar insets, so content is never obscured.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repository.seedDefaultsIfNeeded()
            // v0.18.0 one-time data fix ("Contact"→"Visit", add "Errand").
            repository.applyTypeRenamesIfNeeded()
        }

        val provider = ViewModelProvider(this, viewModelFactory)
        val homeViewModel = provider[HomeViewModel::class.java]
        val calendarViewModel = provider[CalendarViewModel::class.java]
        val contactsViewModel = provider[ContactsViewModel::class.java]
        val settingsViewModel = provider[SettingsViewModel::class.java]

        setContent {
            CalOnyxApp(
                repository = repository,
                homeViewModel = homeViewModel,
                calendarViewModel = calendarViewModel,
                contactsViewModel = contactsViewModel,
                settingsViewModel = settingsViewModel
            )
        }
    }
}
