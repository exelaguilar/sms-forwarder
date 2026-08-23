package com.personal.smsforwarder.forwarder

import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig

/**
 * One way of getting a message off the device.
 *
 * Returns `Result<String>` rather than `Result<Unit>`: the success value is a short
 * human-readable detail line ("HTTP 200", "carrier accepted 2/2 parts; delivered") that
 * the History screen shows. Without it there is no way to distinguish "the API call
 * didn't throw" from "the carrier actually took it", which is exactly the distinction
 * the SMS relay needs to report.
 */
interface Forwarder {

    /**
     * @param onProgress called as the send advances, e.g. the SMS relay reporting that
     * the carrier has accepted the message while it is still waiting on a delivery
     * report. Without it the UI could only show a spinner and guess at a time budget,
     * and History sat at PENDING with no detail for up to a minute and a half.
     */
    suspend fun send(
        request: ForwardRequest,
        config: ForwarderConfig,
        onProgress: (String) -> Unit = {},
    ): Result<String>
}

/**
 * A failure that retrying cannot fix: the forwarder is misconfigured (no URL, no SMTP
 * host, no destination number). The worker fails these immediately instead of burning
 * five backed-off attempts on a message that can never be delivered as configured.
 */
class ForwarderConfigException(message: String) : IllegalStateException(message)
