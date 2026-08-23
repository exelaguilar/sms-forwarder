package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.AttemptStatus
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HistoryEntry
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHistory : HistoryRecorder {
    val entries = mutableListOf<HistoryEntry>()
    override fun record(entry: HistoryEntry) {
        entries += entry
    }
}

private class FakeDispatcher : ForwardDispatcher {
    data class Dispatch(
        val entryId: String,
        val sms: IncomingSms,
        val ruleName: String,
        val forwarderId: String,
    )

    val dispatches = mutableListOf<Dispatch>()
    override fun dispatch(
        historyEntryId: String,
        sms: IncomingSms,
        rule: Rule,
        config: ForwarderConfig,
    ) {
        dispatches += Dispatch(historyEntryId, sms, rule.name, config.id)
    }
}

class MessageProcessorTest {

    private val relay = ForwarderConfig.SmsRelay(id = "f-sms", name = "Relay", destinationNumber = "+15550000000")
    private val webhook = ForwarderConfig.Http(id = "f-http", name = "Hook", enabled = true, url = "https://x/y")
    private val disabledWebhook = webhook.copy(id = "f-http-off", enabled = false)

    private fun processor(
        rules: List<Rule>,
        forwarders: List<ForwarderConfig> = listOf(relay, webhook, disabledWebhook),
        history: FakeHistory = FakeHistory(),
        dispatcher: FakeDispatcher = FakeDispatcher(),
    ) = Triple(
        MessageProcessor({ rules }, { forwarders }, history, dispatcher, now = { 1_700_000_000_000L }),
        history,
        dispatcher,
    )

    private fun sms(body: String, sender: String = "+15551234567") =
        IncomingSms(sender, body, 1_700_000_000_000L)

    // ---- rule matching table ------------------------------------------------

    private val otpRule = Rule(
        id = "r-otp",
        name = "OTP",
        bodyPattern = Defaults.OTP_BODY_PATTERN,
        forwarderIds = listOf(relay.id),
    )

    @Test
    fun `default OTP pattern matches real-world code messages`() {
        val matching = listOf(
            "Your verification code is 458213",
            "123456 is your one-time passcode",
            "Use code 9021 to log in",
            "G-773311 is your Google verification code",
            "Your OTP: 55123. Do not share.",
            "PIN 4821 for your delivery",
        )
        matching.forEach { body ->
            val (p, _, dispatcher) = processor(listOf(otpRule))
            p.process(sms(body))
            assertEquals("expected match for: $body", 1, dispatcher.dispatches.size)
        }
    }

    @Test
    fun `default OTP pattern ignores ordinary messages`() {
        val nonMatching = listOf(
            "Hey, are we still on for dinner?",
            "Your package will arrive Tuesday",
            "Call me back",
            "Your code is ready", // keyword but no digits
            "Balance: 4821 points remaining", // digits but no keyword
        )
        nonMatching.forEach { body ->
            val (p, _, dispatcher) = processor(listOf(otpRule))
            p.process(sms(body))
            assertEquals("expected no match for: $body", 0, dispatcher.dispatches.size)
        }
    }

    // ---- bank / card transaction pattern ------------------------------------

    private val bankRule = Rule(
        id = "r-bank",
        name = "Bank",
        bodyPattern = Defaults.BANK_BODY_PATTERN,
        forwarderIds = listOf(relay.id),
    )

    @Test
    fun `bank pattern matches real fraud-verification texts`() {
        // Verbatim from real carrier messages. The Zelle one is the awkward case: it has
        // an amount but no purchase/charge/debit verb anywhere.
        val realWorld = listOf(
            "Hi, it's Capital One. Did you just try to make this purchase with your Debit " +
                "card ending in 8527? B2B Prime $179.00 Text back \"yes\" or \"no\" to " +
                "protect your account. Text STOP to opt out. Call 1-833-489-1234 for help.",
            "MAIN STREET BANK*Did you attempt this purchase?, $507.95, *WALMART SUPERCENTER " +
                "(WA) Reply 'Y' to avoid card blocking, Reply 'N' to block card. Reply " +
                "'stop' to opt out.",
            "FreeMSG-Main Street Bank 1-833-763-2033: Reply YES or NO if you used debit card " +
                "ending 4321, WALGREENS, $105.95. STOP to opt out",
            "CHASE BANK Fraud Dept. Did you attempt Zelle $2,000.00 Sent to Karen G? Reply " +
                "YES OR NO. Reply STOP to cancel these texts.",
        )
        realWorld.forEach { body ->
            val (p, _, dispatcher) = processor(listOf(bankRule))
            p.process(sms(body))
            assertEquals(
                "expected bank match for: ${body.take(48)}…", 1, dispatcher.dispatches.size
            )
        }
    }

    @Test
    fun `bank pattern matches ordinary purchase alerts`() {
        val matching = listOf(
            "Your card ending 4821 was charged $42.10 at WHOLEFOODS",
            "A purchase of USD 9.99 was approved on your card",
            "INR 500.00 debited from A/c XX3421 on 22-08-26",
            "Payment of £12.50 to TFL TRAVEL approved",
            "Transaction declined: €80.00 at CARREFOUR",
            "You spent $7.25 at STARBUCKS",
        )
        matching.forEach { body ->
            val (p, _, dispatcher) = processor(listOf(bankRule))
            p.process(sms(body))
            assertEquals("expected bank match for: $body", 1, dispatcher.dispatches.size)
        }
    }

    @Test
    fun `bank pattern ignores ordinary texts that mention money`() {
        // The hard cases: personal messages containing an amount. Earlier versions of
        // this pattern matched several of these because "did you" and "spent" were in
        // the keyword list on their own.
        val personal = listOf(
            "did you eat today?",
            "Did you get the $20 back?",
            "I spent $50 on dinner last night",
            "Can you send me $20 when you get a chance",
            "The concert tickets were $45.00 each",
            "Let's split the $30 bill",
            "Did you see the $5 thing I sent you",
            "did you want to grab lunch, it's like $12",
        )
        personal.forEach { body ->
            val (p, _, dispatcher) = processor(listOf(bankRule))
            p.process(sms(body))
            assertEquals("false positive on: $body", 0, dispatcher.dispatches.size)
        }
    }

    @Test
    fun `bank pattern ignores unrelated messages`() {
        val nonMatching = listOf(
            "Your verification code is 458213",
            "Hey, are we still on for dinner?",
            "Your parcel is out for delivery",
            "Meeting moved to 3.30",
        )
        nonMatching.forEach { body ->
            val (p, _, dispatcher) = processor(listOf(bankRule))
            p.process(sms(body))
            assertEquals("expected no bank match for: $body", 0, dispatcher.dispatches.size)
        }
    }

    // ---- sender matching -----------------------------------------------------

    private fun senderRule(match: SenderMatch) =
        Rule(id = "r-s", name = "S", sender = match, forwarderIds = listOf(relay.id))

    private fun matchesSender(match: SenderMatch, from: String): Boolean {
        val (p, _, dispatcher) = processor(listOf(senderRule(match)))
        p.process(sms("anything", sender = from))
        return dispatcher.dispatches.isNotEmpty()
    }

    @Test
    fun `empty sender match accepts anyone`() {
        assertTrue(matchesSender(SenderMatch(), "VERIFY"))
        assertTrue(matchesSender(SenderMatch(), "+15551234567"))
    }

    @Test
    fun `include list is an OR`() {
        val match = SenderMatch(
            include = listOf(
                SenderCriterion.Pattern("^VERIFY$"),
                SenderCriterion.Number("+18065551234"),
            )
        )
        assertTrue(matchesSender(match, "VERIFY"))
        assertTrue(matchesSender(match, "8065551234"))
        assertTrue(!matchesSender(match, "+15559999999"))
    }

    @Test
    fun `exclude wins over include`() {
        val match = SenderMatch(
            include = listOf(SenderCriterion.Pattern(".*")),
            exclude = listOf(SenderCriterion.Number("37268")),
        )
        assertTrue(matchesSender(match, "+15551234567"))
        assertTrue(!matchesSender(match, "37268"))
    }

    @Test
    fun `exclude alone means everything except`() {
        val match = SenderMatch(
            exclude = listOf(SenderCriterion.Contact("Mum", listOf("+18065551234")))
        )
        assertTrue(matchesSender(match, "+15559999999"))
        assertTrue(!matchesSender(match, "806 555 1234"))
    }

    @Test
    fun `excluding a contact excludes every number on it`() {
        // A contact like "SPAM" with several numbers must be blocked wholesale, not just
        // on whichever number happened to be picked.
        val spam = SenderCriterion.Contact(
            "SPAM",
            listOf("+18001112222", "+18003334444", "37268", "8005556666"),
        )
        val match = SenderMatch(exclude = listOf(spam))

        assertTrue(!matchesSender(match, "+18001112222"))
        assertTrue(!matchesSender(match, "800 333 4444"))
        assertTrue(!matchesSender(match, "37268"))
        assertTrue(!matchesSender(match, "+18005556666"))
        // Anyone else still gets through.
        assertTrue(matchesSender(match, "+15559999999"))
    }

    @Test
    fun `including a contact matches any of its numbers`() {
        val work = SenderCriterion.Contact("Work", listOf("+18001112222", "+18003334444"))
        val match = SenderMatch(include = listOf(work))

        assertTrue(matchesSender(match, "+18001112222"))
        assertTrue(matchesSender(match, "+18003334444"))
        assertTrue(!matchesSender(match, "+18009998888"))
    }

    @Test
    fun `numbers compare ignoring formatting and country code`() {
        assertTrue(RuleMatcher.numbersMatch("+1 (806) 475-5252", "18064755252"))
        assertTrue(RuleMatcher.numbersMatch("+18064755252", "8064755252"))
        assertTrue(RuleMatcher.numbersMatch("8064755252", "+1-806-475-5252"))
        assertTrue(!RuleMatcher.numbersMatch("+18064755252", "+18064755253"))
    }

    @Test
    fun `short codes require an exact match`() {
        // Six digits is under the suffix threshold, so 37268 must not match a real
        // number that merely ends in those digits.
        assertTrue(RuleMatcher.numbersMatch("37268", "37268"))
        assertTrue(!RuleMatcher.numbersMatch("37268", "+18065537268"))
        assertTrue(!RuleMatcher.numbersMatch("262966", "1262966"))
    }

    @Test
    fun `blank patterns match everything`() {
        val rule = Rule(name = "All", forwarderIds = listOf(relay.id))
        val (p, _, dispatcher) = processor(listOf(rule))
        p.process(sms("literally anything"))
        assertEquals(1, dispatcher.dispatches.size)
    }

    @Test
    fun `invalid regex never matches and never throws`() {
        val rule = Rule(name = "Broken", bodyPattern = "([unclosed", forwarderIds = listOf(relay.id))
        val (p, history, dispatcher) = processor(listOf(rule))
        p.process(sms("code 123456"))
        assertEquals(0, dispatcher.dispatches.size)
        assertTrue(history.entries.single().matchedRuleNames.isEmpty())
    }

    @Test
    fun `disabled rules are skipped`() {
        val (p, _, dispatcher) = processor(listOf(otpRule.copy(enabled = false)))
        p.process(sms("code 123456"))
        assertEquals(0, dispatcher.dispatches.size)
    }

    // ---- master switch -------------------------------------------------------

    @Test
    fun `paused forwarding delivers nothing but still records what matched`() {
        val history = FakeHistory()
        val dispatcher = FakeDispatcher()
        val paused = MessageProcessor(
            rules = { listOf(otpRule) },
            forwarders = { listOf(relay) },
            history = history,
            dispatcher = dispatcher,
            forwardingEnabled = { false },
        )

        paused.process(sms("your code is 123456"))

        assertTrue("nothing may be delivered while paused", dispatcher.dispatches.isEmpty())
        val entry = history.entries.single()
        // The message must not look like it simply failed to match.
        assertEquals(listOf("OTP"), entry.matchedRuleNames)
        assertTrue(entry.forwardingPaused)
        assertTrue(entry.attempts.isEmpty())
    }

    @Test
    fun `enabled forwarding is unaffected by the switch`() {
        val history = FakeHistory()
        val dispatcher = FakeDispatcher()
        val live = MessageProcessor(
            rules = { listOf(otpRule) },
            forwarders = { listOf(relay) },
            history = history,
            dispatcher = dispatcher,
            forwardingEnabled = { true },
        )

        live.process(sms("your code is 123456"))

        assertEquals(1, dispatcher.dispatches.size)
        assertTrue(!history.entries.single().forwardingPaused)
    }

    // ---- match explanation (powers the editor's "try a message" box) ---------

    @Test
    fun `explain reports which half of the rule failed`() {
        val rule = Rule(
            id = "r",
            name = "R",
            sender = SenderMatch(include = listOf(SenderCriterion.Pattern("^BANK$"))),
            bodyPattern = Defaults.OTP_BODY_PATTERN,
        )

        val both = RuleMatcher.explain(rule, sms("your code is 1234", sender = "BANK"))
        assertTrue(both.matches)

        val badSender = RuleMatcher.explain(rule, sms("your code is 1234", sender = "OTHER"))
        assertTrue(!badSender.matches)
        assertTrue(!badSender.senderMatched)
        assertTrue(badSender.bodyMatched)

        val badBody = RuleMatcher.explain(rule, sms("dinner at 8", sender = "BANK"))
        assertTrue(!badBody.matches)
        assertTrue(badBody.senderMatched)
        assertTrue(!badBody.bodyMatched)

        val neither = RuleMatcher.explain(rule, sms("dinner at 8", sender = "OTHER"))
        assertTrue(!neither.senderMatched)
        assertTrue(!neither.bodyMatched)
    }

    // ---- dispatch behaviour -------------------------------------------------

    @Test
    fun `a rule fires every forwarder it references, skipping disabled ones`() {
        val rule = otpRule.copy(forwarderIds = listOf(relay.id, webhook.id, disabledWebhook.id))
        val (p, history, dispatcher) = processor(listOf(rule))
        p.process(sms("code 123456"))

        assertEquals(listOf(relay.id, webhook.id), dispatcher.dispatches.map { it.forwarderId })
        assertEquals(2, history.entries.single().attempts.size)
        assertTrue(history.entries.single().attempts.all { it.status == AttemptStatus.PENDING })
    }

    @Test
    fun `two matching rules firing different forwarders both fire`() {
        val second = Rule(id = "r-all", name = "All", forwarderIds = listOf(webhook.id))
        val (p, history, dispatcher) = processor(listOf(otpRule, second))
        p.process(sms("code 123456"))

        assertEquals(2, dispatcher.dispatches.size)
        assertEquals(listOf("OTP", "All"), history.entries.single().matchedRuleNames)
    }

    // ---- duplicate delivery suppression -------------------------------------

    @Test
    fun `two rules firing the same relay deliver only once`() {
        val second = Rule(id = "r-all", name = "All", forwarderIds = listOf(relay.id))
        val (p, history, dispatcher) = processor(listOf(otpRule, second))
        p.process(sms("code 123456"))

        assertEquals(1, dispatcher.dispatches.size)
        // The first matching rule wins attribution; both are still recorded as matching.
        assertEquals("OTP", dispatcher.dispatches.single().ruleName)
        assertEquals(listOf("OTP", "All"), history.entries.single().matchedRuleNames)
        assertEquals(1, history.entries.single().attempts.size)
    }

    @Test
    fun `relays pointed at different numbers both deliver`() {
        val other = relay.copy(id = "f-sms-2", name = "Other phone", destinationNumber = "+15559999999")
        val second = Rule(id = "r-all", name = "All", forwarderIds = listOf(other.id))
        val (p, _, dispatcher) = processor(listOf(otpRule, second), forwarders = listOf(relay, other))
        p.process(sms("code 123456"))

        assertEquals(setOf("f-sms", "f-sms-2"), dispatcher.dispatches.map { it.forwarderId }.toSet())
    }

    @Test
    fun `duplicate relay instances aimed at the same number deliver only once`() {
        // Same number, same template, different forwarder instance — the recipient would
        // otherwise get the identical text twice and be billed twice.
        val clone = relay.copy(id = "f-sms-clone", name = "Backup relay")
        val second = Rule(id = "r-all", name = "All", forwarderIds = listOf(clone.id))
        val (p, _, dispatcher) = processor(listOf(otpRule, second), forwarders = listOf(relay, clone))
        p.process(sms("code 123456"))

        assertEquals(1, dispatcher.dispatches.size)
    }

    @Test
    fun `different channels to the same person all deliver`() {
        val email = ForwarderConfig.Email(
            id = "f-mail", name = "Mail", enabled = true,
            host = "smtp.example.com", from = "a@example.com", to = "b@example.com",
        )
        val rule = Rule(id = "r", name = "R", forwarderIds = listOf(relay.id, webhook.id, email.id))
        val (p, _, dispatcher) = processor(listOf(rule), forwarders = listOf(relay, webhook, email))
        p.process(sms("code 123456"))

        assertEquals(3, dispatcher.dispatches.size)
    }

    @Test
    fun `same destination with different templates both deliver`() {
        // Different rendered text is a genuinely different message, so it is not a dupe.
        val variant = relay.copy(id = "f-sms-variant", template = "URGENT: {body}")
        val second = Rule(id = "r-all", name = "All", forwarderIds = listOf(variant.id))
        val (p, _, dispatcher) = processor(listOf(otpRule, second), forwarders = listOf(relay, variant))
        p.process(sms("code 123456"))

        assertEquals(2, dispatcher.dispatches.size)
    }

    @Test
    fun `attempts are keyed by rule id so duplicate rule names do not collide`() {
        // Two rules deliberately sharing a name, firing different forwarders.
        val a = Rule(id = "r-a", name = "Same name", forwarderIds = listOf(relay.id))
        val b = Rule(id = "r-b", name = "Same name", forwarderIds = listOf(webhook.id))
        val (p, history, _) = processor(listOf(a, b))
        p.process(sms("code 123456"))

        val attempts = history.entries.single().attempts
        assertEquals(2, attempts.size)
        assertEquals(setOf("r-a", "r-b"), attempts.map { it.ruleId }.toSet())
    }

    @Test
    fun `unmatched messages are still logged with no attempts`() {
        val (p, history, dispatcher) = processor(listOf(otpRule))
        p.process(sms("dinner at 8?"))

        val entry = history.entries.single()
        assertTrue(entry.matchedRuleNames.isEmpty())
        assertTrue(entry.attempts.isEmpty())
        assertTrue(dispatcher.dispatches.isEmpty())
    }

    @Test
    fun `history entry id is the one handed to the dispatcher`() {
        val (p, history, dispatcher) = processor(listOf(otpRule))
        val result = p.process(sms("code 123456"))

        assertEquals(history.entries.single().id, result.historyEntryId)
        assertEquals(result.historyEntryId, dispatcher.dispatches.single().entryId)
    }

    @Test
    fun `references to unknown forwarder ids are ignored`() {
        val rule = otpRule.copy(forwarderIds = listOf("does-not-exist"))
        val (p, _, dispatcher) = processor(listOf(rule))
        p.process(sms("code 123456"))
        assertEquals(0, dispatcher.dispatches.size)
    }
}
