package com.personal.smsforwarder.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.data.ConfigBackup
import com.personal.smsforwarder.data.ConfigBackupIo
import com.personal.smsforwarder.data.SettingsStore
import java.time.LocalDate

@Composable
fun BackupScreen(store: SettingsStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var includeSecrets by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ConfigBackup?>(null) }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(ConfigBackupIo.export(store, includeSecrets).toByteArray())
            }
            if (includeSecrets) {
                "Exported, including credentials. Keep this file somewhere private."
            } else {
                "Exported without credentials — you'll re-enter passwords and header values."
            }
        }.getOrElse { "Export failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text == null) {
            status = "Could not read that file."
            return@rememberLauncherForActivityResult
        }
        // Parsed but not applied: the user confirms against a summary first.
        ConfigBackupIo.parse(text).fold(
            onSuccess = { pending = it },
            onFailure = { status = "Not a valid backup: ${it.message ?: it.javaClass.simpleName}" },
        )
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Writes your rules and forwarders to a JSON file. History is never " +
                        "included — it holds the codes themselves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include credentials", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (includeSecrets)
                                "SMTP password and header values will be written in plain text."
                            else
                                "SMTP password and header values will be blanked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (includeSecrets) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                }
                Button(
                    onClick = { exporter.launch("sms-forwarder-${LocalDate.now()}.json") },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Export to file") }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Import", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Replaces all rules and forwarders with the contents of a backup file. " +
                        "Your history is left alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Import from file") }
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }

        Text(
            "There is no cloud backup by design, so this file is the only way to move a " +
                "setup to another phone or recover it after a reset.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }

    pending?.let { backup ->
        val summary = ConfigBackupIo.summarise(backup)
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Replace your configuration?") },
            text = {
                Column {
                    Text(
                        "This will replace everything you have now with " +
                            "${summary.rules} rule(s) and ${summary.forwarders} forwarder(s)."
                    )
                    Text(
                        "Exported ${summary.exportedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (!summary.containsSecrets) {
                        Text(
                            "This backup has no credentials in it, so you'll need to re-enter " +
                                "any SMTP password and header values before those forwarders work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ConfigBackupIo.apply(store, backup)
                    pending = null
                    status = "Imported ${summary.rules} rule(s) and ${summary.forwarders} forwarder(s)."
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}
