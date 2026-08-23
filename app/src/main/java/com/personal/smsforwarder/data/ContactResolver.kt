package com.personal.smsforwarder.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.personal.smsforwarder.core.ContactLookup
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves an incoming sender to a contact name via `PhoneLookup`.
 *
 * The permission is checked on every call rather than cached, so revoking it in system
 * settings takes effect immediately. Without it — the default, since the app never
 * requests contacts unless you ask for names — every lookup returns null and templates
 * fall back to the bare number.
 *
 * Alphanumeric senders ("CHASE", "VERIFY") and short codes simply won't match a contact,
 * which is expected rather than an error.
 */
class ContactResolver(private val context: Context) : ContactLookup {

    // Senders repeat constantly; an empty string caches a known miss.
    private val cache = ConcurrentHashMap<String, String>()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    override fun displayName(sender: String): String? {
        if (sender.isBlank() || !hasPermission()) return null
        cache[sender]?.let { return it.ifEmpty { null } }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(sender)
        )
        val name = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()

        cache[sender] = name.orEmpty()
        return name
    }

    /** Called after the permission is granted so earlier misses aren't cached forever. */
    fun clearCache() = cache.clear()
}
