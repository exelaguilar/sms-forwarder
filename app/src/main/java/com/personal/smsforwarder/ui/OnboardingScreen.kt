package com.personal.smsforwarder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.core.PhoneNumbers
import com.personal.smsforwarder.data.SettingsStore
import com.personal.smsforwarder.model.ForwarderConfig

private const val TOTAL_STEPS = 3

/**
 * The first-run guide.
 *
 * Exists because the two things most likely to leave this app silently doing nothing —
 * declined SMS permissions, and a relay with no destination number — were previously only
 * discoverable by going looking for them. Three steps, skippable, and re-runnable from
 * Settings so it stays a reference rather than a one-shot.
 */
@Composable
fun OnboardingScreen(store: SettingsStore, onDone: () -> Unit) {
    val permissions = rememberPermissions()
    val forwarders by store.forwarders.collectAsState()
    val relay = remember(forwarders) {
        forwarders.filterIsInstance<ForwarderConfig.SmsRelay>().firstOrNull()
    }

    var step by remember { mutableIntStateOf(0) }
    var number by remember(relay?.id) { mutableStateOf(relay?.destinationNumber.orEmpty()) }

    // Blank is a legitimate answer here — it means "I'll use a webhook or set this up
    // later". Something that is neither blank nor a number is not.
    val numberProblem = if (number.isBlank()) null else PhoneNumbers.problem(number)

    fun finish() {
        val trimmed = number.trim()
        if (relay != null && trimmed.isNotEmpty() && trimmed != relay.destinationNumber) {
            store.upsertForwarder(relay.copy(destinationNumber = trimmed))
        }
        store.setOnboarded(true)
        onDone()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepDots(step, TOTAL_STEPS, Modifier.weight(1f))
                TextButton(
                    onClick = {
                        store.setOnboarded(true)
                        onDone()
                    }
                ) { Text("Skip") }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> PermissionStep(permissions)
                    else -> NumberStep(number, numberProblem, relay != null) { number = it }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }) { Text("Back") }
                }
                Button(
                    onClick = { if (step == TOTAL_STEPS - 1) finish() else step++ },
                    enabled = numberProblem == null || step != TOTAL_STEPS - 1,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when (step) {
                            0 -> "Get started"
                            TOTAL_STEPS - 1 -> "Finish"
                            else -> "Next"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AppLogo(size = 88.dp, modifier = Modifier.padding(top = 24.dp, bottom = 20.dp))
        Text("SMS Forwarder", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Codes and alerts, on whichever device you are actually holding.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
    }
    Bullet(
        "Rules decide what matters",
        "Two are ready to go: 2FA codes, and bank or card transactions.",
    )
    Bullet(
        "Forwarders decide where it goes",
        "A plain SMS to your other phone, a webhook, or your own email server.",
    )
    Bullet(
        "Nothing passes through anyone else",
        "Messages go straight from this phone to the destination you set. No account, no " +
            "server in the middle, no analytics.",
    )
}

@Composable
private fun PermissionStep(permissions: PermissionsUi) {
    Text("Let the app see your messages", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Android only delivers incoming SMS to apps holding these. Without them the app " +
            "runs, but never receives anything.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )

    GrantRow(
        "Receive SMS",
        "Delivers the broadcast the app listens for.",
        permissions.sms.receiveSms,
    )
    GrantRow(
        "Read SMS",
        "Reads the sender and body out of that broadcast.",
        permissions.sms.readSms,
    )
    GrantRow(
        "Send SMS",
        "Only for the relay, which sends a normal SMS to your other phone. Your carrier " +
            "charges for these as usual.",
        permissions.sms.sendSms,
    )

    when {
        permissions.sms.allGranted -> Text(
            "All set.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp),
        )

        // Android stops showing the dialog after a permanent denial, so the button that
        // now does nothing has to be replaced by one that goes somewhere.
        permissions.mustUseSettings -> {
            Text(
                "Android will not show the permission dialog again for this app. Grant " +
                    "them under Permissions, then come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = permissions.openAppSettings,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Open app settings") }
        }

        else -> {
            Button(
                onClick = permissions.requestSms,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Grant SMS permissions") }
            Text(
                "You can continue without these and grant them later. The app will keep " +
                    "telling you it is not receiving anything until you do.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun NumberStep(
    number: String,
    problem: String?,
    hasRelay: Boolean,
    onChange: (String) -> Unit,
) {
    Text("Where should messages go?", style = MaterialTheme.typography.headlineSmall)
    Text(
        "The number of the phone you want forwarded messages to arrive on. Any handset " +
            "works, iPhone included, because this sends ordinary SMS.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
    )

    if (hasRelay) {
        Field(
            "Destination number",
            number,
            isError = problem != null,
            supporting = problem ?: "E.164 recommended, e.g. +15555551234",
        ) { onChange(it) }
    } else {
        Text(
            "No SMS relay is configured. You can add one from the Forwarders tab.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Text(
        "Leave it blank to set up a webhook or email forwarder instead. Both live on the " +
            "Forwarders tab.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
private fun Bullet(title: String, body: String) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GrantRow(label: String, why: String, granted: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (granted) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "$label granted",
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "Needed",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(if (index == current) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
    }
}
