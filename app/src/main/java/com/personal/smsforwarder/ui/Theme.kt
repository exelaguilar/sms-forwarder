package com.personal.smsforwarder.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.personal.smsforwarder.model.Appearance

/** 0xRRGGBB stored in settings -> an opaque Compose colour. */
fun Int.toAccentColor(): Color = Color(0xFF000000L.toInt() or this)

@Composable
fun AppTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    val scheme = when {
        // Material You, when the platform actually has a palette to offer.
        appearance.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        else -> accentScheme(appearance.accentRgb.toAccentColor(), dark)
    }

    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Builds a usable scheme from a single accent colour.
 *
 * Material's own algorithm needs the material-color-utilities library; this is a
 * deliberately smaller stand-in that only derives the primary/secondary/tertiary families
 * by blending towards white and black, and leaves the neutral surfaces to the baseline
 * scheme. Good enough for an accent picker, and about thirty lines instead of a
 * dependency.
 */
fun accentScheme(seed: Color, dark: Boolean): ColorScheme = if (dark) {
    // Pull dim accents up so they stay visible on a dark surface.
    val primary = seed.blend(Color.White, 0.35f).lightenUntilVisible()
    darkColorScheme(
        primary = primary,
        onPrimary = seed.blend(Color.Black, 0.7f),
        primaryContainer = seed.blend(Color.Black, 0.45f),
        onPrimaryContainer = seed.blend(Color.White, 0.75f),
        secondary = primary.blend(Color.Gray, 0.4f),
        onSecondary = Color.Black,
        secondaryContainer = seed.blend(Color.Black, 0.6f),
        onSecondaryContainer = seed.blend(Color.White, 0.7f),
        tertiary = primary.blend(Color.White, 0.15f),
        onTertiary = Color.Black,
    )
} else {
    // `primary` is also used as a text/icon colour on light surfaces, so a pale accent
    // has to be darkened or buttons and labels become unreadable.
    val primary = seed.blend(Color.Black, 0.05f).darkenUntilReadable()
    lightColorScheme(
        primary = primary,
        onPrimary = contrastOn(primary),
        primaryContainer = seed.blend(Color.White, 0.78f),
        onPrimaryContainer = seed.blend(Color.Black, 0.55f),
        secondary = primary.blend(Color.Gray, 0.35f),
        onSecondary = Color.White,
        secondaryContainer = seed.blend(Color.White, 0.86f),
        onSecondaryContainer = seed.blend(Color.Black, 0.6f),
        tertiary = primary.blend(Color.Black, 0.15f),
        onTertiary = Color.White,
    )
}

/** Black or white, whichever stays readable on [background]. */
fun contrastOn(background: Color): Color =
    if (background.luminance() > 0.45f) Color.Black else Color.White

/**
 * Thresholds come from the WCAG contrast formula `(L1 + 0.05) / (L2 + 0.05)`. To clear
 * 4.5:1 against a near-white surface (luminance ~0.95) a colour must sit at or below
 * ~0.17; against a near-black surface (~0.01) it must reach ~0.23.
 */
private const val MAX_LUMINANCE_ON_LIGHT = 0.17f
private const val MIN_LUMINANCE_ON_DARK = 0.26f

/** Darkens until the colour reads as text on a light background. */
private fun Color.darkenUntilReadable(): Color {
    var color = this
    var guard = 0
    while (color.luminance() > MAX_LUMINANCE_ON_LIGHT && guard++ < 24) {
        color = color.blend(Color.Black, 0.1f)
    }
    return color
}

/** Lightens until the colour reads against a dark background. */
private fun Color.lightenUntilVisible(): Color {
    var color = this
    var guard = 0
    while (color.luminance() < MIN_LUMINANCE_ON_DARK && guard++ < 24) {
        color = color.blend(Color.White, 0.1f)
    }
    return color
}

private fun Color.blend(other: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * t,
        green = green + (other.green - green) * t,
        blue = blue + (other.blue - blue) * t,
        alpha = 1f,
    )
}
