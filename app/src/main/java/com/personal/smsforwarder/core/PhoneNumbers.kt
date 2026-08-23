package com.personal.smsforwarder.core

/**
 * Destination-number validation.
 *
 * Deliberately permissive about *formatting* and strict about *content*: people paste
 * numbers as `+1 (806) 555-1234`, `806-555-1234` and `+18065551234`, and all three are
 * the same number. What must not get through is text — "hahah all text" was accepted and
 * saved, and the only sign anything was wrong came much later as a failed delivery.
 *
 * Not a libphonenumber replacement. It cannot tell you a number is unreachable, only that
 * it is not a phone number at all, which is the mistake worth catching at the keyboard.
 */
object PhoneNumbers {

    /**
     * E.164 allows at most 15 digits. Three is the shortest real short code, so anything
     * below that is a typo rather than a destination.
     */
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 15

    /** Punctuation people type in phone numbers, all of which carries no meaning. */
    private const val SEPARATORS = " -()./ "

    /** A human-readable reason the number is unusable, or null when it is fine. */
    fun problem(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Required — nothing can be sent without a destination."

        val body = trimmed.removePrefix("+")
        if (body.contains('+')) return "A + can only appear at the start."
        if (body.any { it.isLetter() }) return "Letters aren't valid in a phone number."

        val stray = body.firstOrNull { !it.isDigit() && it !in SEPARATORS }
        if (stray != null) return "\"$stray\" isn't valid. Use digits, and + ( ) - or spaces."

        val digits = body.count(Char::isDigit)
        if (digits == 0) return "No digits — that isn't a phone number."
        if (digits < MIN_DIGITS) return "Too short to be a number or a short code."
        if (digits > MAX_DIGITS) return "Too long — numbers are at most $MAX_DIGITS digits."

        return null
    }

    fun isValid(raw: String): Boolean = problem(raw) == null

    /** Just the digits, with any leading `+` preserved. Used for comparison, not sending. */
    fun normalise(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter(Char::isDigit)
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }
}
