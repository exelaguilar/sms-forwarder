package com.personal.smsforwarder.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

    private val grace = 30_000L

    @Test
    fun `a disabled lock never engages`() {
        assertFalse(AppLock.shouldLock(false, backgroundedAtMillis = null, nowMillis = 0, graceMillis = grace))
        assertFalse(AppLock.shouldLock(false, backgroundedAtMillis = 0, nowMillis = 999_999, graceMillis = grace))
    }

    /** Null means the process has not been backgrounded yet, i.e. a cold start. */
    @Test
    fun `a cold start always locks`() {
        assertTrue(AppLock.shouldLock(true, backgroundedAtMillis = null, nowMillis = 1_000, graceMillis = grace))
    }

    @Test
    fun `returning inside the grace window does not prompt`() {
        assertFalse(AppLock.shouldLock(true, backgroundedAtMillis = 1_000, nowMillis = 6_000, graceMillis = grace))
    }

    @Test
    fun `returning after the grace window prompts`() {
        assertTrue(AppLock.shouldLock(true, backgroundedAtMillis = 1_000, nowMillis = 40_000, graceMillis = grace))
    }

    /** Exactly at the boundary counts as expired; a lock that is late is a lock that failed. */
    @Test
    fun `the boundary itself locks`() {
        assertTrue(AppLock.shouldLock(true, backgroundedAtMillis = 1_000, nowMillis = 31_000, graceMillis = grace))
    }

    @Test
    fun `a zero grace locks on any trip to the background`() {
        assertTrue(AppLock.shouldLock(true, backgroundedAtMillis = 1_000, nowMillis = 1_000, graceMillis = 0))
    }

    /**
     * Time is read from elapsedRealtime, but a negative interval would still mean
     * something is wrong. Fail closed: a spurious prompt costs a fingerprint, a spurious
     * unlock costs the feature.
     */
    @Test
    fun `time moving backwards locks rather than unlocking`() {
        assertTrue(AppLock.shouldLock(true, backgroundedAtMillis = 90_000, nowMillis = 1_000, graceMillis = grace))
    }
}
