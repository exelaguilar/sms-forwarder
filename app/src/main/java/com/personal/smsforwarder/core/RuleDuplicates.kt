package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch

/**
 * Finds a rule that already matches exactly the messages a rule being saved would match.
 *
 * Two rules with the same criteria are not *harmful* — duplicate deliveries to the same
 * destination are collapsed at send time (see [deliveryKey]) — but they are confusing:
 * History credits whichever comes first, so editing the other one appears to do nothing.
 * That is the kind of bug you chase for twenty minutes, so it is worth a dialog.
 *
 * Comparison is on matching criteria only, never on name or forwarders. Two rules with
 * identical criteria and *different* forwarders are the one case where a duplicate is
 * deliberate — "the same messages, also to email" — so the caller is told which it is
 * rather than being blocked.
 */
object RuleDuplicates {

    /** The existing rule a candidate would duplicate, or null. */
    fun findDuplicate(candidate: Rule, existing: List<Rule>): Rule? =
        existing.firstOrNull { it.id != candidate.id && matchesSameMessages(it, candidate) }

    fun matchesSameMessages(a: Rule, b: Rule): Boolean =
        normalisePattern(a.bodyPattern) == normalisePattern(b.bodyPattern) &&
            normalise(a.sender) == normalise(b.sender)

    /** True when the duplicate also delivers to exactly the same places. */
    fun sameDestinations(a: Rule, b: Rule): Boolean =
        a.forwarderIds.toSet() == b.forwarderIds.toSet()

    /** Blank and null both mean "don't care", and trailing whitespace is not a rule. */
    private fun normalisePattern(pattern: String?): String? = pattern?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Order within an include/exclude list is irrelevant to matching, so compare as sets.
     */
    private fun normalise(match: SenderMatch): Pair<Set<String>, Set<String>> =
        match.include.map(::key).toSet() to match.exclude.map(::key).toSet()

    /**
     * A criterion reduced to what it actually tests. Numbers compare on digits alone, so
     * `+1 (806) 555-1234` and `18065551234` are recognised as the same rule rather than
     * quietly coexisting.
     */
    private fun key(criterion: SenderCriterion): String = when (criterion) {
        is SenderCriterion.Pattern -> "regex:" + criterion.value.trim()
        is SenderCriterion.Number -> "number:" + criterion.value.filter(Char::isDigit)
        is SenderCriterion.Contact ->
            "contact:" + criterion.name.trim().lowercase() + ":" +
                criterion.numbers.map { it.filter(Char::isDigit) }.toSortedSet().joinToString(",")
    }
}
