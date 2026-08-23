package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.Rule

/**
 * First-run seed data. The SMS relay is the primary path (nothing needed on the
 * receiving end), so the default rules select it and it starts enabled; the HTTP and
 * email forwarders exist but are disabled until configured.
 */
object Defaults {

    const val SMS_RELAY_ID = "forwarder-sms-relay"
    const val HTTP_ID = "forwarder-http"
    const val EMAIL_ID = "forwarder-email"

    /**
     * "Contains a 4-8 digit number AND an OTP-ish keyword", in either order.
     * Two lookaheads over the whole body; edit freely in the Rules screen.
     */
    const val OTP_BODY_PATTERN =
        """(?=.*\b\d{4,8}\b)(?=.*\b(code|otp|passcode|verification|verify|one[- ]?time|2fa|token|pin)\b)"""

    /**
     * "Mentions money AND a transaction word." Covers the common shapes:
     * `$42.10 purchase at ...`, `Card ending 1234 was charged USD 9.99`,
     * `INR 500.00 debited from A/c ...`, `Payment of £12 approved`.
     *
     * Currency symbols and codes are both matched, and the amount may come before or
     * after the keyword.
     */
    const val BANK_BODY_PATTERN =
        // An amount: symbol form ($507.95, £12.50), code form (USD 9.99),
        // comma-grouped ($2,000.00) or a bare decimal (507.95).
        """(?=.*(?:[$£€₹¥]\s?[\d,]+(?:\.\d{2})?""" +
            """|\b(?:USD|EUR|GBP|INR|CAD|AUD|JPY|MXN)\s?[\d,]+""" +
            """|\b\d{1,3}(?:,\d{3})+(?:\.\d{2})?\b""" +
            """|\b\d+\.\d{2}\b))""" +
            // ...and a banking signal. Fraud-verification texts ("Did you attempt Zelle
            // $2,000 Sent to Karen G?") carry no transaction verb at all, so transfer,
            // fraud and challenge vocabulary has to be here too.
            //
            // The phrases are deliberately narrower than the obvious words. A bare
            // "did you" matched "Did you get the $20 back?", and a bare "spent" matched
            // "I spent $50 on dinner" — both ordinary texts that merely mention money.
            // "did you <verb>" and "you spent" keep the bank phrasings and drop the
            // conversational ones. Likewise "card" alone is too common on its own.
            """(?=.*(?:\b(?:purchases?|transactions?|charges?|charged|debited?|credited?""" +
            """|withdraw\w*|payments?|approved|declined?|authoriz\w*|authoris\w*""" +
            """|zelle|venmo|fraud)\b""" +
            """|\byou spent\b""" +
            """|\b(?:debit|credit|gift|prepaid)\s+card\b""" +
            """|\bcard\s+ending\b|\bending\s+in\s+\d""" +
            """|\ba/c\b|\bacct\b|\baccount\b""" +
            """|\bwire\s+transfer\b|\btransfer(?:red|s)?\b|\bsent\s+to\b""" +
            """|\bdid\s+you\s+(?:just\s+)?(?:try|attempt|make|use|initiate|authoriz\w*|authoris\w*)""" +
            """|\breply\s+(?:yes|no|y|n)\b))"""

    fun forwarders(): List<ForwarderConfig> = listOf(
        ForwarderConfig.SmsRelay(
            id = SMS_RELAY_ID,
            name = "SMS relay to phone",
            enabled = true,
        ),
        ForwarderConfig.Http(
            id = HTTP_ID,
            name = "Webhook",
            enabled = false,
        ),
        ForwarderConfig.Email(
            id = EMAIL_ID,
            name = "Email",
            enabled = false,
        ),
    )

    fun rules(): List<Rule> = listOf(
        Rule(
            id = "rule-otp",
            name = "2FA / OTP codes",
            senderPattern = null,
            bodyPattern = OTP_BODY_PATTERN,
            enabled = true,
            forwarderIds = listOf(SMS_RELAY_ID),
        ),
        Rule(
            id = "rule-bank",
            name = "Bank & card transactions",
            senderPattern = null,
            bodyPattern = BANK_BODY_PATTERN,
            // On by default alongside OTP: a fraud alert you miss is as costly as a code
            // you miss. Note each forwarded alert is a billable SMS on the relay.
            enabled = true,
            forwarderIds = listOf(SMS_RELAY_ID),
        ),
        Rule(
            id = "rule-all",
            name = "Everything (off by default)",
            senderPattern = null,
            bodyPattern = null,
            enabled = false,
            forwarderIds = listOf(SMS_RELAY_ID),
        ),
    )
}
