package com.personal.smsforwarder.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.personal.smsforwarder.R

/**
 * Tells you when a forward has finally given up.
 *
 * The worst failure mode for this app is a silent one: a code never arrives and you find
 * out when you are locked out of something. History records every failure, but only if
 * you go and look. This is the push in the other direction.
 *
 * Only *terminal* failures notify — a retrying attempt may still succeed, and a
 * notification per attempt would train you to ignore them.
 */
class FailureNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Forwarding failures",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shown when a message could not be forwarded."
        }
        manager.createNotificationChannel(channel)
    }

    /** Notifications are optional; without the permission this is a no-op. */
    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifyFailure(forwarderName: String, ruleName: String, sender: String, detail: String) {
        if (!canNotify()) return

        // Launch intent rather than a direct MainActivity reference, so this layer
        // doesn't have to reach up into the UI package.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val open = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_forward)
            .setContentTitle("Forward failed: $forwarderName")
            .setContentText("From $sender — $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Rule: $ruleName\n$detail"))
            .setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        // Each failure gets its own id so several don't overwrite one another.
        runCatching { manager.notify(nextId(), notification) }
    }

    private companion object {
        const val CHANNEL_ID = "forward_failures"
        private var counter = 1000
        fun nextId(): Int = ++counter
    }
}
