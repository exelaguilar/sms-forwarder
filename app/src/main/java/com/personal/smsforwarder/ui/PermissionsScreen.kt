package com.personal.smsforwarder.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private data class PermissionInfo(val permission: String, val label: String, val why: String)

private val PERMISSIONS = listOf(
    PermissionInfo(
        Manifest.permission.RECEIVE_SMS,
        "Receive SMS",
        "Delivers the SMS_RECEIVED broadcast this app listens for. Without it nothing is forwarded.",
    ),
    PermissionInfo(
        Manifest.permission.READ_SMS,
        "Read SMS",
        "Required alongside RECEIVE_SMS to read the message body out of the broadcast.",
    ),
    PermissionInfo(
        Manifest.permission.SEND_SMS,
        "Send SMS",
        "Only used by the SMS relay forwarder, which sends a plain SMS to your other phone. " +
            "Carrier message charges may apply per forwarded message.",
    ),
)

@Composable
fun PermissionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var contactsRefresh by remember { mutableIntStateOf(0) }
    val contactsGranted = remember(contactsRefresh) {
        isGranted(context, Manifest.permission.READ_CONTACTS)
    }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { contactsRefresh++ }

    var notifyRefresh by remember { mutableIntStateOf(0) }
    val notificationsNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val notificationsGranted = remember(notifyRefresh) {
        !notificationsNeeded || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifyRefresh++ }
    // Bumped after each request so the grant states are re-read.
    var refresh by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        refresh++
        val denied = granted.filterValues { !it }.keys
        lastResult = if (denied.isEmpty()) {
            "All requested permissions granted."
        } else {
            "Denied: ${denied.joinToString(", ") { it.substringAfterLast('.') }}. " +
                "If the dialog no longer appears, grant them in Settings → Apps → SMS Forwarder → Permissions."
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        ScreenHeader("Permissions", "Only what the app actually uses. INTERNET is install-time.")

        PERMISSIONS.forEach { info ->
            val granted = remember(refresh) { isGranted(context, info.permission) }
            ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(info.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (granted) "Granted" else "Not granted",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        info.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Button(
            onClick = { launcher.launch(PERMISSIONS.map { it.permission }.toTypedArray()) },
            modifier = Modifier.padding(vertical = 12.dp),
        ) { Text("Request permissions") }

        lastResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        // Deliberately separate from the button above: contacts is optional and is never
        // bundled into the main request.
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Contact names (optional)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (contactsGranted) "Granted" else "Not granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (contactsGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Turns an incoming number into a name, so a forward reads " +
                        "\"+15551234567 (Mum)\" instead of just the number. Everything works " +
                        "without this — templates simply show the bare number. Matching a " +
                        "rule against a contact does not need it either.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!contactsGranted) {
                    OutlinedButton(
                        onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Enable contact names") }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Failure alerts (optional)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (notificationsGranted) "Granted" else "Not granted",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (notificationsGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Notifies you when a message could not be forwarded after every retry. " +
                        "Without it a failure is only visible if you open History — which is " +
                        "how you end up locked out of something at 3am.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!notificationsGranted) {
                    OutlinedButton(
                        onClick = { notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Enable failure alerts") }
                }
            }
        }

        Text(
            "This app never sends anything anywhere except the forwarders you configure. " +
                "No analytics, no third-party services in the data path.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
