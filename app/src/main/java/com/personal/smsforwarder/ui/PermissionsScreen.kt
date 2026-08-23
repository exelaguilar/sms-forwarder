package com.personal.smsforwarder.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PermissionsScreen(modifier: Modifier = Modifier) {
    val permissions = rememberPermissions()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Only what the app actually uses. INTERNET is install-time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        PermissionCard(
            "Receive SMS",
            "Delivers the SMS_RECEIVED broadcast this app listens for. Without it nothing " +
                "is forwarded.",
            permissions.sms.receiveSms,
            required = true,
        )
        PermissionCard(
            "Read SMS",
            "Required alongside Receive SMS to read the message body out of the broadcast.",
            permissions.sms.readSms,
            required = true,
        )
        PermissionCard(
            "Send SMS",
            "Only used by the SMS relay forwarder, which sends a plain SMS to your other " +
                "phone. Carrier message charges may apply per forwarded message.",
            permissions.sms.sendSms,
            required = true,
        )

        if (!permissions.sms.allGranted) {
            if (permissions.mustUseSettings) {
                // Android silently stops showing the dialog after a permanent denial, so
                // a "Request permissions" button here would do nothing at all.
                Text(
                    "Android will not show the permission dialog again for this app. The " +
                        "only route left is the system settings page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Button(
                    onClick = permissions.openAppSettings,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) { Text("Open app settings") }
            } else {
                Button(
                    onClick = permissions.requestSms,
                    modifier = Modifier.padding(vertical = 12.dp),
                ) { Text("Request permissions") }
            }
        }

        // Deliberately separate from the button above: these are optional and are never
        // bundled into the main request.
        OptionalCard(
            title = "Contact names (optional)",
            granted = permissions.contacts,
            body = "Turns an incoming number into a name, so a forward reads " +
                "\"+15551234567 (Mum)\" instead of just the number. Everything works " +
                "without this — templates simply show the bare number. Matching a rule " +
                "against a contact does not need it either.",
            action = "Enable contact names",
            onRequest = permissions.requestContacts,
        )

        OptionalCard(
            title = "Failure alerts (optional)",
            granted = permissions.notifications,
            body = "Notifies you when a message could not be forwarded after every retry. " +
                "Without it a failure is only visible if you open History — which is how " +
                "you end up locked out of something at 3am.",
            action = "Enable failure alerts",
            onRequest = permissions.requestNotifications,
        )

        Text(
            "This app never sends anything anywhere except the forwarders you configure. " +
                "No analytics, no third-party services in the data path.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun PermissionCard(label: String, why: String, granted: Boolean, required: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    granted -> MaterialTheme.colorScheme.primary
                    required -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionalCard(
    title: String,
    granted: Boolean,
    body: String,
    action: String,
    onRequest: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.labelLarge,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!granted) {
                OutlinedButton(onClick = onRequest, modifier = Modifier.padding(top = 8.dp)) {
                    Text(action)
                }
            }
        }
    }
}
