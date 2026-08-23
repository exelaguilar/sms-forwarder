package com.personal.smsforwarder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexColorTest {

    @Test
    fun acceptsTheFormsPeopleActuallyPaste() {
        listOf("#1F6FEB", "1F6FEB", "#1f6feb", "1f6feb", "0x1F6FEB", "  #1F6FEB  ")
            .forEach { assertEquals("failed on $it", 0x1F6FEB, HexColor.parse(it)) }
    }

    /** CSS shorthand: #abc means #aabbcc, and it does get pasted. */
    @Test
    fun expandsThreeDigitShorthand() {
        assertEquals(0xAABBCC, HexColor.parse("#abc"))
        assertEquals(0xFFFFFF, HexColor.parse("fff"))
        assertEquals(0x000000, HexColor.parse("#000"))
    }

    @Test
    fun rejectsAnythingThatIsNotSixOrThreeHexDigits() {
        listOf("", "#", "12345", "#1234567", "#GGGGGG", "hahah", "#12 34 56", "#1F6FEB80")
            .forEach { assertNull("accepted $it", HexColor.parse(it)) }
    }

    /** Alpha is never honoured; a colour is always opaque. */
    @Test
    fun eightDigitAlphaFormIsRejectedRatherThanTruncated() {
        assertNull(HexColor.parse("#FF1F6FEB"))
    }

    @Test
    fun formatIsCanonicalAndRoundTrips() {
        assertEquals("#1F6FEB", HexColor.format(0x1F6FEB))
        assertEquals("#000000", HexColor.format(0x000000))
        assertEquals("#0A0B0C", HexColor.format(0x0A0B0C))
        listOf(0x1F6FEB, 0x000000, 0xFFFFFF, 0x6750A4).forEach {
            assertEquals(it, HexColor.parse(HexColor.format(it)))
        }
    }

    /** Stored colours carry no alpha, but an opaque ARGB value must not leak one through. */
    @Test
    fun formatMasksAnythingAboveTheLowTwentyFourBits() {
        assertEquals("#1F6FEB", HexColor.format(0xFF1F6FEB.toInt()))
    }
}
