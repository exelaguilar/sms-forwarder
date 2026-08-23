package com.personal.smsforwarder.data

import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch

/**
 * Fix-ups applied to stored data on load.
 *
 * Pure functions, deliberately: migrations are exactly the code you cannot afford to get
 * wrong and cannot easily re-run, and keeping them free of Android types means they can
 * be unit-tested rather than only exercised by installing an old build.
 */
object Migrations {

    /**
     * Rules saved before sender matching became structured carry a bare regex in the
     * legacy `senderPattern`. Fold it into [SenderMatch]; dropping it would silently
     * widen the rule to "any sender", which is the dangerous direction.
     */
    fun migrateRules(rules: List<Rule>): List<Rule> = rules.map { rule ->
        val legacy = rule.senderPattern?.trim().orEmpty()
        if (legacy.isNotEmpty() && rule.sender.isAny) {
            rule.copy(
                senderPattern = null,
                sender = SenderMatch(include = listOf(SenderCriterion.Pattern(legacy))),
            )
        } else {
            rule
        }
    }

    /**
     * Numbers already configured on a relay count as "known", so the picker is useful
     * immediately rather than only after the next edit.
     */
    fun seedKnownNumbers(
        knownNumbers: List<String>,
        forwarders: List<ForwarderConfig>,
    ): List<String> {
        val inUse = forwarders
            .filterIsInstance<ForwarderConfig.SmsRelay>()
            .map { it.destinationNumber.trim() }
            .filter { it.isNotEmpty() }
        return (knownNumbers + inUse).distinct()
    }
}
