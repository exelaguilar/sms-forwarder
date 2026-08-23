package com.personal.smsforwarder.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed

/** Whether this device can authenticate at all, which decides if the lock may be armed. */
enum class LockAvailability {
    /** A biometric is enrolled, or a PIN/pattern/password is set. */
    Available,

    /** The hardware or the keyguard exists, but nothing is enrolled yet. */
    NoneEnrolled,

    /** No screen lock and no usable biometric: arming here would be a one-way door. */
    Unsupported,
}

/**
 * Never arm the lock without checking this first.
 *
 * If the app could lock itself on a device with no way to authenticate, the only recovery
 * would be clearing app data, which takes the rules, forwarders and credentials with it.
 */
fun lockAvailability(context: Context): LockAvailability {
    val hasScreenLock = runCatching {
        ContextCompat.getSystemService(context, KeyguardManager::class.java)?.isDeviceSecure
    }.getOrNull() == true

    val biometric = runCatching {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    }.getOrDefault(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)

    return when {
        hasScreenLock || biometric == BiometricManager.BIOMETRIC_SUCCESS -> LockAvailability.Available
        biometric == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockAvailability.NoneEnrolled
        else -> LockAvailability.Unsupported
    }
}

/**
 * The unlock gate, drawn over the whole app.
 *
 * Prompts once on appearance rather than on every recomposition, and leaves a Retry button
 * behind if the user dismisses it — a lock with no way back in is a bricked app.
 *
 * [onUnavailable] is the escape hatch for the case where authentication became impossible
 * *after* the lock was armed (screen lock removed, biometrics cleared by a security
 * update). It unlocks and disarms rather than trapping anyone behind a prompt that can
 * never succeed.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit, onUnavailable: (String) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() as? FragmentActivity }
    val unlocked by rememberUpdatedState(onUnlocked)
    val unavailable by rememberUpdatedState(onUnavailable)

    var status by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(attempt, activity) {
        val host = activity
        if (host == null) {
            // Should not happen, but failing open beats an unreachable app.
            unavailable("Could not show the unlock prompt on this device.")
            return@LaunchedEffect
        }
        if (lockAvailability(context) != LockAvailability.Available) {
            unavailable("This device no longer has a screen lock, so app lock has been turned off.")
            return@LaunchedEffect
        }

        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked()
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    when (code) {
                        // Nothing left to authenticate with. Disarm rather than trap.
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
                        ->
                            unavailable("App lock has been turned off: $message")

                        else -> status = message.toString()
                    }
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SMS Forwarder")
            .setSubtitle("Your rules, forwarders and message history are locked.")
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    // Combining the two authenticator constants is unsupported below
                    // API 30; this deprecated call is the only way to offer the PIN
                    // fallback there, and without a fallback a failed fingerprint is a
                    // locked-out user.
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()

        // Must wait for RESUMED. Called any earlier - which is exactly what happens when
        // the lock engages from ON_START - androidx.biometric shows the prompt and then
        // immediately cancels it, leaving "Authentication canceled" on screen and the
        // user having to press Unlock by hand every single time.
        lifecycle.withResumed {
            runCatching { prompt.authenticate(info) }
                .onFailure { status = it.message ?: "Could not start the unlock prompt." }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppLogo(size = 88.dp, modifier = Modifier.padding(bottom = 24.dp))
            Text("Locked", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Forwarding carries on in the background while the app is locked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            Button(
                onClick = { attempt++ },
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text("Unlock") }
        }
    }
}
