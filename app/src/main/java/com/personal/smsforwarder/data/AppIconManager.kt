package com.personal.smsforwarder.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.personal.smsforwarder.model.AppIcon

/**
 * Swaps the launcher icon by enabling one `<activity-alias>` and disabling the rest.
 *
 * Two details are load-bearing:
 *
 * 1. The new alias is enabled *before* the old ones are disabled. Doing it the other way
 *    round leaves a window with no enabled launcher component, and a launcher that
 *    refreshes inside that window drops the app from the home screen entirely.
 * 2. [PackageManager.DONT_KILL_APP] - without it the process is killed mid-tap, which
 *    looks exactly like a crash to the person who just changed a setting.
 *
 * Home-screen shortcuts pinned to the old alias do not follow the change; that is an
 * Android limitation, and the Appearance screen says so rather than pretending otherwise.
 */
class AppIconManager(private val context: Context) {

    /** No-op when [icon] is already the enabled one, so this is safe to call on launch. */
    fun apply(icon: AppIcon) {
        if (current() == icon) return
        val pm = context.packageManager
        runCatching {
            pm.setComponentEnabledSetting(
                component(icon),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            AppIcon.entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    component(it),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    /**
     * Every variant the system currently has enabled.
     *
     * Exposed as a list rather than a single value so the invariant that keeps the app
     * launchable - exactly one enabled alias, never zero - can actually be asserted.
     */
    fun enabled(): List<AppIcon> = AppIcon.entries.filter { isEnabled(it) }

    /** Which variant is in use. Falls back to the default rather than reporting none. */
    fun current(): AppIcon = enabled().firstOrNull() ?: AppIcon.Blue

    /**
     * `COMPONENT_ENABLED_STATE_DEFAULT` means "whatever the manifest says", which is only
     * true of [AppIcon.Blue] - so an untouched install reports Blue without anything
     * having been written anywhere.
     */
    private fun isEnabled(icon: AppIcon): Boolean {
        val state = runCatching { context.packageManager.getComponentEnabledSetting(component(icon)) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == AppIcon.Blue)
    }

    private fun component(icon: AppIcon) = ComponentName(context, alias(icon))

    private fun alias(icon: AppIcon) = "${context.packageName}.ui.Launcher${icon.name}"
}
