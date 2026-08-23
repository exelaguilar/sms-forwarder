package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDuplicatesTest {

    private val otp = Rule(
        id = "a",
        name = "OTP",
        bodyPattern = Defaults.OTP_BODY_PATTERN,
        forwarderIds = listOf("relay"),
    )

    @Test
    fun `an identical rule under a different name is a duplicate`() {
        val copy = otp.copy(id = "b", name = "Codes")
        assertEquals("a", RuleDuplicates.findDuplicate(copy, listOf(otp))?.id)
    }

    /** Editing a rule must not report the rule against itself. */
    @Test
    fun `a rule never duplicates itself`() {
        assertNull(RuleDuplicates.findDuplicate(otp.copy(name = "Renamed"), listOf(otp)))
    }

    @Test
    fun `a different body pattern is not a duplicate`() {
        val other = otp.copy(id = "b", bodyPattern = "something else")
        assertNull(RuleDuplicates.findDuplicate(other, listOf(otp)))
    }

    @Test
    fun `whitespace around a pattern does not make a new rule`() {
        val padded = otp.copy(id = "b", bodyPattern = "  ${Defaults.OTP_BODY_PATTERN}  ")
        assertEquals("a", RuleDuplicates.findDuplicate(padded, listOf(otp))?.id)
    }

    @Test
    fun `blank and null body patterns are the same thing`() {
        val nullBody = Rule(id = "a", name = "Everything", bodyPattern = null)
        val blankBody = Rule(id = "b", name = "All", bodyPattern = "   ")
        assertEquals("a", RuleDuplicates.findDuplicate(blankBody, listOf(nullBody))?.id)
    }

    /** Criteria are a set: listing the same two senders in the other order is one rule. */
    @Test
    fun `criterion order does not matter`() {
        val first = Rule(
            id = "a",
            name = "Banks",
            sender = SenderMatch(
                include = listOf(SenderCriterion.Number("111"), SenderCriterion.Number("222"))
            ),
        )
        val reordered = first.copy(
            id = "b",
            sender = SenderMatch(
                include = listOf(SenderCriterion.Number("222"), SenderCriterion.Number("111"))
            ),
        )
        assertEquals("a", RuleDuplicates.findDuplicate(reordered, listOf(first))?.id)
    }

    /**
     * The same number typed two ways is the same rule; without normalising, a user who
     * reformatted a number would silently end up with two rules matching one sender.
     */
    @Test
    fun `formatting differences in a number are ignored`() {
        val plain = Rule(
            id = "a",
            name = "Bank",
            sender = SenderMatch(include = listOf(SenderCriterion.Number("18065551234"))),
        )
        val formatted = plain.copy(
            id = "b",
            sender = SenderMatch(include = listOf(SenderCriterion.Number("+1 (806) 555-1234"))),
        )
        assertEquals("a", RuleDuplicates.findDuplicate(formatted, listOf(plain))?.id)
    }

    @Test
    fun `include and exclude are not interchangeable`() {
        val included = Rule(
            id = "a",
            name = "Only",
            sender = SenderMatch(include = listOf(SenderCriterion.Number("111"))),
        )
        val excluded = included.copy(
            id = "b",
            sender = SenderMatch(exclude = listOf(SenderCriterion.Number("111"))),
        )
        assertNull(RuleDuplicates.findDuplicate(excluded, listOf(included)))
    }

    @Test
    fun `a contact with different numbers is a different rule`() {
        val one = Rule(
            id = "a",
            name = "Spam",
            sender = SenderMatch(exclude = listOf(SenderCriterion.Contact("SPAM", listOf("111")))),
        )
        val two = one.copy(
            id = "b",
            sender = SenderMatch(
                exclude = listOf(SenderCriterion.Contact("SPAM", listOf("111", "222")))
            ),
        )
        assertNull(RuleDuplicates.findDuplicate(two, listOf(one)))
    }

    @Test
    fun `forwarders are not part of the comparison`() {
        val elsewhere = otp.copy(id = "b", forwarderIds = listOf("email"))
        assertEquals("a", RuleDuplicates.findDuplicate(elsewhere, listOf(otp))?.id)
        assertFalse(RuleDuplicates.sameDestinations(elsewhere, otp))
        assertTrue(RuleDuplicates.sameDestinations(otp.copy(id = "c"), otp))
    }

    @Test
    fun `forwarder order does not count as a different destination set`() {
        val a = otp.copy(forwarderIds = listOf("relay", "email"))
        val b = otp.copy(id = "b", forwarderIds = listOf("email", "relay"))
        assertTrue(RuleDuplicates.sameDestinations(a, b))
    }

    /** The seeded rules must not trip the warning against each other. */
    @Test
    fun `the two default rules are not duplicates`() {
        val rules = Defaults.rules()
        rules.forEach { rule ->
            assertNull(
                "${rule.name} was reported as a duplicate",
                RuleDuplicates.findDuplicate(rule, rules),
            )
        }
    }
}
