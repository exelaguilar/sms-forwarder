package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.ForwardAttempt
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HistoryEntry
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule

/** Where matched messages get handed off for actual (retrying, off-thread) delivery. */
interface ForwardDispatcher {
    fun dispatch(historyEntryId: String, sms: IncomingSms, rule: Rule, config: ForwarderConfig)
}

/** Sink for the history log. */
interface HistoryRecorder {
    fun record(entry: HistoryEntry)
}

/** What [MessageProcessor.process] decided, returned so the simulator UI can show it. */
data class ProcessResult(
    val historyEntryId: String,
    val matchedRules: List<Rule>,
    val dispatched: List<Pair<Rule, ForwarderConfig>>,
)

/**
 * The whole decision layer, in one directly-callable class.
 *
 * The BroadcastReceiver only parses the intent and calls [process]; the simulator screen
 * and unit tests call it exactly the same way, so every path below here is testable
 * without a radio, a SIM, or the Android framework.
 */
class MessageProcessor(
    private val rules: () -> List<Rule>,
    private val forwarders: () -> List<ForwarderConfig>,
    private val history: HistoryRecorder,
    private val dispatcher: ForwardDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
    /** Master switch. When off, messages are matched and logged but never delivered. */
    private val forwardingEnabled: () -> Boolean = { true },
) {

    fun process(sms: IncomingSms, simulated: Boolean = false): ProcessResult {
        val matched = rules().filter { it.enabled && RuleMatcher.matches(it, sms) }

        // Paused: still record what matched, so the log shows what you missed and why,
        // rather than the message vanishing as if no rule had matched.
        if (!forwardingEnabled()) {
            val entry = HistoryEntry(
                sender = sms.sender,
                body = sms.body,
                timestampMillis = sms.timestampMillis,
                matchedRuleNames = matched.map { it.name },
                attempts = emptyList(),
                simulated = simulated,
                forwardingPaused = true,
            )
            history.record(entry)
            return ProcessResult(entry.id, matched, emptyList())
        }

        val allForwarders = forwarders()
        val jobs: List<Pair<Rule, ForwarderConfig>> = matched
            .flatMap { rule ->
                rule.forwarderIds.mapNotNull { id ->
                    allForwarders.firstOrNull { it.id == id && it.enabled }?.let { rule to it }
                }
            }
            // Two rules matching one message must not deliver the same thing twice — that
            // is a duplicate on the recipient's phone and a second billable SMS. Keyed by
            // destination + rendered payload, so genuinely different deliveries survive.
            // Rules are evaluated in list order, so the first one wins attribution.
            .distinctBy { (rule, config) -> deliveryKey(config, ForwardRequest(sms, rule.name)) }

        val entry = HistoryEntry(
            sender = sms.sender,
            body = sms.body,
            timestampMillis = sms.timestampMillis,
            matchedRuleNames = matched.map { it.name },
            attempts = jobs.map { (rule, config) ->
                ForwardAttempt(
                    ruleId = rule.id,
                    ruleName = rule.name,
                    forwarderId = config.id,
                    forwarderName = config.name,
                    updatedAtMillis = now(),
                )
            },
            simulated = simulated,
        )
        history.record(entry)

        jobs.forEach { (rule, config) -> dispatcher.dispatch(entry.id, sms, rule, config) }

        return ProcessResult(entry.id, matched, jobs)
    }
}
