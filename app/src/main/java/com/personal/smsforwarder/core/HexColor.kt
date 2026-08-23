package com.personal.smsforwarder.core

/**
 * Parsing and formatting for the accent colour's hex field.
 *
 * The RGB sliders can express every colour but are hopeless for reproducing a specific
 * one — nobody knows their brand colour as three separate 0-255 numbers, they know it as
 * `#1F6FEB`. Typing or pasting that has to work, including the forms people actually
 * copy: with or without the hash, upper or lower case, and the three-digit shorthand.
 */
object HexColor {

    /** 0xRRGGBB, or null when [text] isn't a colour. Alpha is never accepted; it's always opaque. */
    fun parse(text: String): Int? {
        val cleaned = text.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
        if (cleaned.any { it !in "0123456789abcdefABCDEF" }) return null

        val six = when (cleaned.length) {
            6 -> cleaned
            // #abc is the CSS shorthand for #aabbcc, and people do paste it.
            3 -> cleaned.map { "$it$it" }.joinToString("")
            else -> return null
        }
        return six.toIntOrNull(16)
    }

    /** The canonical form shown in the field: uppercase, hash-prefixed, six digits. */
    fun format(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)
}
