package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch

/**
 * Rule matching. Body patterns are Java regexes, matched case-insensitively with
 * `containsMatchIn` (partial match — you don't have to anchor them).
 *
 * A blank body pattern means "any". An invalid regex never matches; use [patternError]
 * to surface the problem in the editor instead of failing silently at 3am.
 */
object RuleMatcher {

    fun matches(rule: Rule, sms: IncomingSms): Boolean = explain(rule, sms).matches

    /**
     * Which half of a rule accepted a message. The editor's "try a message" box uses this
     * to say *why* something didn't match, since "no match" alone tells you nothing about
     * whether the sender or the body was at fault.
     */
    data class Explanation(val senderMatched: Boolean, val bodyMatched: Boolean) {
        val matches: Boolean get() = senderMatched && bodyMatched
    }

    fun explain(rule: Rule, sms: IncomingSms): Explanation = Explanation(
        senderMatched = matchesSender(rule.sender, sms.sender),
        bodyMatched = fieldMatches(rule.bodyPattern, sms.body),
    )

    /** `(include empty OR any include hits) AND no exclude hits`. */
    fun matchesSender(match: SenderMatch, sender: String): Boolean {
        if (match.exclude.any { it.matches(sender) }) return false
        return match.include.isEmpty() || match.include.any { it.matches(sender) }
    }

    private fun SenderCriterion.matches(sender: String): Boolean = when (this) {
        is SenderCriterion.Pattern -> fieldMatches(value, sender)
        is SenderCriterion.Number -> numbersMatch(value, sender)
        // Any number on the contact counts, so excluding a contact excludes all of them.
        is SenderCriterion.Contact -> numbers.any { numbersMatch(it, sender) }
    }

    private fun fieldMatches(pattern: String?, value: String): Boolean {
        val p = pattern?.trim().orEmpty()
        if (p.isEmpty()) return true
        val regex = compile(p) ?: return false
        return regex.containsMatchIn(value)
    }

    /**
     * Compares two phone numbers ignoring formatting.
     *
     * Equal digit strings always match. Otherwise the shorter must be a suffix of the
     * longer *and* be at least [MIN_SUFFIX_DIGITS] long, which is what makes
     * `+18065551234` match `8065551234` without making the five-digit short code `37268`
     * match every number ending in those digits.
     */
    fun numbersMatch(a: String, b: String): Boolean {
        val left = a.filter(Char::isDigit)
        val right = b.filter(Char::isDigit)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        val (shorter, longer) = if (left.length <= right.length) left to right else right to left
        return shorter.length >= MIN_SUFFIX_DIGITS && longer.endsWith(shorter)
    }

    /** Below this, a "number" is a short code and only an exact match counts. */
    private const val MIN_SUFFIX_DIGITS = 7

    fun compile(pattern: String): Regex? =
        runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }.getOrNull()

    /** Null when the pattern is blank or valid; otherwise the regex error message. */
    fun patternError(pattern: String?): String? {
        val p = pattern?.trim().orEmpty()
        if (p.isEmpty()) return null
        return runCatching { Regex(p) }.exceptionOrNull()?.message
    }
}
