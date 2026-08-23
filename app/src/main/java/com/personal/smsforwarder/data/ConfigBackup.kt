package com.personal.smsforwarder.data

import com.personal.smsforwarder.model.Appearance
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * A portable copy of the configuration.
 *
 * Deliberately **excludes history** — that log holds the OTP codes themselves, and a
 * plaintext file of them in Downloads or a cloud drive is exactly what the encrypted
 * store exists to prevent.
 *
 * Credentials are excluded by default for the same reason; [containsSecrets] records
 * which kind of file this is so the import side can tell you what you're getting.
 */
@Serializable
data class ConfigBackup(
    val version: Int = CURRENT_VERSION,
    val exportedAt: String,
    val containsSecrets: Boolean,
    val rules: List<Rule>,
    val forwarders: List<ForwarderConfig>,
    val knownNumbers: List<String>,
    val appearance: Appearance,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** What an import would change, shown for confirmation before anything is overwritten. */
data class ImportSummary(
    val rules: Int,
    val forwarders: Int,
    val containsSecrets: Boolean,
    val exportedAt: String,
)

object ConfigBackupIo {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun export(store: SettingsStore, includeSecrets: Boolean): String {
        val backup = ConfigBackup(
            exportedAt = Instant.now().toString(),
            containsSecrets = includeSecrets,
            rules = store.rules.value,
            forwarders = store.forwarders.value.map {
                if (includeSecrets) it else it.withoutSecrets()
            },
            knownNumbers = store.knownNumbers.value,
            appearance = store.appearance.value,
        )
        return json.encodeToString(backup)
    }

    /** Parses and validates without applying, so the UI can confirm first. */
    fun parse(text: String): Result<ConfigBackup> = runCatching {
        val backup = json.decodeFromString<ConfigBackup>(text)
        require(backup.version <= ConfigBackup.CURRENT_VERSION) {
            "This file was written by a newer version of the app (format ${backup.version})"
        }
        backup
    }

    fun summarise(backup: ConfigBackup) = ImportSummary(
        rules = backup.rules.size,
        forwarders = backup.forwarders.size,
        containsSecrets = backup.containsSecrets,
        exportedAt = backup.exportedAt,
    )

    fun apply(store: SettingsStore, backup: ConfigBackup) {
        store.replaceConfig(
            rules = backup.rules,
            forwarders = backup.forwarders,
            knownNumbers = backup.knownNumbers,
            appearance = backup.appearance,
        )
    }
}

/**
 * Blanks anything that would be a credential in a plaintext file: the SMTP password, and
 * HTTP header *values* (which is where bearer tokens and API keys live). Header names are
 * kept so the shape of the request survives and only the secret needs re-entering.
 */
internal fun ForwarderConfig.withoutSecrets(): ForwarderConfig = when (this) {
    is ForwarderConfig.Email -> copy(password = "")
    is ForwarderConfig.Http -> copy(headers = headers.map { it.copy(value = "") })
    is ForwarderConfig.SmsRelay -> this
}
