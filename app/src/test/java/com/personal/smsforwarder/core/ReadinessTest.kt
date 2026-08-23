package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {

    private val relay = ForwarderConfig.SmsRelay(
        id = "relay",
        name = "SMS relay",
        enabled = true,
        destinationNumber = "+15555550123",
    )

    private val rule = Rule(
        id = "rule",
        name = "OTP",
        bodyPattern = "code",
        enabled = true,
        forwarderIds = listOf("relay"),
    )

    @Test
    fun `a fully configured app with permissions reports nothing`() {
        assertEquals(
            emptyList<Issue>(),
            Readiness.issues(SmsPermissions.ALL, listOf(relay), listOf(rule)),
        )
    }

    @Test
    fun `missing receive permission blocks everything`() {
        val issues = Readiness.issues(SmsPermissions.NONE, listOf(relay), listOf(rule))
        val blocking = issues.first { it.severity == Severity.Blocking }
        assertEquals("Nothing is being forwarded", blocking.title)
        assertEquals(Action.GrantPermissions, blocking.action)
    }

    @Test
    fun `read without receive is still not enough`() {
        val partial = SmsPermissions(receiveSms = false, readSms = true, sendSms = true)
        assertFalse(partial.canReceive)
        assertTrue(
            Readiness.issues(partial, listOf(relay), listOf(rule))
                .any { it.title == "Nothing is being forwarded" }
        )
    }

    // The case the whole feature exists for: a relay armed against nothing.
    @Test
    fun `a relay with no destination number blocks its own toggle`() {
        val problem = Readiness.problem(relay.copy(destinationNumber = ""), SmsPermissions.ALL)
        assertTrue(problem!!.blocksEnable)
    }

    @Test
    fun `a blank destination is caught even when it is only whitespace`() {
        assertTrue(Readiness.problem(relay.copy(destinationNumber = "   "), SmsPermissions.ALL)!!.blocksEnable)
    }

    /** Free text used to save happily and only fail later, at delivery time. */
    @Test
    fun `a destination that is not a phone number blocks the toggle`() {
        val problem = Readiness.problem(
            relay.copy(destinationNumber = "hahah all text"),
            SmsPermissions.ALL,
        )
        assertTrue(problem!!.blocksEnable)
        assertTrue(problem.message.contains("usable number"))
    }

    /** configProblem is what gates Save, so it must not need permissions to answer. */
    @Test
    fun `configProblem ignores permissions entirely`() {
        assertNull(Readiness.configProblem(relay))
        assertTrue(Readiness.configProblem(relay.copy(destinationNumber = "nope"))!!.blocksEnable)
    }

    @Test
    fun `a formatted number is accepted by the save gate`() {
        assertNull(Readiness.configProblem(relay.copy(destinationNumber = "+1 (806) 555-1234")))
    }

    /**
     * The distinction that decides whether a toggle refuses or merely warns: a permission
     * may be granted a minute from now, so switching the user's setup off would be wrong.
     */
    @Test
    fun `a missing send permission warns but does not block the toggle`() {
        val withoutSend = SmsPermissions(receiveSms = true, readSms = true, sendSms = false)
        val problem = Readiness.problem(relay, withoutSend)
        assertFalse(problem!!.blocksEnable)
    }

    @Test
    fun `a configured relay with full permissions has no problem`() {
        assertNull(Readiness.problem(relay, SmsPermissions.ALL))
    }

    @Test
    fun `an http forwarder needs a url`() {
        val http = ForwarderConfig.Http(id = "h", name = "Hook", enabled = true, url = "")
        assertTrue(Readiness.problem(http, SmsPermissions.ALL)!!.blocksEnable)
    }

    @Test
    fun `an email forwarder needs host, recipient and sender`() {
        val base = ForwarderConfig.Email(id = "e", name = "Mail", enabled = true)
        assertTrue(Readiness.problem(base, SmsPermissions.ALL)!!.blocksEnable)
        val hostOnly = base.copy(host = "smtp.example.com")
        assertTrue(Readiness.problem(hostOnly, SmsPermissions.ALL)!!.blocksEnable)
        val complete = hostOnly.copy(to = "me@example.com", from = "phone@example.com")
        assertNull(Readiness.problem(complete, SmsPermissions.ALL))
    }

    /** A disabled forwarder is not a problem, however broken it is. */
    @Test
    fun `disabled forwarders are not reported`() {
        val broken = relay.copy(enabled = false, destinationNumber = "")
        assertEquals(
            emptyList<Issue>(),
            Readiness.issues(
                SmsPermissions.ALL,
                listOf(broken),
                listOf(rule.copy(forwarderIds = emptyList(), enabled = false)),
            ).filter { it.forwarderId != null },
        )
    }

    @Test
    fun `no enabled rules is a warning, not a block`() {
        val issues = Readiness.issues(SmsPermissions.ALL, listOf(relay), listOf(rule.copy(enabled = false)))
        val issue = issues.single()
        assertEquals("No rules are enabled", issue.title)
        assertEquals(Severity.Warning, issue.severity)
    }

    @Test
    fun `an enabled rule pointing only at a disabled forwarder is flagged`() {
        val issues = Readiness.issues(
            SmsPermissions.ALL,
            listOf(relay.copy(enabled = false)),
            listOf(rule),
        )
        assertTrue(issues.any { it.title == "No enabled rule points at an enabled forwarder" })
    }

    @Test
    fun `blocking issues sort ahead of warnings`() {
        val issues = Readiness.issues(
            SmsPermissions.NONE,
            listOf(relay),
            listOf(rule.copy(enabled = false)),
        )
        assertEquals(Severity.Blocking, issues.first().severity)
    }
}
