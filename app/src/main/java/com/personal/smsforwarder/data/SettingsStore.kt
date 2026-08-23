package com.personal.smsforwarder.data

import android.content.Context
import com.personal.smsforwarder.core.Defaults
import com.personal.smsforwarder.core.HistoryRecorder
import com.personal.smsforwarder.model.Appearance
import com.personal.smsforwarder.model.AttemptStatus
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HistoryEntry
import com.personal.smsforwarder.model.Rule
import com.personal.smsforwarder.model.Security
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * All persistent state, in one file-backed store.
 *
 * Everything lives in a single EncryptedSharedPreferences file because all of it is
 * sensitive: SMTP credentials, webhook URLs/headers, and the history log (which contains
 * the OTP codes themselves). Values are JSON blobs; there is no database.
 */
class SettingsStore(context: Context) : HistoryRecorder {

    private val prefs = JsonPrefs(context)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _forwarders = MutableStateFlow(
        decode<List<ForwarderConfig>>(KEY_FORWARDERS) ?: Defaults.forwarders()
    )
    val forwarders: StateFlow<List<ForwarderConfig>> = _forwarders.asStateFlow()

    private val _rules = MutableStateFlow(decode<List<Rule>>(KEY_RULES) ?: Defaults.rules())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()

    private val _history = MutableStateFlow(decode<List<HistoryEntry>>(KEY_HISTORY) ?: emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _appearance = MutableStateFlow(decode<Appearance>(KEY_APPEARANCE) ?: Appearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    /** Destination numbers already used, offered when configuring another relay. */
    private val _knownNumbers = MutableStateFlow(decode<List<String>>(KEY_NUMBERS) ?: emptyList())
    val knownNumbers: StateFlow<List<String>> = _knownNumbers.asStateFlow()

    /**
     * Master switch. When off, messages are still matched and logged so you can see what
     * you missed, but nothing is delivered — useful while travelling, lending the phone,
     * or iterating on rules without paying for SMS.
     */
    private val _forwardingEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val forwardingEnabled: StateFlow<Boolean> = _forwardingEnabled.asStateFlow()

    fun setForwardingEnabled(enabled: Boolean) {
        _forwardingEnabled.value = enabled
        prefs.putBoolean(KEY_ENABLED, enabled)
    }

    /** Biometric lock. Never carried in a config export - see [Security]. */
    private val _security = MutableStateFlow(decode<Security>(KEY_SECURITY) ?: Security())
    val security: StateFlow<Security> = _security.asStateFlow()

    fun updateSecurity(security: Security) {
        _security.value = security.also { persist(KEY_SECURITY, it) }
    }

    /**
     * Whether the first-run guide has been dismissed. A flow rather than a one-off read
     * so re-running it from Settings is the same code path as showing it on install.
     */
    private val _onboarded = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDED, false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    fun setOnboarded(value: Boolean) {
        _onboarded.value = value
        prefs.putBoolean(KEY_ONBOARDED, value)
    }

    init {
        // Persist the seed data on first run so IDs stay stable across restarts.
        if (!prefs.contains(KEY_FORWARDERS)) persist(KEY_FORWARDERS, _forwarders.value)
        if (!prefs.contains(KEY_RULES)) persist(KEY_RULES, _rules.value)

        val migrated = Migrations.migrateRules(_rules.value)
        if (migrated != _rules.value) {
            _rules.value = migrated
            persist(KEY_RULES, migrated)
        }

        val merged = Migrations.seedKnownNumbers(_knownNumbers.value, _forwarders.value)
        if (merged != _knownNumbers.value) {
            _knownNumbers.value = merged
            persist(KEY_NUMBERS, merged)
        }
    }

    // ---- rules -------------------------------------------------------------

    fun upsertRule(rule: Rule) = updateRules { list ->
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list.toMutableList().also { it[idx] = rule } else list + rule
    }

    fun deleteRule(id: String) = updateRules { list -> list.filterNot { it.id == id } }

    /**
     * Reorders a rule. List order is evaluation order, which decides which rule is
     * credited when duplicate deliveries are collapsed.
     */
    fun moveRule(id: String, delta: Int) = updateRules { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) {
            list
        } else {
            list.toMutableList().also {
                val moved = it.removeAt(from)
                it.add(to, moved)
            }
        }
    }

    private fun updateRules(transform: (List<Rule>) -> List<Rule>) {
        _rules.value = transform(_rules.value).also { persist(KEY_RULES, it) }
    }

    // ---- forwarders --------------------------------------------------------

    fun upsertForwarder(config: ForwarderConfig) {
        updateForwarders { list ->
            val idx = list.indexOfFirst { it.id == config.id }
            if (idx >= 0) list.toMutableList().also { it[idx] = config } else list + config
        }
        // Remember destinations so the next relay can be picked from a list instead of
        // retyped. Kept even if the forwarder is later deleted.
        if (config is ForwarderConfig.SmsRelay) rememberNumber(config.destinationNumber)
    }

    fun rememberNumber(number: String) {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || trimmed in _knownNumbers.value) return
        _knownNumbers.value = (_knownNumbers.value + trimmed).also { persist(KEY_NUMBERS, it) }
    }

    fun forgetNumber(number: String) {
        _knownNumbers.value = _knownNumbers.value.filterNot { it == number }
            .also { persist(KEY_NUMBERS, it) }
    }

    /**
     * Wholesale replacement, used by config import. History is untouched: it belongs to
     * this device and is never carried in a backup.
     */
    fun replaceConfig(
        rules: List<Rule>,
        forwarders: List<ForwarderConfig>,
        knownNumbers: List<String>,
        appearance: Appearance,
    ) {
        _rules.value = rules.also { persist(KEY_RULES, it) }
        _forwarders.value = forwarders.also { persist(KEY_FORWARDERS, it) }
        _knownNumbers.value = knownNumbers.distinct().also { persist(KEY_NUMBERS, it) }
        updateAppearance(appearance)
    }

    // ---- appearance --------------------------------------------------------

    fun updateAppearance(appearance: Appearance) {
        _appearance.value = appearance.also { persist(KEY_APPEARANCE, it) }
    }

    /** Removing a forwarder also unlinks it from every rule. */
    fun deleteForwarder(id: String) {
        updateForwarders { list -> list.filterNot { it.id == id } }
        updateRules { list -> list.map { it.copy(forwarderIds = it.forwarderIds - id) } }
    }

    private fun updateForwarders(transform: (List<ForwarderConfig>) -> List<ForwarderConfig>) {
        _forwarders.value = transform(_forwarders.value).also { persist(KEY_FORWARDERS, it) }
    }

    fun forwarder(id: String): ForwarderConfig? = _forwarders.value.firstOrNull { it.id == id }

    // ---- history -----------------------------------------------------------

    override fun record(entry: HistoryEntry) = updateHistory { list ->
        (listOf(entry) + list).take(MAX_HISTORY)
    }

    /** Called by [com.personal.smsforwarder.work.ForwardWorker] as each attempt resolves. */
    fun updateAttempt(
        entryId: String,
        ruleId: String,
        forwarderId: String,
        status: AttemptStatus,
        detail: String,
        at: Long = System.currentTimeMillis(),
    ) = updateHistory { list ->
        list.map { entry ->
            if (entry.id != entryId) return@map entry
            entry.copy(
                attempts = entry.attempts.map { attempt ->
                    if (attempt.ruleId == ruleId && attempt.forwarderId == forwarderId) {
                        attempt.copy(status = status, detail = detail, updatedAtMillis = at)
                    } else {
                        attempt
                    }
                }
            )
        }
    }

    fun clearHistory() = updateHistory { emptyList() }

    @Synchronized
    private fun updateHistory(transform: (List<HistoryEntry>) -> List<HistoryEntry>) {
        _history.value = transform(_history.value).also { persist(KEY_HISTORY, it) }
    }

    // ---- persistence -------------------------------------------------------

    private inline fun <reified T> decode(key: String): T? {
        val raw = prefs.getString(key) ?: return null
        return runCatching { json.decodeFromString<T>(raw) }.getOrNull()
    }

    private inline fun <reified T> persist(key: String, value: T) {
        prefs.putString(key, json.encodeToString(value))
    }

    private companion object {
        const val KEY_RULES = "rules"
        const val KEY_ENABLED = "forwarding_enabled"
        const val KEY_FORWARDERS = "forwarders"
        const val KEY_HISTORY = "history"
        const val KEY_APPEARANCE = "appearance"
        const val KEY_NUMBERS = "known_numbers"
        const val KEY_SECURITY = "security"
        const val KEY_ONBOARDED = "onboarded"
        const val MAX_HISTORY = 300
    }
}
