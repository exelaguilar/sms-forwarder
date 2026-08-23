package com.personal.smsforwarder.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for JVM-vs-Android regex divergence.
 *
 * Android uses ICU for `java.util.regex`, and it is stricter than the JVM's engine: an
 * unescaped `}` compiles fine on the JVM but throws `PatternSyntaxException` here. When
 * that happens inside an object initialiser it surfaces as `ExceptionInInitializerError`
 * at the first touch of the class — invisible to JVM unit tests, fatal on a handset.
 *
 * Every regex the app compiles at class-init time must therefore be exercised on device.
 */
@RunWith(AndroidJUnit4::class)
class TemplateRendererAndroidTest {

    private val request = ForwardRequest(
        sms = IncomingSms("+15551234567", "Your code is 123456", 1_700_000_000_000L),
        ruleName = "OTP",
    )

    @Test
    fun placeholderPatternCompilesOnAndroid() {
        // Fails with ExceptionInInitializerError if TemplateRenderer's regex is invalid
        // under ICU, which is exactly the bug this test exists to catch.
        assertEquals(
            "+15551234567|Your code is 123456|OTP",
            TemplateRenderer.render("{sender}|{body}|{rule_name}", request),
        )
    }

    @Test
    fun defaultTemplatesRenderOnAndroid() {
        assertEquals(
            "[+15551234567] Your code is 123456",
            TemplateRenderer.render(ForwarderConfig.SmsRelay.DEFAULT_TEMPLATE, request),
        )
        assertTrue(
            TemplateRenderer.render(ForwarderConfig.Email.DEFAULT_SUBJECT, request)
                .startsWith("SMS from")
        )
        val json = TemplateRenderer.render(
            ForwarderConfig.Http.DEFAULT_BODY, request, TemplateRenderer.Escaping.JSON
        )
        assertTrue(json, json.contains("\"rule\":\"OTP\""))
    }

    @Test
    fun defaultOtpRulePatternCompilesOnAndroid() {
        val rule = Rule(name = "OTP", bodyPattern = Defaults.OTP_BODY_PATTERN)
        assertTrue(RuleMatcher.matches(rule, IncomingSms("VERIFY", "Your code is 4821", 0L)))
        assertTrue(!RuleMatcher.matches(rule, IncomingSms("MUM", "call me back", 0L)))
    }
}
