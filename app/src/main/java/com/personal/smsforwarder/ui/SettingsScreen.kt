package com.personal.smsforwarder.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.personal.smsforwarder.BuildConfig
import com.personal.smsforwarder.data.SettingsStore
import com.personal.smsforwarder.model.AppIcon
import com.personal.smsforwarder.model.Appearance
import com.personal.smsforwarder.model.Security

const val PROJECT_URL = "https://github.com/exelaguilar/sms-forwarder"

enum class SettingsPage(val title: String, val icon: ImageVector, val summary: String) {
    Appearance("Appearance", Icons.Default.Palette, "Accent colour, app icon, Material You"),
    Security("Security", Icons.Default.Fingerprint, "Lock the app with biometrics"),
    Simulate(
        "Simulate an incoming SMS",
        Icons.Default.PlayArrow,
        "Runs every rule and really fires your forwarders",
    ),
    Backup("Backup & restore", Icons.Default.Save, "Export or import your configuration"),
    Permissions("Permissions", Icons.Default.Lock, "What the app needs, and why"),
    Guide("Setup guide", Icons.Default.Explore, "Run the first-run walkthrough again"),
    About("About", Icons.Default.Info, "Version, source code, licence"),
}

@Composable
fun SettingsScreen(onOpen: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings")
        SettingsPage.entries.forEach { page ->
            ElevatedCard(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clickable { onOpen(page) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(page.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            page.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---- Appearance -----------------------------------------------------------

@Composable
fun AppearanceScreen(store: SettingsStore, appearance: Appearance, modifier: Modifier = Modifier) {
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val accent = appearance.accentRgb
    val red = (accent shr 16) and 0xFF
    val green = (accent shr 8) and 0xFF
    val blue = accent and 0xFF

    fun setChannel(r: Int = red, g: Int = green, b: Int = blue) {
        store.updateAppearance(appearance.copy(accentRgb = (r shl 16) or (g shl 8) or b))
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Material You", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (supportsDynamic) "Follow the colours of your wallpaper"
                        else "Needs Android 12 or newer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = appearance.useDynamicColor && supportsDynamic,
                    enabled = supportsDynamic,
                    onCheckedChange = { store.updateAppearance(appearance.copy(useDynamicColor = it)) },
                )
            }
        }

        val customEnabled = !(appearance.useDynamicColor && supportsDynamic)

        Text(
            "Accent colour",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (!customEnabled) {
            Text(
                "Turn off Material You to choose your own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 12.dp),
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(accent.toAccentColor()),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Aa",
                    color = contrastOn(accent.toAccentColor()),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.padding(top = 8.dp)) {
                Text(
                    "#%06X".format(accent),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    "R $red   G $green   B $blue",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Appearance.PRESETS.forEach { preset ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(preset.toAccentColor())
                        .clickable(enabled = customEnabled) {
                            store.updateAppearance(appearance.copy(accentRgb = preset))
                        }
                )
            }
        }

        ChannelSlider("Red", red, Color.Red, customEnabled) { setChannel(r = it) }
        ChannelSlider("Green", green, Color(0xFF2E7D32), customEnabled) { setChannel(g = it) }
        ChannelSlider("Blue", blue, Color(0xFF1565C0), customEnabled) { setChannel(b = it) }

        OutlinedButton(
            enabled = customEnabled,
            onClick = { store.updateAppearance(appearance.copy(accentRgb = Appearance.DEFAULT_ACCENT)) },
            modifier = Modifier.padding(vertical = 12.dp),
        ) { Text("Reset to default") }

        Text(
            "Changes apply immediately across the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AppIconPicker(appearance.icon) { store.updateAppearance(appearance.copy(icon = it)) }
    }
}

@Composable
private fun ChannelSlider(
    label: String,
    value: Int,
    tint: Color,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Column {
        Text("$label  $value", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            enabled = enabled,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = tint,
                activeTrackColor = tint,
            ),
        )
    }
}

// ---- About ----------------------------------------------------------------

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppLogo(size = 96.dp, modifier = Modifier.padding(top = 32.dp, bottom = 16.dp))

        Text("SMS Forwarder", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
            Column(Modifier.padding(16.dp)) {
                AboutRow("Developed by", "exelaguilar")
                AboutRow("Licence", "MIT")
                AboutRow("Forwards", "SMS relay, webhook, email")
                Text(
                    "Your messages go straight from this device to the destinations you " +
                        "configure. No analytics, no telemetry, no cloud service in between.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        Button(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, PROJECT_URL.toUri()))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("View source on GitHub") }

        Text(
            PROJECT_URL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- App icon -------------------------------------------------------------

@Composable
private fun AppIconPicker(selected: AppIcon, onSelect: (AppIcon) -> Unit) {
    Text(
        "App icon",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp),
    )
    // Weighted columns rather than intrinsic ones: a longer label under one variant
    // otherwise pushes the last swatch off the right edge.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        AppIcon.entries.forEach { icon ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f).clickable { onSelect(icon) },
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (icon == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppLogo(size = 66.dp, icon = icon)
                }
                Text(
                    icon.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    if (icon == selected) "In use" else " ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    Text(
        "The home screen can take a moment to redraw. Shortcuts pinned to the old icon " +
            "will not follow the change; Android offers no way to move them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 24.dp),
    )
}

// ---- Security -------------------------------------------------------------

/**
 * App lock.
 *
 * The switch is only offered when the device can actually authenticate. Arming a lock on
 * a phone with no screen lock and no enrolled biometric would leave clearing app data as
 * the only way back in, which takes the rules and credentials with it.
 */
@Composable
fun SecurityScreen(store: SettingsStore, security: Security, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val availability = remember { lockAvailability(context) }
    val canArm = availability == LockAvailability.Available
    val armed = security.appLockEnabled && canArm

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Require unlock", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (availability) {
                            LockAvailability.Available ->
                                "Fingerprint, face, or your device PIN before the app opens."
                            LockAvailability.NoneEnrolled ->
                                "Set a screen lock or enrol a fingerprint in system settings first."
                            LockAvailability.Unsupported ->
                                "This device has no screen lock or biometric to authenticate with."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = armed,
                    enabled = canArm,
                    onCheckedChange = { store.updateSecurity(security.copy(appLockEnabled = it)) },
                )
            }
        }

        Text(
            "Ask again",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "How long the app may sit in the background before it locks. The permission " +
                "dialog, the contact picker and the file picker all count as leaving the " +
                "app, so the shortest setting will prompt often.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Security.GRACE_CHOICES.forEach { seconds ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = armed) {
                        store.updateSecurity(security.copy(graceSeconds = seconds))
                    },
            ) {
                RadioButton(
                    selected = security.graceSeconds == seconds,
                    enabled = armed,
                    onClick = { store.updateSecurity(security.copy(graceSeconds = seconds)) },
                )
                Text(Security.graceLabel(seconds))
            }
        }

        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "What the lock does, and what it does not",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Forwarding keeps running while the app is locked. Messages are " +
                        "received by a broadcast receiver and sent by a background " +
                        "worker, neither of which needs the screen, so a locked phone " +
                        "still forwards your codes.\n\n" +
                        "While the lock is on, the app is also kept out of the " +
                        "recent-apps preview, so message bodies cannot be read from " +
                        "there.\n\n" +
                        "This guards the app's own screens. It is not encryption: your " +
                        "settings and history are stored encrypted either way.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
