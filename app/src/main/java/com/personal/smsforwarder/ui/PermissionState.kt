package com.personal.smsforwarder.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.personal.smsforwarder.core.SmsPermissions

/** The three the app cannot work without, requested together. */
val REQUIRED_SMS_PERMISSIONS = listOf(
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_SMS,
    Manifest.permission.SEND_SMS,
)

@Immutable
class PermissionsUi(
    val sms: SmsPermissions,
    val contacts: Boolean,
    val notifications: Boolean,
    /**
     * True when Android will no longer show the dialog, so the only route left is the
     * system settings page. Detecting this is the difference between a button that works
     * and a button that appears to do nothing at all.
     */
    val mustUseSettings: Boolean,
    val requestSms: () -> Unit,
    val requestContacts: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAppSettings: () -> Unit,
)

/**
 * Live permission state for the composables that care.
 *
 * Re-reads on every resume, which is what makes the round trip to system settings work:
 * the app is backgrounded while the user flips the switch, so nothing short of a
 * lifecycle observer would notice the grant.
 */
@Composable
fun rememberPermissions(): PermissionsUi {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var refresh by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val smsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        asked = true
        refresh++
    }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }

    return remember(refresh, asked, activity) {
        val sms = SmsPermissions(
            receiveSms = context.granted(Manifest.permission.RECEIVE_SMS),
            readSms = context.granted(Manifest.permission.READ_SMS),
            sendSms = context.granted(Manifest.permission.SEND_SMS),
        )
        // shouldShowRequestPermissionRationale is false both before the first ask and
        // after a permanent denial, so it only means "permanently denied" once we know
        // an ask has happened.
        val stuck = asked && activity != null && REQUIRED_SMS_PERMISSIONS.any {
            !context.granted(it) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        PermissionsUi(
            sms = sms,
            contacts = context.granted(Manifest.permission.READ_CONTACTS),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.granted(Manifest.permission.POST_NOTIFICATIONS),
            mustUseSettings = stuck,
            requestSms = { smsLauncher.launch(REQUIRED_SMS_PERMISSIONS.toTypedArray()) },
            requestContacts = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
            requestNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            openAppSettings = { context.openAppSettings() },
        )
    }
}

fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Deep link to this app's own permission page, for when the dialog is no longer offered. */
fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Compose's LocalContext may be wrapped; the activity is what asks for permissions. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
