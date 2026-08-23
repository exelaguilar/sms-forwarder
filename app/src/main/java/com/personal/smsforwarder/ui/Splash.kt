package com.personal.smsforwarder.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.personal.smsforwarder.R
import kotlinx.coroutines.delay

/** The launcher icon's artwork on its brand-blue disc, reused by the splash and About. */
@Composable
fun AppLogo(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1F6FEB)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}

/**
 * Cold-start animation: the logo springs in, holds briefly, then fades out to reveal the
 * app. Shown only on a genuinely fresh start (see MainActivity), never on rotation or
 * when returning from the background — an animation you can't skip gets old fast.
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val scale = remember { Animatable(0.7f) }
    val fade = remember { Animatable(0f) }
    val titleFade = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(durationMillis = 220))
        scale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        )
        titleFade.animateTo(1f, tween(durationMillis = 200))
        delay(320)
        fade.animateTo(0f, tween(durationMillis = 260))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .alpha(fade.value),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppLogo(size = 128.dp, modifier = Modifier.scale(scale.value))
            Text(
                "SMS Forwarder",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 24.dp).alpha(titleFade.value),
            )
        }
    }
}
