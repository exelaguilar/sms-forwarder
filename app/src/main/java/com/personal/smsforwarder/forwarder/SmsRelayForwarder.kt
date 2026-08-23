package com.personal.smsforwarder.forwarder

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.personal.smsforwarder.core.TemplateRenderer
import com.personal.smsforwarder.core.describe
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Relays the message as a plain SMS from this device's own carrier connection.
 *
 * PLAIN SMS ONLY. Android exposes no public API for sending RCS from a third-party app
 * (RCS is implemented inside Google Messages / the carrier stack, and iMessage inside
 * Apple's), so there is no "send as RCS" option to add here. Plain SMS is the reliable
 * baseline and lands on any handset — Android or iPhone — regardless of messaging app.
 *
 * The send is only reported successful once the platform delivers the per-part
 * `sentIntent` result, i.e. the radio/carrier actually accepted it — not merely because
 * the API call didn't throw. Delivery reports are then awaited best-effort and appended
 * to the detail line, since many carriers never send them.
 */
class SmsRelayForwarder(private val context: Context) : Forwarder {

    override suspend fun send(
        request: ForwardRequest,
        config: ForwarderConfig,
        onProgress: (String) -> Unit,
    ): Result<String> {
        val c = config as ForwarderConfig.SmsRelay
        if (c.destinationNumber.isBlank()) {
            return Result.failure(ForwarderConfigException("No destination number configured"))
        }

        val text = TemplateRenderer.render(c.template, request)
        if (text.isBlank()) return Result.failure(ForwarderConfigException("Rendered message is empty"))

        // Resolving SmsManager, splitting the message and registering the result
        // receivers can all throw on a real handset (no telephony, no active
        // subscription, receiver limits). None of it may escape as an exception: on the
        // test-send path that crashes the app, and in the worker it would leave the
        // history attempt stuck at PENDING forever.
        return try {
            relay(c, text, onProgress)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // Log the full stack too — on a real handset this is the only way to see
            // what the platform actually objected to.
            Log.e(TAG, "SMS relay failed: ${t.describe()}", t)
            Result.failure(t)
        }
    }

    private suspend fun relay(
        c: ForwarderConfig.SmsRelay,
        text: String,
        onProgress: (String) -> Unit,
    ): Result<String> {
        val smsManager = smsManager(context)
        // divideMessage handles the 160/70-character split; sendMultipartTextMessage
        // reassembles on the receiving handset.
        val parts = smsManager.divideMessage(text)
        if (parts.isNullOrEmpty()) {
            return Result.failure(IllegalStateException("divideMessage produced no parts"))
        }
        val token = UUID.randomUUID().toString()
        val sentAction = "$ACTION_SENT.$token"
        val deliveredAction = "$ACTION_DELIVERED.$token"

        var sentCollector: ResultCollector? = null
        var deliveredCollector: ResultCollector? = null

        try {
            sentCollector = ResultCollector(context, sentAction, parts.size)
            deliveredCollector = ResultCollector(context, deliveredAction, parts.size)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            val deliveredIntents = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { i ->
                sentIntents += pendingIntent(context, sentAction, i)
                deliveredIntents += pendingIntent(context, deliveredAction, parts.size + i)
            }

            onProgress("handed ${parts.size} part(s) to the radio")
            try {
                smsManager.sendMultipartTextMessage(
                    c.destinationNumber, null, parts, sentIntents, deliveredIntents
                )
            } catch (t: Throwable) {
                // Missing SEND_SMS permission, malformed number, etc.
                return Result.failure(t)
            }

            val sentResults = sentCollector.await(SENT_TIMEOUT_MS)
                ?: return Result.failure(
                    IllegalStateException("No send result after ${SENT_TIMEOUT_MS / 1000}s (${parts.size} part(s))")
                )

            val failures = sentResults.filter { it != Activity.RESULT_OK }
            if (failures.isNotEmpty()) {
                val reasons = failures.joinToString(", ") { describeSendError(it) }
                return Result.failure(IllegalStateException("Carrier rejected ${failures.size}/${parts.size} part(s): $reasons"))
            }

            val accepted = "carrier accepted ${parts.size}/${parts.size} part(s)"
            // The important one: the message is away and billable at this point. Say so
            // now rather than staying silent for up to 25 more seconds waiting on a
            // delivery report that many carriers never send.
            onProgress("$accepted — waiting for a delivery report")
            val deliveryResults = deliveredCollector.await(DELIVERY_TIMEOUT_MS)
            val deliveryNote = when {
                deliveryResults == null -> "no delivery report (many carriers never send one)"
                deliveryResults.all { it == Activity.RESULT_OK } -> "delivered"
                else -> "delivery report reported failure"
            }
            return Result.success("$accepted; $deliveryNote")
        } finally {
            sentCollector?.close()
            deliveredCollector?.close()
        }
    }

    /** Collects the per-part broadcast result codes for one send. */
    private class ResultCollector(
        private val context: Context,
        action: String,
        private val expected: Int,
    ) {
        private val results = mutableListOf<Int>()
        private val done = CompletableDeferred<List<Int>>()

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                synchronized(results) {
                    results += resultCode
                    if (results.size >= expected) done.complete(results.toList())
                }
            }
        }

        init {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        suspend fun await(timeoutMs: Long): List<Int>? = withTimeoutOrNull(timeoutMs) { done.await() }

        fun close() {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    companion object {
        private const val TAG = "SmsRelayForwarder"

        const val ACTION_SENT = "com.personal.smsforwarder.SMS_SENT"
        const val ACTION_DELIVERED = "com.personal.smsforwarder.SMS_DELIVERED"

        private const val SENT_TIMEOUT_MS = 60_000L
        // Delivery reports arrive within seconds when they arrive at all, so a long wait
        // only delays telling the user the carrier already accepted the message.
        private const val DELIVERY_TIMEOUT_MS = 25_000L

        /**
         * Resolves the SmsManager to send with. On a dual-SIM or eSIM handset the
         * no-argument manager may not be bound to the subscription the user actually
         * chose for SMS, so bind explicitly when there is a valid default.
         */
        fun smsManager(context: Context): SmsManager {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                return SmsManager.getDefault()
                    ?: error("SmsManager unavailable — this device reports no SMS support")
            }
            val base = context.getSystemService(SmsManager::class.java)
                ?: error("SmsManager unavailable — this device reports no SMS support")
            // Wrapped: on some builds touching SubscriptionManager blows up in its own
            // static initializer, which is an Error rather than an Exception.
            val subId = runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }
                .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
            } else {
                base
            }
        }

        private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun describeSendError(code: Int): String = when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
            SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "rate limit exceeded"
            else -> "result code $code"
        }
    }
}
