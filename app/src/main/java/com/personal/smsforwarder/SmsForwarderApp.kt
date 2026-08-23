package com.personal.smsforwarder

import android.app.Application
import android.content.Context
import com.personal.smsforwarder.core.MessageProcessor
import com.personal.smsforwarder.data.AppIconManager
import com.personal.smsforwarder.data.ContactResolver
import com.personal.smsforwarder.data.FailureNotifier
import com.personal.smsforwarder.data.SettingsStore
import com.personal.smsforwarder.model.AttemptStatus
import com.personal.smsforwarder.model.ForwardAttempt
import com.personal.smsforwarder.model.HistoryEntry
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.forwarder.ForwarderFactory
import com.personal.smsforwarder.work.WorkManagerDispatcher

/** Hand-rolled dependency container — three objects, no DI framework. */
class AppContainer(context: Context) {

    val store = SettingsStore(context)
    val forwarders = ForwarderFactory(context.applicationContext)

    /** Swaps the launcher icon between the manifest's activity-aliases. */
    val icons = AppIconManager(context.applicationContext)

    /** Returns null for everything until the user grants contacts access. */
    val contacts = ContactResolver(context.applicationContext)

    /** No-op until the user allows notifications. */
    val notifier = FailureNotifier(context.applicationContext)

    private val dispatcher = WorkManagerDispatcher(context.applicationContext)

    /**
     * Re-runs one previously failed delivery. The rule may since have been renamed or
     * deleted, so the attempt's own record of it is used rather than a live lookup.
     */
    fun retry(entry: HistoryEntry, attempt: ForwardAttempt): Boolean {
        val config = store.forwarder(attempt.forwarderId) ?: return false
        store.updateAttempt(
            entry.id, attempt.ruleId, attempt.forwarderId, AttemptStatus.PENDING, "retrying…"
        )
        dispatcher.dispatch(
            entry.id,
            IncomingSms(entry.sender, entry.body, entry.timestampMillis),
            Rule(id = attempt.ruleId, name = attempt.ruleName),
            config,
        )
        return true
    }

    val processor = MessageProcessor(
        rules = { store.rules.value },
        forwarders = { store.forwarders.value },
        history = store,
        dispatcher = dispatcher,
        forwardingEnabled = { store.forwardingEnabled.value },
    )
}

class SmsForwarderApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }
}
