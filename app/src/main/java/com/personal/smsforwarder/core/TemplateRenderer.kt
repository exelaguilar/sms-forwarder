package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.ForwardRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Substitutes `{sender}`, `{sender_label}`, `{contact}`, `{body}`, `{timestamp}` and
 * `{rule_name}` in a template.
 *
 * Only these four placeholders exist; anything else is left untouched. Substitution is
 * single-pass, so a value that itself contains `{body}` is never re-expanded.
 */
object TemplateRenderer {

    enum class Escaping {
        /** Insert values verbatim (SMS text, plain-text email, form bodies). */
        NONE,

        /** Escape values for use inside a JSON string literal. */
        JSON,
    }

    // Both braces must be escaped. The JVM's java.util.regex tolerates a bare `}`, but
    // Android's ICU engine rejects it with a PatternSyntaxException — which, in an object
    // initialiser, surfaces as an ExceptionInInitializerError on device while JVM unit
    // tests pass. See TemplateRendererAndroidTest, which runs this on a real engine.
    private val PLACEHOLDER =
        Regex("""\{(sender|sender_label|contact|body|timestamp|rule_name)\}""")

    fun render(template: String, request: ForwardRequest, escaping: Escaping = Escaping.NONE): String =
        PLACEHOLDER.replace(template) { match ->
            val raw = when (match.groupValues[1]) {
                "sender" -> request.sms.sender
                // Degrades to the bare number when there's no contact name, so it is safe
                // to use by default whether or not contacts access was ever granted.
                "sender_label" -> request.contactName
                    ?.let { "${request.sms.sender} ($it)" }
                    ?: request.sms.sender
                "contact" -> request.contactName.orEmpty()
                "body" -> request.sms.body
                "timestamp" -> formatTimestamp(request.sms.timestampMillis)
                "rule_name" -> request.ruleName
                else -> match.value
            }
            // The lambda overload of Regex.replace inserts this value literally — no
            // Regex.escapeReplacement here, or `$` and `\` in a message body get mangled.
            when (escaping) {
                Escaping.NONE -> raw
                Escaping.JSON -> jsonEscape(raw)
            }
        }

    /** Shared by HttpForwarder and deliveryKey so the two can't drift apart. */
    fun escapingFor(contentType: String): Escaping =
        if (contentType.contains("json", ignoreCase = true)) Escaping.JSON else Escaping.NONE

    /** Local-time ISO-8601 with offset, e.g. `2026-08-23T14:05:09-07:00`. */
    fun formatTimestamp(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }
}
