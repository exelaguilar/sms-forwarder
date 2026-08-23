package com.personal.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    /** The formats people actually paste, all of which must be accepted unchanged. */
    @Test
    fun realNumbersInEveryCommonFormatAreAccepted() {
        listOf(
            "+18065551234",
            "18065551234",
            "8065551234",
            "+1 (806) 555-1234",
            "806-555-1234",
            "806.555.1234",
            "+44 20 7946 0958",
            "+91 98765 43210",
        ).forEach { assertNull("rejected $it", PhoneNumbers.problem(it)) }
    }

    /** Short codes are the reason the minimum is three digits and not seven. */
    @Test
    fun shortCodesAreAccepted() {
        listOf("37268", "911", "22395").forEach {
            assertNull("rejected short code $it", PhoneNumbers.problem(it))
        }
    }

    /** The bug that started this: free text saved happily and failed much later. */
    @Test
    fun freeTextIsRejected() {
        assertNotNull(PhoneNumbers.problem("hahah all text"))
        assertFalse(PhoneNumbers.isValid("hahah all text"))
    }

    @Test
    fun theReasonMentionsLettersWhenThatIsTheProblem() {
        assertTrue(PhoneNumbers.problem("call me maybe")!!.contains("Letters"))
    }

    @Test
    fun aNumberWithALetterInTheMiddleIsRejected() {
        assertNotNull(PhoneNumbers.problem("+1806555O234"))
    }

    @Test
    fun blankAndWhitespaceAreRejected() {
        assertNotNull(PhoneNumbers.problem(""))
        assertNotNull(PhoneNumbers.problem("   "))
    }

    @Test
    fun punctuationWithNoDigitsIsRejected() {
        assertNotNull(PhoneNumbers.problem("+"))
        assertNotNull(PhoneNumbers.problem("()- "))
    }

    @Test
    fun tooShortAndTooLongAreRejected() {
        assertNotNull(PhoneNumbers.problem("12"))
        assertNull(PhoneNumbers.problem("123"))
        // E.164 caps at 15 digits.
        assertNull(PhoneNumbers.problem("123456789012345"))
        assertNotNull(PhoneNumbers.problem("1234567890123456"))
    }

    @Test
    fun aPlusIsOnlyValidAtTheStart() {
        assertNull(PhoneNumbers.problem("+18065551234"))
        assertNotNull(PhoneNumbers.problem("1806+5551234"))
        assertNotNull(PhoneNumbers.problem("++18065551234"))
    }

    @Test
    fun charactersThatLookLikeSeparatorsButAreNotAreRejected() {
        assertNotNull(PhoneNumbers.problem("806*555*1234"))
        assertNotNull(PhoneNumbers.problem("806#5551234"))
        assertNotNull(PhoneNumbers.problem("806,555,1234"))
    }

    @Test
    fun normaliseKeepsTheLeadingPlusAndDropsFormatting() {
        assertEquals("+18065551234", PhoneNumbers.normalise("+1 (806) 555-1234"))
        assertEquals("8065551234", PhoneNumbers.normalise("806-555-1234"))
        assertEquals("+18065551234", PhoneNumbers.normalise("  +18065551234  "))
    }
}
