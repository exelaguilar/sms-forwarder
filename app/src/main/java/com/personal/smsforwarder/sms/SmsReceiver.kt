package com.personal.smsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.personal.smsforwarder.SmsForwarderApp
import com.personal.smsforwarder.core.MessageProcessor
import com.personal.smsforwarder.model.IncomingSms

/**
 * Parses the incoming broadcast into an [IncomingSms] and hands it to [MessageProcessor].
 *
 * That is deliberately all it does: no matching, no forwarding, no I/O. Everything worth
 * testing lives in MessageProcessor, which the simulator screen and the unit tests call
 * directly. This class only owns PDU decoding and multi-part concatenation.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val sms = parse(intent) ?: return
        runCatching { processorFactory(context).process(sms) }
            .onFailure { Log.e(TAG, "Failed to process incoming SMS", it) }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /**
         * Swapped out by the instrumented test to observe what the receiver produced.
         * Production always resolves the app's single [MessageProcessor].
         */
        @Volatile
        var processorFactory: (Context) -> MessageProcessor = { context ->
            (context.applicationContext as SmsForwarderApp).container.processor
        }

        /**
         * Concatenates the parts of a multi-part message into one body. Android delivers
         * all parts of a concatenated SMS in a single broadcast.
         */
        fun parse(intent: Intent): IncomingSms? {
            val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
                .getOrNull()
                ?.filterNotNull()
                ?: return null
            if (messages.isEmpty()) return null

            val first = messages.first()
            val sender = first.displayOriginatingAddress ?: first.originatingAddress ?: ""
            val body = messages.joinToString(separator = "") {
                it.displayMessageBody ?: it.messageBody ?: ""
            }
            val timestamp = first.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()

            return IncomingSms(sender = sender, body = body, timestampMillis = timestamp)
        }
    }
}
