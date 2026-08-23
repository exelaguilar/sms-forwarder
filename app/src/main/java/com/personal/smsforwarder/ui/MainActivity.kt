package com.personal.smsforwarder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.personal.smsforwarder.AppContainer
import com.personal.smsforwarder.SmsForwarderApp

private enum class Tab(val label: String, val icon: ImageVector) {
    Rules("Rules", Icons.AutoMirrored.Filled.List),
    Forwarders("Forwarders", Icons.AutoMirrored.Filled.Send),
    History("History", Icons.Default.Info),
    Settings("Settings", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Only a genuinely fresh start animates; rotation and restores go straight in.
        val coldStart = savedInstanceState == null
        super.onCreate(savedInstanceState)
        val container = (application as SmsForwarderApp).container
        setContent { AppRoot(container, coldStart) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(container: AppContainer, coldStart: Boolean) {
    val appearance by container.store.appearance.collectAsState()

    AppTheme(appearance) {
        var splashDone by remember { mutableStateOf(!coldStart) }
        var tab by remember { mutableStateOf(Tab.Rules) }
        var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }

        // Back closes a settings sub-page before it leaves the app.
        BackHandler(enabled = settingsPage != null) { settingsPage = null }

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    val page = settingsPage
                    if (page != null) {
                        TopAppBar(
                            title = { Text(page.title) },
                            navigationIcon = {
                                IconButton(onClick = { settingsPage = null }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            },
                        )
                    }
                },
                bottomBar = {
                    NavigationBar {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = {
                                    tab = item
                                    settingsPage = null
                                },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                val modifier = Modifier.padding(padding)
                val page = settingsPage
                when {
                    page != null -> when (page) {
                        SettingsPage.Appearance ->
                            AppearanceScreen(container.store, appearance, modifier)
                        SettingsPage.Simulate -> SimulatorScreen(container.processor, modifier)
                        SettingsPage.Backup -> BackupScreen(container.store, modifier)
                        SettingsPage.Permissions -> PermissionsScreen(modifier)
                        SettingsPage.About -> AboutScreen(modifier)
                    }

                    else -> when (tab) {
                        Tab.Rules -> RulesScreen(container.store, modifier)
                        Tab.Forwarders ->
                            ForwardersScreen(container.store, container.forwarders, modifier)
                        Tab.History -> HistoryScreen(
                            container.store,
                            modifier,
                            onRetry = { entry, attempt -> container.retry(entry, attempt) },
                        )
                        Tab.Settings -> SettingsScreen({ settingsPage = it }, modifier)
                    }
                }
            }

            AnimatedVisibility(
                visible = !splashDone,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SplashOverlay(onFinished = { splashDone = true })
            }
        }
    }
}
