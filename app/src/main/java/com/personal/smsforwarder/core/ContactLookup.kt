package com.personal.smsforwarder.core

/**
 * Reverse lookup from a sender to a contact name.
 *
 * An interface so the template layer stays free of Android types and testable on the JVM,
 * and so the whole feature can degrade to [None] when the user hasn't granted — or has
 * declined — the contacts permission.
 */
fun interface ContactLookup {

    /** Display name for a sender, or null when unknown, unmatched, or not permitted. */
    fun displayName(sender: String): String?

    companion object {
        /** Used when contacts access is unavailable; every lookup simply misses. */
        val None = ContactLookup { null }
    }
}
