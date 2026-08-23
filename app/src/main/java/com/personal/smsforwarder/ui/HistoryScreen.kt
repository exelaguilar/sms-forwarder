package com.personal.smsforwarder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.data.SettingsStore
import com.personal.smsforwarder.model.AttemptStatus
import com.personal.smsforwarder.model.ForwardAttempt
import com.personal.smsforwarder.model.HistoryEntry

/** Single-select filter over the log; null means "show everything". */
sealed interface HistoryFilter {
    data class ByRule(val name: String) : HistoryFilter
    data class ByForwarder(val name: String) : HistoryFilter
}

@Composable
fun HistoryScreen(
    store: SettingsStore,
    modifier: Modifier = Modifier,
    onRetry: ((HistoryEntry, ForwardAttempt) -> Unit)? = null,
) {
    val history by store.history.collectAsState()
    var detail by remember { mutableStateOf<HistoryEntry?>(null) }
    var filter by remember { mutableStateOf<HistoryFilter?>(null) }

    // Filters are derived from what's actually in the log, so there are never chips for
    // rules or forwarders you've never seen fire.
    val ruleFilters = history.flatMap { it.matchedRuleNames }.distinct().sorted()
    val forwarderFilters = history.flatMap { e -> e.attempts.map { it.forwarderName } }
        .distinct().sorted()
    val shown = history.filter { entry ->
        when (val f = filter) {
            null -> true
            is HistoryFilter.ByRule -> f.name in entry.matchedRuleNames
            is HistoryFilter.ByForwarder -> entry.attempts.any { it.forwarderName == f.name }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            ScreenHeader("History", "Newest first, capped at 300 entries. Tap for full detail.")
            if (history.isNotEmpty()) {
                TextButton(
                    onClick = { store.clearHistory() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Clear history") }
            }
            if (ruleFilters.size + forwarderFilters.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick = { filter = null },
                        label = { Text("All") },
                    )
                    ruleFilters.forEach { name ->
                        FilterChip(
                            selected = filter == HistoryFilter.ByRule(name),
                            onClick = {
                                filter = HistoryFilter.ByRule(name).takeIf { it != filter }
                            },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(16.dp)) },
                        )
                    }
                    forwarderFilters.forEach { name ->
                        FilterChip(
                            selected = filter == HistoryFilter.ByForwarder(name),
                            onClick = {
                                filter = HistoryFilter.ByForwarder(name).takeIf { it != filter }
                            },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp)) },
                        )
                    }
                }
                if (filter != null) {
                    Text(
                        "${shown.size} of ${history.size} messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    "Nothing yet. Use the Simulate tab to push a message through the pipeline.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        items(shown, key = { it.id }) { entry ->
            HistoryCard(entry) { detail = entry }
        }
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text("Message detail") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DetailLine("From", entry.sender)
                    DetailLine("Received", formatTime(entry.timestampMillis))
                    if (entry.simulated) DetailLine("Source", "in-app simulator")
                    DetailLine(
                        "Matched rules",
                        entry.matchedRuleNames.joinToString(", ").ifEmpty { "none" },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(entry.body, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (entry.attempts.isEmpty()) {
                        Text("No forwarders fired.", style = MaterialTheme.typography.bodySmall)
                    }
                    entry.attempts.forEach { attempt ->
                        Text(
                            "${attempt.forwarderName} (rule: ${attempt.ruleName})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${attempt.status} — ${attempt.detail.ifBlank { "no detail yet" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor(attempt.status),
                        )
                        if (attempt.status == AttemptStatus.FAILED && onRetry != null) {
                            TextButton(onClick = { onRetry(entry, attempt) }) { Text("Retry") }
                        }
                        if (attempt.updatedAtMillis > 0) {
                            Text(
                                formatTime(attempt.updatedAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onClick: () -> Unit) {
    ElevatedCard(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    maskSender(entry.sender),
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(entry.timestampMillis), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                entry.body.take(60) + if (entry.body.length > 60) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.forwardingPaused) {
                Text(
                    "matched, but forwarding was paused",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (entry.matchedRuleNames.isEmpty()) {
                Text(
                    "no rule matched",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                entry.attempts.forEach { attempt -> AttemptBadge(attempt) }
            }
        }
    }
}

@Composable
private fun AttemptBadge(attempt: ForwardAttempt) {
    Badge(
        text = "${attempt.forwarderName}: ${attempt.status}",
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = statusColor(attempt.status),
    )
}

@Composable
private fun statusColor(status: AttemptStatus): Color = when (status) {
    AttemptStatus.SUCCESS -> Color(0xFF1B7F3B)
    AttemptStatus.FAILED -> MaterialTheme.colorScheme.error
    AttemptStatus.RETRYING -> Color(0xFFB26A00)
    AttemptStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}
