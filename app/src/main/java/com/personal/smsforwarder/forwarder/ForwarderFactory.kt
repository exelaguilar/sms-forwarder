package com.personal.smsforwarder.forwarder

import android.content.Context
import com.personal.smsforwarder.model.ForwarderConfig

/** Maps a config instance to the implementation that knows how to send it. */
class ForwarderFactory(private val context: Context) {

    private val http by lazy { HttpForwarder() }
    private val email by lazy { EmailForwarder() }
    private val smsRelay by lazy { SmsRelayForwarder(context) }

    fun forConfig(config: ForwarderConfig): Forwarder = when (config) {
        is ForwarderConfig.Http -> http
        is ForwarderConfig.Email -> email
        is ForwarderConfig.SmsRelay -> smsRelay
    }
}
