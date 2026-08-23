package com.personal.smsforwarder.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.core.Action
import com.personal.smsforwarder.core.Issue
import com.personal.smsforwarder.core.Severity

/**
 * The "why is nothing happening?" answer, at the top of the screens it applies to.
 *
 * One card per issue rather than a single rolled-up one, because the fix differs per
 * issue and a button has to belong to exactly one of them.
 */
@Composable
fun ReadinessBanner(issues: List<Issue>, onFix: (Issue) -> Unit, modifier: Modifier = Modifier) {
    if (issues.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        issues.forEach { issue ->
            val blocking = issue.severity == Severity.Blocking
            ElevatedCard(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (blocking) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (blocking) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            issue.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    Text(
                        issue.detail,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    issue.action.label()?.let { label ->
                        TextButton(onClick = { onFix(issue) }, modifier = Modifier.padding(top = 4.dp)) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }
}

/** Null where the user is already looking at the thing they need to change. */
private fun Action?.label(): String? = when (this) {
    Action.GrantPermissions -> "Grant permissions"
    Action.ConfigureForwarder -> "Set it up"
    Action.OpenRules, null -> null
}
