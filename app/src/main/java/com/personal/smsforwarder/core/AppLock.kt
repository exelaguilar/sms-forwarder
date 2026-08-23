package com.personal.smsforwarder.core

/**
 * When the app should ask for biometrics again.
 *
 * Separated from Android because both failure modes are silent: a lock that never engages
 * looks identical to one that works, and a lock that re-prompts after every system dialog
 * gets switched off by the user within a day. Neither shows up in a screenshot.
 *
 * Note this governs the *UI* only. Receiving and forwarding run in a broadcast receiver
 * and a worker, neither of which involves the activity — locking the screen never stops a
 * message being forwarded, which is the whole point of the app continuing to work while
 * the phone is in someone else's hand.
 */
object AppLock {

    /**
     * @param backgroundedAtMillis a monotonic timestamp from when the app last went to
     *   the background, or null if it has not been backgrounded this process — i.e. a
     *   cold start, which always locks.
     */
    fun shouldLock(
        enabled: Boolean,
        backgroundedAtMillis: Long?,
        nowMillis: Long,
        graceMillis: Long,
    ): Boolean {
        if (!enabled) return false
        if (backgroundedAtMillis == null) return true

        val away = nowMillis - backgroundedAtMillis
        // A negative interval means the clock moved under us. Fail closed: a spurious
        // prompt costs a fingerprint, a spurious unlock costs the whole feature.
        if (away < 0) return true

        return away >= graceMillis
    }
}
