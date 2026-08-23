package com.personal.smsforwarder.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.personal.smsforwarder.AppContainer
import com.personal.smsforwarder.SmsForwarderApp
import com.personal.smsforwarder.core.AppLock

private enum class Tab(val label: String, val icon: ImageVector) {
    Rules("Rules", Icons.AutoMirrored.Filled.List),
    Forwarders("Forwarders", Icons.AutoMirrored.Filled.Send),
    History("History", Icons.Default.Info),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * A FragmentActivity rather than a plain ComponentActivity purely because
 * androidx.biometric's prompt is a fragment and will not attach to anything less.
 */
class MainActivity : FragmentActivity() {

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
    val security by container.store.security.collectAsState()
    val onboarded by container.store.onboarded.collectAsState()

    AppTheme(appearance) {
        var splashDone by remember { mutableStateOf(!coldStart) }
        var tab by remember { mutableStateOf(Tab.Rules) }
        var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
        var showGuide by remember { mutableStateOf(false) }

        // The system, not our preference, is the source of truth for which alias is live:
        // an import or a restore can change the setting without anyone visiting Appearance.
        LaunchedEffect(appearance.icon) { container.icons.apply(appearance.icon) }

        val appLock = rememberAppLock(security.appLockEnabled, security.graceSeconds) {
            container.store.updateSecurity(security.copy(appLockEnabled = false))
        }

        // Screenshot blocking follows the screen rather than the lock. History is the only
        // tab holding message bodies — and therefore the codes — so blanking the whole app
        // just to protect it made rules and forwarders uncapturable for no benefit.
        SecureWindow(security.hideHistoryFromScreenshots && tab == Tab.History)

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
                        SettingsPage.Security ->
                            SecurityScreen(container.store, security, modifier)
                        SettingsPage.Simulate -> SimulatorScreen(container.processor, modifier)
                        SettingsPage.Backup -> BackupScreen(container.store, modifier)
                        SettingsPage.Permissions -> PermissionsScreen(modifier)
                        SettingsPage.About -> AboutScreen(modifier)
                        // Handled by onOpen below; it is an overlay, not a sub-page.
                        SettingsPage.Guide -> Unit
                    }

                    else -> when (tab) {
                        Tab.Rules -> RulesScreen(
                            container.store,
                            modifier,
                            onOpenForwarders = { tab = Tab.Forwarders },
                        )
                        Tab.Forwarders ->
                            ForwardersScreen(container.store, container.forwarders, modifier)
                        Tab.History -> HistoryScreen(
                            container.store,
                            modifier,
                            onRetry = { entry, attempt -> container.retry(entry, attempt) },
                        )
                        Tab.Settings -> SettingsScreen(
                            onOpen = { page ->
                                if (page == SettingsPage.Guide) showGuide = true
                                else settingsPage = page
                            },
                            modifier = modifier,
                        )
                    }
                }
            }

            // The guide comes before the splash finishes fading so a fresh install lands
            // on step one rather than on an empty Rules screen.
            if (showGuide || !onboarded) {
                OnboardingScreen(container.store, onDone = { showGuide = false })
            }

            AnimatedVisibility(
                visible = !splashDone,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                SplashOverlay(onFinished = { splashDone = true })
            }

            // Drawn last so it covers everything, the guide included.
            if (appLock.isLocked) {
                LockScreen(onUnlocked = appLock.unlock, onUnavailable = appLock.disarm)
            }
        }
    }
}

/** State for the app lock: whether we are locked, and the two ways out of it. */
private class AppLockState(
    val isLocked: Boolean,
    val unlock: () -> Unit,
    val disarm: (String) -> Unit,
)

/**
 * Tracks time spent in the background and decides when to re-lock.
 *
 * Uses [SystemClock.elapsedRealtime] rather than wall-clock time so changing the device
 * clock cannot extend the grace period indefinitely.
 */
@Composable
private fun rememberAppLock(
    enabled: Boolean,
    graceSeconds: Int,
    onUnavailable: () -> Unit,
): AppLockState {
    var locked by remember { mutableStateOf(enabled) }
    var backgroundedAt by remember { mutableStateOf<Long?>(null) }

    // Turning the setting off unlocks immediately; turning it on does not lock the screen
    // the user is currently looking at, which would be startling and pointless.
    LaunchedEffect(enabled) { if (!enabled) locked = false }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, enabled, graceSeconds) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> backgroundedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START ->
                    if (AppLock.shouldLock(
                            enabled = enabled,
                            backgroundedAtMillis = backgroundedAt,
                            nowMillis = SystemClock.elapsedRealtime(),
                            graceMillis = graceSeconds * 1000L,
                        )
                    ) {
                        locked = true
                    }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return AppLockState(
        isLocked = locked && enabled,
        unlock = { locked = false },
        disarm = {
            locked = false
            onUnavailable()
        },
    )
}

/**
 * Applies FLAG_SECURE to the activity window while [secure] is true.
 *
 * The flag is per-window, not per-composable, so it has to be cleared again on the way
 * out — leaving it set would silently make the whole app uncapturable the moment someone
 * visited History once.
 */
@Composable
private fun SecureWindow(secure: Boolean) {
    val context = LocalContext.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window, secure) {
        if (secure) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
