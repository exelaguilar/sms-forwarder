package com.personal.smsforwarder.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A received (or simulated) SMS, reduced to the only three things this app cares about.
 * Deliberately free of Android types so the whole pipeline is unit-testable on the JVM.
 */
data class IncomingSms(
    val sender: String,
    val body: String,
    val timestampMillis: Long,
)

/** What a forwarder is asked to send: the message plus the rule that matched it. */
data class ForwardRequest(
    val sms: IncomingSms,
    val ruleName: String,
    /** Contact name for the sender, when contacts access is granted and it matched. */
    val contactName: String? = null,
)

/** One test against a message's sender. */
@Serializable
sealed interface SenderCriterion {

    /** Java regex, matched case-insensitively against the raw sender. */
    @Serializable
    @SerialName("regex")
    data class Pattern(val value: String) : SenderCriterion

    /**
     * A phone number. Compared ignoring formatting, and tolerant of country-code
     * differences (`+18065551234` matches `8065551234`) — but short codes, being under
     * seven digits, must match exactly so `37268` never collides with a real number.
     */
    @Serializable
    @SerialName("number")
    data class Number(val value: String) : SenderCriterion

    /**
     * A contact, stored as its name plus **every** number on it.
     *
     * All of them, not just the one picked: "ignore SPAM" has to ignore all five numbers
     * filed under SPAM, and matching only the picked one would let the rest through
     * silently — the worst direction for an exclusion to fail in.
     *
     * The numbers are a snapshot taken when the contact was chosen, so matching itself
     * needs no contacts permission. Numbers added to the contact afterwards are not
     * picked up until it is re-selected; [numbers] is shown in the editor so the count is
     * visible rather than implied.
     */
    @Serializable
    @SerialName("contact")
    data class Contact(val name: String, val numbers: List<String> = emptyList()) : SenderCriterion
}

/**
 * Sender matching: `(include is empty OR any include matches) AND no exclude matches`.
 *
 * Both lists empty means "any sender". An empty include with a populated exclude is the
 * "everything except…" case.
 */
@Serializable
data class SenderMatch(
    val include: List<SenderCriterion> = emptyList(),
    val exclude: List<SenderCriterion> = emptyList(),
) {
    val isAny: Boolean get() = include.isEmpty() && exclude.isEmpty()
}

/**
 * A matching rule. A blank body pattern means "don't care"; an empty [sender] matches any
 * sender. Both empty => matches every message.
 */
@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /**
     * Superseded by [sender]. Retained only so rules saved by earlier versions migrate
     * instead of silently widening to "any sender"; see SettingsStore.
     */
    val senderPattern: String? = null,
    val sender: SenderMatch = SenderMatch(),
    val bodyPattern: String? = null,
    val enabled: Boolean = true,
    /** IDs of the forwarder instances this rule fires. */
    val forwarderIds: List<String> = emptyList(),
)

@Serializable
enum class HttpMethod { GET, POST, PUT }

@Serializable
data class HttpHeader(val name: String, val value: String)

/**
 * A configured forwarder instance. Rules reference these by [id], not by type,
 * so you can have several webhooks pointing at different URLs.
 */
@Serializable
sealed interface ForwarderConfig {
    val id: String
    val name: String

    /** Global on/off switch. Disabled forwarders are skipped even if a rule references them. */
    val enabled: Boolean

    /**
     * Plain SMS relay through the device's own carrier connection.
     * NOTE: plain SMS only. Android exposes no public API for sending RCS from a
     * third-party app (RCS lives inside Google Messages / the carrier stack), so
     * "send as RCS" is not achievable here. Plain SMS reaches any handset, including iPhone.
     */
    @Serializable
    @SerialName("sms_relay")
    data class SmsRelay(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "SMS relay",
        override val enabled: Boolean = true,
        val destinationNumber: String = "",
        val template: String = DEFAULT_TEMPLATE,
    ) : ForwarderConfig {
        companion object {
            /**
             * `{sender_label}` falls back to the bare number when there's no contact
             * name, so this reads "[+1806… (Mum)] hi" with contacts access and
             * "[+1806…] hi" without — safe as a default either way.
             */
            const val DEFAULT_TEMPLATE = "[{sender_label}] {body}"
        }
    }

    @Serializable
    @SerialName("http")
    data class Http(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Webhook",
        override val enabled: Boolean = false,
        val url: String = "",
        val method: HttpMethod = HttpMethod.POST,
        val headers: List<HttpHeader> = emptyList(),
        val contentType: String = "application/json",
        val bodyTemplate: String = DEFAULT_BODY,
    ) : ForwarderConfig {
        companion object {
            const val DEFAULT_BODY =
                """{"sender":"{sender}","body":"{body}","timestamp":"{timestamp}","rule":"{rule_name}"}"""
        }
    }

    @Serializable
    @SerialName("email")
    data class Email(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "Email",
        override val enabled: Boolean = false,
        val host: String = "",
        val port: Int = 587,
        val username: String = "",
        val password: String = "",
        /** STARTTLS on a plaintext port (typically 587). */
        val useStartTls: Boolean = true,
        /** Implicit TLS from the first byte (typically 465). Mutually exclusive with [useStartTls]. */
        val useSsl: Boolean = false,
        val from: String = "",
        val to: String = "",
        val subjectTemplate: String = DEFAULT_SUBJECT,
        val bodyTemplate: String = DEFAULT_BODY,
    ) : ForwarderConfig {
        companion object {
            const val DEFAULT_SUBJECT = "SMS from {sender}"
            const val DEFAULT_BODY = "{body}\n\n--\nFrom: {sender}\nAt: {timestamp}\nRule: {rule_name}"
        }
    }
}

/**
 * Launcher icon variants. Each is an `<activity-alias>` in the manifest, swapped with
 * PackageManager; see [com.personal.smsforwarder.data.AppIconManager] for why that
 * matters more than it looks like it should.
 */
@Serializable
enum class AppIcon(val label: String) {
    @SerialName("blue")
    Blue("Blue"),

    @SerialName("graphite")
    Graphite("Graphite"),

    @SerialName("light")
    Light("Light"),
}

/**
 * Device-local security settings.
 *
 * Deliberately **not** part of a config export: a backup carrying "app lock off" would
 * silently disarm the lock on whatever device it was restored onto, which is the one
 * direction a security setting must never travel.
 */
@Serializable
data class Security(
    val appLockEnabled: Boolean = false,
    /**
     * Blocks screenshots and the recent-apps preview **on the History tab only**.
     *
     * History is the one screen that holds message bodies, which means the OTP codes
     * themselves; a screenshot of it lands in the gallery and, for most people, in a cloud
     * photo backup — precisely what disabling Android's own backup was for. The rest of
     * the app is configuration, so it stays capturable and people can share a rule or a
     * bug report.
     *
     * Independent of [appLockEnabled] on purpose: which screen exposes what does not
     * change based on whether a lock is armed.
     */
    val hideHistoryFromScreenshots: Boolean = true,
    /**
     * How long the app may sit in the background before it re-locks.
     *
     * Not zero by default: the system permission dialog, the contact picker and the file
     * picker all background the activity, so an instant re-lock means authenticating
     * again in the middle of a task you started from inside the app.
     */
    val graceSeconds: Int = 30,
) {
    companion object {
        val GRACE_CHOICES = listOf(0, 15, 30, 60, 300)

        fun graceLabel(seconds: Int): String = when {
            seconds <= 0 -> "Immediately"
            seconds < 60 -> "After ${seconds}s away"
            seconds == 60 -> "After 1 minute away"
            else -> "After ${seconds / 60} minutes away"
        }
    }
}

/**
 * Theme settings. [accentRgb] is a plain 0xRRGGBB value so the picker can treat it as
 * three channels; alpha is always opaque.
 */
@Serializable
data class Appearance(
    /** Material You. Ignored below Android 12, which has no dynamic palette. */
    val useDynamicColor: Boolean = true,
    val accentRgb: Int = DEFAULT_ACCENT,
    val icon: AppIcon = AppIcon.Blue,
) {
    companion object {
        const val DEFAULT_ACCENT = 0x6750A4

        /** Offered as one-tap choices next to the RGB sliders. */
        val PRESETS = listOf(
            0x6750A4, // purple (default)
            0x1F6FEB, // blue
            0x1B7F3B, // green
            0xB3261E, // red
            0xB26A00, // amber
            0x00696E, // teal
            0x8E4585, // magenta
            0x4A4458, // slate
        )
    }
}

@Serializable
enum class AttemptStatus { PENDING, RETRYING, SUCCESS, FAILED }

/** One (rule -> forwarder) delivery attempt for one message. */
@Serializable
data class ForwardAttempt(
    /** Attempts are keyed by rule *ID*, not name — rule names are not unique. */
    val ruleId: String = "",
    val ruleName: String,
    val forwarderId: String,
    val forwarderName: String,
    val status: AttemptStatus = AttemptStatus.PENDING,
    val detail: String = "",
    val updatedAtMillis: Long = 0L,
)

@Serializable
data class HistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val body: String,
    val timestampMillis: Long,
    val matchedRuleNames: List<String> = emptyList(),
    val attempts: List<ForwardAttempt> = emptyList(),
    /** True when the entry came from the in-app simulator rather than the radio. */
    val simulated: Boolean = false,
    /** Matched, but nothing was delivered because forwarding was paused. */
    val forwardingPaused: Boolean = false,
)
