package com.personal.smsforwarder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.core.MessageProcessor
import com.personal.smsforwarder.core.ProcessResult
import com.personal.smsforwarder.model.IncomingSms

/**
 * Pushes a hand-typed message through [MessageProcessor.process] — the same call the
 * BroadcastReceiver makes — so rules, forwarders, history and retries are all exercised
 * without a SIM. Only PDU decoding is skipped (that path is covered by the instrumented
 * test and by `adb emu sms send`).
 */
@Composable
fun SimulatorScreen(processor: MessageProcessor, modifier: Modifier = Modifier) {
    var sender by remember { mutableStateOf("+15555550123") }
    var body by remember { mutableStateOf("Your verification code is 458213. Do not share it.") }
    var result by remember { mutableStateOf<ProcessResult?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "The whole pipeline: every enabled rule is evaluated and matching forwarders " +
                "really fire — an SMS relay sends a real, billable message. To check a " +
                "pattern without sending anything, use Test this pattern in the rule editor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Field("Sender", sender) { sender = it }
        Field("Body", body, singleLine = false) { body = it }

        Button(
            onClick = {
                result = processor.process(
                    IncomingSms(sender.trim(), body, System.currentTimeMillis()),
                    simulated = true,
                )
            },
            modifier = Modifier.padding(vertical = 8.dp),
        ) { Text("Simulate incoming SMS") }

        result?.let { r ->
            ElevatedCard(Modifier.padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Result", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Matched rules: " +
                            r.matchedRules.joinToString(", ") { it.name }.ifEmpty { "none" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Forwarders fired: " +
                            r.dispatched.joinToString(", ") { (rule, config) ->
                                "${config.name} (${rule.name})"
                            }.ifEmpty { "none" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Delivery runs in WorkManager — watch the History tab for the outcome.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TextButton(onClick = {
            sender = "VERIFY"
            body = "123456 is your one-time passcode."
        }) { Text("Load an OTP sample") }

        TextButton(onClick = {
            sender = "+15551239876"
            body = "Hey, are we still on for dinner?"
        }) { Text("Load a non-matching sample") }
    }
}
