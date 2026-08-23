package com.personal.smsforwarder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The storage primitive: one encrypted preferences file holding opaque strings.
 *
 * Everything lives here because all of it is sensitive — SMTP credentials, webhook
 * headers, and the history log, which contains the OTP codes themselves.
 *
 * Deliberately not generic. Serialisation stays in [SettingsStore], so this class is only
 * responsible for "where the bytes go" and can be reasoned about on its own.
 */
class JsonPrefs(context: Context, fileName: String = DEFAULT_FILE) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun contains(key: String): Boolean = prefs.contains(key)

    fun getString(key: String): String? = prefs.getString(key, null)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    companion object {
        const val DEFAULT_FILE = "sms_forwarder_store"
    }
}
