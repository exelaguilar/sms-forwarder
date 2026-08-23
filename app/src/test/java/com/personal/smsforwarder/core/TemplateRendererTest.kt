package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.IncomingSms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRendererTest {

    private val request = ForwardRequest(
        sms = IncomingSms(
            sender = "+15551234567",
            body = "Your code is 123456",
            timestampMillis = 1_700_000_000_000L,
        ),
        ruleName = "OTP",
    )

    @Test
    fun `substitutes every placeholder`() {
        val out = TemplateRenderer.render("{sender}|{body}|{rule_name}", request)
        assertEquals("+15551234567|Your code is 123456|OTP", out)
    }

    @Test
    fun `sender_label falls back to the bare number without a contact`() {
        // The no-permission path: must stay usable, not render "null" or empty.
        assertEquals("+15551234567", TemplateRenderer.render("{sender_label}", request))
        assertEquals("", TemplateRenderer.render("{contact}", request))
    }

    @Test
    fun `sender_label includes the contact name when resolved`() {
        val named = request.copy(contactName = "Mum")
        assertEquals("+15551234567 (Mum)", TemplateRenderer.render("{sender_label}", named))
        assertEquals("Mum", TemplateRenderer.render("{contact}", named))
    }

    @Test
    fun `default relay template is safe with and without contacts`() {
        assertEquals(
            "[+15551234567] Your code is 123456",
            TemplateRenderer.render(ForwarderConfig.SmsRelay.DEFAULT_TEMPLATE, request),
        )
        assertEquals(
            "[+15551234567 (Mum)] Your code is 123456",
            TemplateRenderer.render(
                ForwarderConfig.SmsRelay.DEFAULT_TEMPLATE, request.copy(contactName = "Mum")
            ),
        )
    }

    @Test
    fun `unknown placeholders are left alone`() {
        assertEquals("{nope} +15551234567", TemplateRenderer.render("{nope} {sender}", request))
    }

    @Test
    fun `timestamp renders as ISO-8601 with offset`() {
        val out = TemplateRenderer.render("{timestamp}", request)
        // Local zone varies; just assert the shape.
        assertTrue(out, Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}([+-]\d{2}:\d{2}|Z)""").matches(out))
    }

    @Test
    fun `values are not re-expanded`() {
        val nested = request.copy(sms = request.sms.copy(body = "{sender}"))
        assertEquals("{sender}", TemplateRenderer.render("{body}", nested))
    }

    @Test
    fun `dollar signs and backslashes in the body survive substitution`() {
        val tricky = request.copy(sms = request.sms.copy(body = """pay $5 \ now $1"""))
        assertEquals("""pay $5 \ now $1""", TemplateRenderer.render("{body}", tricky))
    }

    @Test
    fun `json escaping produces a valid string literal`() {
        val tricky = request.copy(
            sms = request.sms.copy(body = "line1\nline2 \"quoted\" back\\slash\ttab")
        )
        val out = TemplateRenderer.render(
            ForwarderConfig.Http.DEFAULT_BODY, tricky, TemplateRenderer.Escaping.JSON
        )
        assertTrue(out, out.contains("""line1\nline2 \"quoted\" back\\slash\ttab"""))
        // Round-trips through a strict parser.
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(out)
        assertTrue(parsed.toString().isNotEmpty())
    }

    @Test
    fun `no escaping is applied for plain text bodies`() {
        val tricky = request.copy(sms = request.sms.copy(body = "a \"b\" c"))
        assertEquals("a \"b\" c", TemplateRenderer.render("{body}", tricky))
    }

    @Test
    fun `default email templates render`() {
        assertEquals(
            "SMS from +15551234567",
            TemplateRenderer.render(ForwarderConfig.Email.DEFAULT_SUBJECT, request),
        )
        val body = TemplateRenderer.render(ForwarderConfig.Email.DEFAULT_BODY, request)
        assertTrue(body.startsWith("Your code is 123456"))
        assertTrue(body.contains("Rule: OTP"))
    }
}
