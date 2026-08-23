package com.personal.smsforwarder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.core.describe
import com.personal.smsforwarder.data.SettingsStore
import com.personal.smsforwarder.forwarder.ForwarderFactory
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HttpHeader
import com.personal.smsforwarder.model.HttpMethod
import com.personal.smsforwarder.model.IncomingSms
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ForwardersScreen(
    store: SettingsStore,
    factory: ForwarderFactory,
    modifier: Modifier = Modifier,
) {
    val forwarders by store.forwarders.collectAsState()
    val savedNumbers by store.knownNumbers.collectAsState()
    var editing by remember { mutableStateOf<ForwarderConfig?>(null) }
    var addMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Test-send progress lives here rather than in a snackbar: a snackbar cannot show a
    // running count, and it disappeared as soon as the screen left composition.
    var testingId by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableIntStateOf(0) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("SMS relay") },
                        onClick = {
                            addMenuOpen = false
                            editing = ForwarderConfig.SmsRelay(name = "SMS relay", enabled = true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Webhook (HTTP)") },
                        onClick = {
                            addMenuOpen = false
                            editing = ForwarderConfig.Http(name = "Webhook")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Email (SMTP)") },
                        onClick = {
                            addMenuOpen = false
                            editing = ForwarderConfig.Email(name = "Email")
                        },
                    )
                }
                FloatingActionButton(onClick = { addMenuOpen = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add forwarder")
                }
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item {
                ScreenHeader(
                    "Forwarders",
                    "Named destinations. Rules reference these by instance, so you can have " +
                        "several webhooks pointed at different URLs.",
                )
            }
            items(forwarders, key = { it.id }) { config ->
                ForwarderCard(
                    config = config,
                    onToggle = { store.upsertForwarder(config.withEnabled(it)) },
                    onEdit = { editing = config },
                    onDelete = { store.deleteForwarder(config.id) },
                    elapsedSeconds = if (testingId == config.id) elapsed else null,
                    progressText = if (testingId == config.id) progressText else null,
                    lastResult = results[config.id],
                    onTest = {
                        scope.launch {
                            testingId = config.id
                            elapsed = 0
                            progressText = null
                            results = results - config.id
                            // A relay send can legitimately take a while: the carrier
                            // acknowledgement, then the delivery report. Tick a counter so
                            // the UI shows progress instead of appearing frozen.
                            val ticker = launch {
                                while (true) {
                                    delay(1000)
                                    elapsed += 1
                                }
                            }
                            // Defence in depth: a forwarder that throws must surface as a
                            // message, never as a crashed app.
                            val result = try {
                                factory.forConfig(config).send(testRequest(), config) { progress ->
                                    progressText = progress
                                }
                            } catch (t: Throwable) {
                                Result.failure(t)
                            }
                            ticker.cancel()
                            testingId = null
                            progressText = null
                            results = results + (config.id to result.fold(
                                onSuccess = { "OK — $it" },
                                onFailure = { "FAILED — ${it.describe()}" },
                            ))
                        }
                    },
                )
            }
        }
    }

    editing?.let { config ->
        ForwarderEditorDialog(
            config = config,
            savedNumbers = savedNumbers,
            onForgetNumber = { store.forgetNumber(it) },
            onDismiss = { editing = null },
            onSave = {
                store.upsertForwarder(it)
                editing = null
            },
        )
    }
}

/** Worst-case wait before a test resolves; matches the forwarder timeouts. */
private const val SMS_TEST_BUDGET_SECONDS = 90
private const val NETWORK_TEST_BUDGET_SECONDS = 30

private fun testRequest() = ForwardRequest(
    sms = IncomingSms(
        sender = "+15550001111",
        body = "Test message from SMS Forwarder. Your code is 123456.",
        timestampMillis = System.currentTimeMillis(),
    ),
    ruleName = "Test send",
)

private fun ForwarderConfig.withEnabled(value: Boolean): ForwarderConfig = when (this) {
    is ForwarderConfig.SmsRelay -> copy(enabled = value)
    is ForwarderConfig.Http -> copy(enabled = value)
    is ForwarderConfig.Email -> copy(enabled = value)
}

private fun ForwarderConfig.summary(): String = when (this) {
    is ForwarderConfig.SmsRelay -> "SMS → ${destinationNumber.ifBlank { "(no number set)" }}"
    is ForwarderConfig.Http -> "$method ${url.ifBlank { "(no URL set)" }}"
    is ForwarderConfig.Email -> "SMTP ${host.ifBlank { "(no host set)" }}:$port → ${to.ifBlank { "(no recipient)" }}"
}

@Composable
private fun ForwarderCard(
    config: ForwarderConfig,
    elapsedSeconds: Int?,
    progressText: String?,
    lastResult: String?,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The badge sits under the name rather than beside it: on the title row it
                // ended up crowding the enable toggle.
                Column(Modifier.weight(1f)) {
                    Text(config.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        config.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 6.dp)) {
                        if (config is ForwarderConfig.SmsRelay) {
                            Badge(
                                "Primary",
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else {
                            Badge(
                                "Optional",
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Switch(checked = config.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit forwarder") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete forwarder") }
            }
            OutlinedButton(
                onClick = onTest,
                enabled = elapsedSeconds == null,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    if (config is ForwarderConfig.SmsRelay) "Send test (sends a real SMS)"
                    else "Send test message"
                )
            }

            if (elapsedSeconds != null) {
                val budget = if (config is ForwarderConfig.SmsRelay) SMS_TEST_BUDGET_SECONDS
                else NETWORK_TEST_BUDGET_SECONDS
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        // Real progress from the forwarder when it has any, elapsed time
                        // only as a fallback before the first report.
                        progressText?.let { "$it  (${elapsedSeconds}s)" }
                            ?: "Testing… ${elapsedSeconds}s of up to ${budget}s",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LinearProgressIndicator(
                    progress = { (elapsedSeconds.toFloat() / budget).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                if (config is ForwarderConfig.SmsRelay && progressText == null && elapsedSeconds > 12) {
                    Text(
                        "Still waiting on a delivery report — many carriers never send one, " +
                            "and a switched-off handset won't confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else if (lastResult != null) {
                Text(
                    lastResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lastResult.startsWith("OK")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ForwarderEditorDialog(
    config: ForwarderConfig,
    savedNumbers: List<String>,
    onForgetNumber: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ForwarderConfig) -> Unit,
) {
    var draft by remember { mutableStateOf(config) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit forwarder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val d = draft) {
                    is ForwarderConfig.SmsRelay -> SmsRelayFields(d, savedNumbers, onForgetNumber) { draft = it }
                    is ForwarderConfig.Http -> HttpFields(d) { draft = it }
                    is ForwarderConfig.Email -> EmailFields(d) { draft = it }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SmsRelayFields(
    config: ForwarderConfig.SmsRelay,
    savedNumbers: List<String>,
    onForgetNumber: (String) -> Unit,
    onChange: (ForwarderConfig.SmsRelay) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }

    Field("Name", config.name) { onChange(config.copy(name = it)) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(
            "Destination number",
            config.destinationNumber,
            modifier = Modifier.weight(1f),
            supporting = "E.164 recommended, e.g. +15555551234",
        ) { onChange(config.copy(destinationNumber = it)) }

        // Numbers already used elsewhere, so a second or third forwarder to the same
        // handset doesn't mean retyping it.
        if (savedNumbers.isNotEmpty()) {
            Box {
                IconButton(onClick = { pickerOpen = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Saved numbers")
                }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    Text(
                        "Saved numbers",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    savedNumbers.forEach { number ->
                        DropdownMenuItem(
                            text = { Text(number) },
                            trailingIcon = {
                                IconButton(onClick = { onForgetNumber(number) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Forget $number",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                pickerOpen = false
                                onChange(config.copy(destinationNumber = number))
                            },
                        )
                    }
                }
            }
        }
    }
    Field(
        "Message template", config.template, singleLine = false, monospace = true,
        supporting = "Placeholders: {sender} {body} {timestamp} {rule_name}. " +
            "Long messages are split automatically.",
    ) { onChange(config.copy(template = it)) }
    Text(
        "Sends plain SMS over this device's carrier connection. RCS/iMessage cannot be " +
            "sent by a third-party app — there is no public API for it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HttpFields(config: ForwarderConfig.Http, onChange: (ForwarderConfig.Http) -> Unit) {
    Field("Name", config.name) { onChange(config.copy(name = it)) }
    Field("URL", config.url, monospace = true) { onChange(config.copy(url = it)) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HttpMethod.entries.forEach { method ->
            OutlinedButton(onClick = { onChange(config.copy(method = method)) }) {
                Text(if (config.method == method) "● ${method.name}" else method.name)
            }
        }
    }

    Field("Content-Type", config.contentType, monospace = true) { onChange(config.copy(contentType = it)) }
    Field(
        "Body template", config.bodyTemplate, singleLine = false, monospace = true,
        supporting = "Values are JSON-escaped when Content-Type contains \"json\".",
    ) { onChange(config.copy(bodyTemplate = it)) }

    Text("Headers", style = MaterialTheme.typography.titleSmall)
    config.headers.forEachIndexed { index, header ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Field("Name", header.name, modifier = Modifier.weight(1f), monospace = true) {
                onChange(config.copy(headers = config.headers.replaceAt(index, header.copy(name = it))))
            }
            Field("Value", header.value, modifier = Modifier.weight(1f), monospace = true) {
                onChange(config.copy(headers = config.headers.replaceAt(index, header.copy(value = it))))
            }
            IconButton(onClick = { onChange(config.copy(headers = config.headers.withoutAt(index))) }) {
                Icon(Icons.Default.Delete, "Remove header")
            }
        }
    }
    TextButton(onClick = { onChange(config.copy(headers = config.headers + HttpHeader("", ""))) }) {
        Text("Add header")
    }
}

@Composable
private fun EmailFields(config: ForwarderConfig.Email, onChange: (ForwarderConfig.Email) -> Unit) {
    Field("Name", config.name) { onChange(config.copy(name = it)) }
    Field("SMTP host", config.host, monospace = true) { onChange(config.copy(host = it)) }
    Field("Port", config.port.toString()) { text ->
        onChange(config.copy(port = text.filter { it.isDigit() }.take(5).toIntOrNull() ?: 0))
    }
    Field("Username (blank = no auth)", config.username) { onChange(config.copy(username = it)) }
    Field("Password", config.password, supporting = "Stored in EncryptedSharedPreferences.") {
        onChange(config.copy(password = it))
    }
    CheckboxRow("STARTTLS (port 587)", config.useStartTls) {
        onChange(config.copy(useStartTls = it, useSsl = if (it) false else config.useSsl))
    }
    CheckboxRow("Implicit SSL/TLS (port 465)", config.useSsl) {
        onChange(config.copy(useSsl = it, useStartTls = if (it) false else config.useStartTls))
    }
    Field("From", config.from) { onChange(config.copy(from = it)) }
    Field("To", config.to, supporting = "Comma-separate for several recipients.") {
        onChange(config.copy(to = it))
    }
    Field("Subject template", config.subjectTemplate, monospace = true) {
        onChange(config.copy(subjectTemplate = it))
    }
    Field("Body template", config.bodyTemplate, singleLine = false, monospace = true) {
        onChange(config.copy(bodyTemplate = it))
    }
}

@Composable
private fun CheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun <T> List<T>.withoutAt(index: Int): List<T> =
    filterIndexed { i, _ -> i != index }
