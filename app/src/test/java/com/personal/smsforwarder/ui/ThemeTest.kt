package com.personal.smsforwarder.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accent picker accepts *any* colour, including ones that would be illegible as a
 * button label. These assert the derived scheme still clears the WCAG AA contrast bar
 * against the surface it is drawn on, whatever the user picks.
 */
class ThemeTest {

    /** WCAG relative-contrast ratio; 4.5:1 is the AA threshold for normal text. */
    private fun contrastRatio(a: Color, b: Color): Float {
        val high = maxOf(a.luminance(), b.luminance())
        val low = minOf(a.luminance(), b.luminance())
        return (high + 0.05f) / (low + 0.05f)
    }

    private val awkwardSeeds = listOf(
        Color(0xFF67E2A4), // pale green
        Color(0xFFFFF59D), // pale yellow
        Color(0xFFF7FCFC), // near white
        Color.White,
        Color.Yellow,
        Color(0xFF6750A4), // the default
        Color.Red,
        Color(0xFF0A0A0A), // near black
        Color.Black,
    )

    @Test
    fun `primary stays readable on the light surface for any accent`() {
        awkwardSeeds.forEach { seed ->
            val scheme = accentScheme(seed, dark = false)
            val ratio = contrastRatio(scheme.primary, scheme.surface)
            assertTrue("seed $seed gives only ${ratio}:1 against surface", ratio >= 4.5f)
        }
    }

    @Test
    fun `primary stays readable on the dark surface for any accent`() {
        awkwardSeeds.forEach { seed ->
            val scheme = accentScheme(seed, dark = true)
            val ratio = contrastRatio(scheme.primary, scheme.surface)
            assertTrue("seed $seed gives only ${ratio}:1 against surface", ratio >= 4.5f)
        }
    }

    @Test
    fun `onPrimary is readable on top of primary`() {
        awkwardSeeds.forEach { seed ->
            listOf(true, false).forEach { dark ->
                val scheme = accentScheme(seed, dark)
                val ratio = contrastRatio(scheme.onPrimary, scheme.primary)
                assertTrue("seed $seed (dark=$dark) gives only ${ratio}:1", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun `accent packing round-trips through the stored int`() {
        val rgb = (103 shl 16) or (226 shl 8) or 164
        assertEquals(0x67E2A4, rgb)
        assertEquals(Color(0xFF67E2A4), rgb.toAccentColor())
    }
}
