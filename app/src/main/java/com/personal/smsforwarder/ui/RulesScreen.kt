package com.personal.smsforwarder.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.personal.smsforwarder.core.Defaults
import com.personal.smsforwarder.core.RuleMatcher
import com.personal.smsforwarder.data.SettingsStore
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch

@Composable
fun RulesScreen(store: SettingsStore, modifier: Modifier = Modifier) {
    val rules by store.rules.collectAsState()
    val forwarders by store.forwarders.collectAsState()
    val forwardingEnabled by store.forwardingEnabled.collectAsState()
    var editing by remember { mutableStateOf<Rule?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = Rule(name = "New rule", forwarderIds = listOf(Defaults.SMS_RELAY_ID))
            }) { Icon(Icons.Default.Add, contentDescription = "Add rule") }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
        ) {
            item {
                ScreenHeader(
                    "Rules",
                    "Every enabled rule is evaluated against each incoming message. " +
                        "A blank pattern matches anything.",
                )
                // Master switch, at the top of the screen it governs. When off, messages
                // are still matched and logged — they're just never delivered.
                ElevatedCard(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (forwardingEnabled) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (forwardingEnabled) "Forwarding is on" else "Forwarding is paused",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (forwardingEnabled)
                                    "Matching messages are delivered to your forwarders."
                                else
                                    "Nothing is delivered. Messages are still matched and " +
                                        "logged so you can see what you missed.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = forwardingEnabled,
                            onCheckedChange = { store.setForwardingEnabled(it) },
                        )
                    }
                }
            }
            itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                RuleCard(
                    rule = rule,
                    forwarders = forwarders,
                    canMoveUp = index > 0,
                    canMoveDown = index < rules.lastIndex,
                    onMove = { delta -> store.moveRule(rule.id, delta) },
                    onToggle = { store.upsertRule(rule.copy(enabled = it)) },
                    onEdit = { editing = rule },
                    onDelete = { store.deleteRule(rule.id) },
                )
            }
        }
    }

    editing?.let { rule ->
        RuleEditorDialog(
            rule = rule,
            forwarders = forwarders,
            onDismiss = { editing = null },
            onSave = {
                store.upsertRule(it)
                editing = null
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    forwarders: List<ForwarderConfig>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit rule") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete rule") }
            }
            PatternLine("sender", rule.sender.summary())
            PatternLine("body", rule.bodyPattern?.trim().orEmpty().ifEmpty { "(any)" })
            val names = rule.forwarderIds.mapNotNull { id -> forwarders.firstOrNull { it.id == id }?.name }
            Text(
                if (names.isEmpty()) "Fires: nothing selected" else "Fires: ${names.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Order is evaluation order, so it decides which rule gets credited when two
            // rules would deliver the same thing.
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
            }
        }
    }
}

/**
 * Live "does this match?" box inside the editor.
 *
 * Tests the *draft* rule, not the saved one, so you can see the effect of a pattern
 * before committing to it — and it reports which half failed, because "no match" on its
 * own doesn't tell you whether the sender or the body was the problem.
 */
@Composable
private fun RuleTester(rule: Rule) {
    // Collapsed by default: the editor dialog is already dense, and this is a check you
    // reach for deliberately rather than something you need on screen every time.
    var expanded by remember { mutableStateOf(false) }
    var sampleSender by remember { mutableStateOf("") }
    var sampleBody by remember { mutableStateOf("") }

    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
        Text(if (expanded) "Hide pattern test" else "Test this pattern")
    }

    if (!expanded) return

    Text(
        "Checks matching only, against your unsaved edits. Nothing is sent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Field("Sample sender", sampleSender) { sampleSender = it }
    Field("Sample body", sampleBody, singleLine = false) { sampleBody = it }

    if (sampleSender.isNotBlank() || sampleBody.isNotBlank()) {
        val result = RuleMatcher.explain(
            rule, IncomingSms(sampleSender, sampleBody, System.currentTimeMillis())
        )
        val summary = when {
            result.matches -> "Matches"
            !result.senderMatched && !result.bodyMatched -> "No match — sender and body both fail"
            !result.senderMatched -> "No match — sender doesn't match"
            else -> "No match — body doesn't match"
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (result.matches) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Editor for one side of a [SenderMatch] — the include list or the exclude list.
 *
 * Contacts are chosen with ACTION_PICK, which hands back the number and name without the
 * app ever holding READ_CONTACTS; matching then happens on the number like any other.
 */
@Composable
private fun SenderSection(
    title: String,
    emptyHint: String,
    criteria: List<SenderCriterion>,
    onChange: (List<SenderCriterion>) -> Unit,
) {
    var addMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        readContact(context, uri)?.let { onChange(criteria + it) }
    }

    // ACTION_PICK grants read access to the one phone row chosen. Collecting the
    // contact's *other* numbers needs READ_CONTACTS, so ask for it at the moment it is
    // actually needed; declining still yields a usable single-number criterion.
    val requestContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pickContact.launch(
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        )
    }

    // Title and the add control share a row; with a separate heading, hint and button
    // each section was spending three rows on almost nothing.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (criteria.isEmpty()) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            TextButton(onClick = { addMenu = true }) { Text("Add…") }
            SenderAddMenu(
                expanded = addMenu,
                onDismiss = { addMenu = false },
                onPick = { onChange(criteria + it) },
                onPickContact = {
                    if (hasContactsPermission(context)) {
                        pickContact.launch(
                            Intent(
                                Intent.ACTION_PICK,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            )
                        )
                    } else {
                        requestContacts.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
            )
        }
    }

    criteria.forEachIndexed { index, criterion ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (criterion) {
                is SenderCriterion.Pattern -> Field(
                    "Regex", criterion.value, modifier = Modifier.weight(1f), monospace = true,
                    isError = RuleMatcher.patternError(criterion.value) != null,
                ) { onChange(criteria.replaceAt(index, SenderCriterion.Pattern(it))) }

                is SenderCriterion.Number -> Field(
                    "Number", criterion.value, modifier = Modifier.weight(1f), monospace = true,
                    supporting = "Country code optional; short codes match exactly",
                ) { onChange(criteria.replaceAt(index, SenderCriterion.Number(it))) }

                is SenderCriterion.Contact -> Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(criterion.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // Spell out how many numbers are covered — for an exclusion this
                        // is the difference between blocking a contact and blocking one
                        // of their numbers.
                        when (criterion.numbers.size) {
                            0 -> "no numbers captured"
                            1 -> criterion.numbers.single()
                            else -> "${criterion.numbers.size} numbers: " +
                                criterion.numbers.joinToString(", ")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { onChange(criteria.filterIndexed { i, _ -> i != index }) }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }

}

@Composable
private fun SenderAddMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (SenderCriterion) -> Unit,
    onPickContact: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Regex") },
            onClick = {
                onDismiss()
                onPick(SenderCriterion.Pattern(""))
            },
        )
        DropdownMenuItem(
            text = { Text("Phone number") },
            onClick = {
                onDismiss()
                onPick(SenderCriterion.Number(""))
            },
        )
        DropdownMenuItem(
            text = { Text("Contact…") },
            onClick = {
                onDismiss()
                onPickContact()
            },
        )
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun hasContactsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Turns a picked phone row into a criterion carrying every number on that contact.
 *
 * The picked row is always readable thanks to the URI grant. Its siblings need
 * READ_CONTACTS; without it we fall back to the single number rather than failing, and
 * the editor shows how many were captured.
 */
private fun readContact(context: Context, uri: Uri): SenderCriterion.Contact? = runCatching {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
    )
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val picked = cursor.getString(0).orEmpty()
        val name = cursor.getString(1).orEmpty().ifBlank { picked }
        val contactId = cursor.getString(2)

        val all = linkedSetOf<String>()
        if (picked.isNotBlank()) all += picked
        if (contactId != null && hasContactsPermission(context)) {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null,
            )?.use { siblings ->
                while (siblings.moveToNext()) {
                    siblings.getString(0)?.takeIf { it.isNotBlank() }?.let { all += it }
                }
            }
        }
        SenderCriterion.Contact(name, all.toList())
    }
}.getOrNull()

/** One-line description of a sender matcher for the rule card. */
private fun SenderMatch.summary(): String = when {
    isAny -> "(any)"
    else -> buildList {
        if (include.isNotEmpty()) add(include.joinToString(" or ") { it.label() })
        if (exclude.isNotEmpty()) add("except ${exclude.joinToString(", ") { it.label() }}")
    }.joinToString("  ")
}

private fun SenderCriterion.label(): String = when (this) {
    is SenderCriterion.Pattern -> "/$value/"
    is SenderCriterion.Number -> value
    is SenderCriterion.Contact ->
        if (numbers.size > 1) "$name (${numbers.size} numbers)"
        else "$name (${numbers.firstOrNull() ?: "no number"})"
}

@Composable
private fun PatternLine(label: String, pattern: String?) {
    val shown = pattern?.trim().orEmpty().ifEmpty { "(any)" }
    Text(
        "$label: $shown",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // A pattern like the bank one is ~300 characters and wrapped to five lines,
        // making every card enormous. One line keeps cards uniform and scannable; the
        // full text is in the editor.
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RuleEditorDialog(
    rule: Rule,
    forwarders: List<ForwarderConfig>,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    var name by remember { mutableStateOf(rule.name) }
    var sender by remember { mutableStateOf(rule.sender) }
    var bodyPattern by remember { mutableStateOf(rule.bodyPattern.orEmpty()) }
    var selected by remember { mutableStateOf(rule.forwarderIds.toSet()) }

    val senderError = sender.include.plus(sender.exclude)
        .filterIsInstance<SenderCriterion.Pattern>()
        .firstNotNullOfOrNull { RuleMatcher.patternError(it.value) }
    val bodyError = RuleMatcher.patternError(bodyPattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit rule") },
        text = {
            // No heightIn here: AlertDialog already bounds this slot, and clamping the
            // content instead of the viewport makes it un-scrollable and truncated.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Name", name) { name = it }

                SenderSection(
                    title = "Sender must match",
                    emptyHint = "Any sender",
                    criteria = sender.include,
                    onChange = { sender = sender.copy(include = it) },
                )
                SenderSection(
                    title = "Except",
                    emptyHint = "No exceptions",
                    criteria = sender.exclude,
                    onChange = { sender = sender.copy(exclude = it) },
                )
                if (senderError != null) {
                    Text(
                        senderError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Field(
                    "Body pattern (regex, blank = any)", bodyPattern,
                    singleLine = false, monospace = true,
                    isError = bodyError != null,
                    supporting = bodyError ?: "Case-insensitive, partial match.",
                ) { bodyPattern = it }
                Row {
                    TextButton(onClick = { bodyPattern = Defaults.OTP_BODY_PATTERN }) {
                        Text("OTP pattern")
                    }
                    TextButton(onClick = { bodyPattern = Defaults.BANK_BODY_PATTERN }) {
                        Text("Bank pattern")
                    }
                }
                RuleTester(
                    rule.copy(
                        senderPattern = null,
                        sender = sender,
                        bodyPattern = bodyPattern.trim().ifEmpty { null },
                    )
                )

                Text(
                    "Forwarders to fire",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                forwarders.forEach { config ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = config.id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + config.id else selected - config.id
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(config.name)
                            if (!config.enabled) {
                                Text(
                                    "disabled in Forwarders",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (config is ForwarderConfig.SmsRelay) {
                            Badge(
                                "Primary",
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = senderError == null && bodyError == null && name.isNotBlank(),
                onClick = {
                    onSave(
                        rule.copy(
                            name = name.trim(),
                            senderPattern = null,
                            sender = sender,
                            bodyPattern = bodyPattern.trim().ifEmpty { null },
                            forwarderIds = forwarders.map { it.id }.filter { it in selected },
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
