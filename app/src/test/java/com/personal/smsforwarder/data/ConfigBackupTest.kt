package com.personal.smsforwarder.data

import com.personal.smsforwarder.model.Appearance
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HttpHeader
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.SenderCriterion
import com.personal.smsforwarder.model.SenderMatch
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The security-relevant half of backup is what *isn't* written. These assert against the
 * serialised text, not the object graph, because the risk is a plaintext file on disk.
 */
class ConfigBackupTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val email = ForwarderConfig.Email(
        id = "f-mail", name = "Mail", host = "smtp.example.com",
        username = "me@example.com", password = "hunter2-super-secret",
        from = "me@example.com", to = "you@example.com",
    )
    private val webhook = ForwarderConfig.Http(
        id = "f-http", name = "Hook", url = "https://example.com/hook",
        headers = listOf(
            HttpHeader("Authorization", "Bearer sk-live-do-not-leak"),
            HttpHeader("X-Trace", "abc"),
        ),
    )
    private val relay = ForwarderConfig.SmsRelay(id = "f-sms", destinationNumber = "+18065551234")

    private fun backup(includeSecrets: Boolean) = ConfigBackup(
        exportedAt = "2026-08-23T00:00:00Z",
        containsSecrets = includeSecrets,
        rules = listOf(
            Rule(
                id = "r", name = "OTP",
                sender = SenderMatch(include = listOf(SenderCriterion.Number("+18065551234"))),
            )
        ),
        // Calls the production redaction, not a copy of it — a duplicated version here
        // would still pass if the real one were broken.
        forwarders = listOf(email, webhook, relay).map {
            if (includeSecrets) it else it.withoutSecrets()
        },
        knownNumbers = listOf("+18065551234"),
        appearance = Appearance(),
    )

    @Test
    fun `withoutSecrets blanks exactly the credential fields`() {
        val strippedEmail = email.withoutSecrets() as ForwarderConfig.Email
        assertEquals("", strippedEmail.password)
        // Everything needed to identify the account survives.
        assertEquals("smtp.example.com", strippedEmail.host)
        assertEquals("me@example.com", strippedEmail.username)

        val strippedHttp = webhook.withoutSecrets() as ForwarderConfig.Http
        assertTrue(strippedHttp.headers.all { it.value.isEmpty() })
        assertEquals(listOf("Authorization", "X-Trace"), strippedHttp.headers.map { it.name })
        assertEquals("https://example.com/hook", strippedHttp.url)

        // A relay has no secret to strip and must come through untouched.
        assertEquals(relay, relay.withoutSecrets())
    }

    @Test
    fun `redacted export contains no credential material`() {
        val text = json.encodeToString(ConfigBackup.serializer(), backup(includeSecrets = false))

        assertTrue("SMTP password leaked", !text.contains("hunter2"))
        assertTrue("bearer token leaked", !text.contains("sk-live-do-not-leak"))
        // Structure survives so only the secret needs re-entering.
        assertTrue(text.contains("smtp.example.com"))
        assertTrue(text.contains("Authorization"))
        assertTrue(text.contains("https://example.com/hook"))
    }

    @Test
    fun `explicit secret export keeps them, and says so`() {
        val full = backup(includeSecrets = true)
        val text = json.encodeToString(ConfigBackup.serializer(), full)
        assertTrue(text.contains("hunter2-super-secret"))
        assertTrue(full.containsSecrets)
    }

    @Test
    fun `backup round-trips through json`() {
        val original = backup(includeSecrets = false)
        val text = json.encodeToString(ConfigBackup.serializer(), original)
        val parsed = ConfigBackupIo.parse(text).getOrThrow()

        assertEquals(original.rules, parsed.rules)
        assertEquals(original.forwarders, parsed.forwarders)
        assertEquals(original.knownNumbers, parsed.knownNumbers)
    }

    @Test
    fun `a newer format version is refused rather than half-applied`() {
        val text = json.encodeToString(
            ConfigBackup.serializer(),
            backup(includeSecrets = false).copy(version = ConfigBackup.CURRENT_VERSION + 1),
        )
        val result = ConfigBackupIo.parse(text)
        assertTrue(result.isFailure)
    }

    @Test
    fun `rubbish input fails cleanly`() {
        assertTrue(ConfigBackupIo.parse("not json at all").isFailure)
        assertTrue(ConfigBackupIo.parse("{}").isFailure)
    }

    @Test
    fun `summary reports what the user is about to overwrite`() {
        val summary = ConfigBackupIo.summarise(backup(includeSecrets = false))
        assertEquals(1, summary.rules)
        assertEquals(3, summary.forwarders)
        assertTrue(!summary.containsSecrets)
    }
}
