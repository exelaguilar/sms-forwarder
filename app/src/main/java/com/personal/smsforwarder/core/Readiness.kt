package com.personal.smsforwarder.core

import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.Rule

/**
 * "Is this app actually going to do anything?", answered without touching Android.
 *
 * The failure this guards against is the quiet one: a forwarder switched on, a rule
 * enabled, and nothing arriving — because a permission was declined at install time and
 * the only evidence was a screen three taps into Settings. Every check here becomes a
 * banner you cannot miss, with the fix one tap away.
 *
 * Kept as pure functions over plain data so the wording and the logic are unit-tested;
 * the Android side only supplies [SmsPermissions] and renders the result.
 */
object Readiness {

    /**
     * Everything wrong right now, most severe first. An empty list means the app is
     * genuinely ready, which is worth being able to state positively.
     */
    fun issues(
        permissions: SmsPermissions,
        forwarders: List<ForwarderConfig>,
        rules: List<Rule>,
    ): List<Issue> {
        val out = mutableListOf<Issue>()

        if (!permissions.canReceive) {
            out += Issue(
                severity = Severity.Blocking,
                title = "Nothing is being forwarded",
                detail = "Receive SMS and Read SMS haven't been granted, so incoming " +
                    "messages never reach the app. Rules and forwarders below are saved, " +
                    "but none of them can run.",
                action = Action.GrantPermissions,
            )
        }

        // A forwarder that cannot possibly work, reported per instance so the fix lands
        // on the right card rather than as a general grumble.
        forwarders.filter { it.enabled }.forEach { config ->
            val problem = problem(config, permissions) ?: return@forEach
            out += Issue(
                severity = if (problem.blocksEnable) Severity.Blocking else Severity.Warning,
                title = "${config.name} can't send",
                detail = problem.message,
                action = if (problem.blocksEnable) Action.ConfigureForwarder
                else Action.GrantPermissions,
                forwarderId = config.id,
            )
        }

        val enabledRules = rules.filter { it.enabled }
        if (enabledRules.isEmpty()) {
            out += Issue(
                severity = Severity.Warning,
                title = "No rules are enabled",
                detail = "Nothing will match, so nothing will be forwarded.",
                action = Action.OpenRules,
            )
        } else {
            val referenced = enabledRules.flatMap { it.forwarderIds }.toSet()
            val willFire = forwarders.any { it.enabled && it.id in referenced }
            if (!willFire) {
                out += Issue(
                    severity = Severity.Warning,
                    title = "No enabled rule points at an enabled forwarder",
                    detail = "Messages will match and be logged, but there is nowhere " +
                        "for them to go.",
                    action = Action.OpenRules,
                )
            }
        }

        return out.sortedBy { it.severity.ordinal }
    }

    /**
     * What's wrong with one forwarder, or null if it's fine.
     *
     * [Problem.blocksEnable] is the line between the two kinds of wrong. Missing
     * configuration is certain failure and the user can fix it right here, so the toggle
     * refuses. A missing permission is fixed elsewhere and may be granted later, so the
     * toggle allows it and the card says it won't run — switching someone's setup off
     * behind their back is worse than letting them arm it early.
     */
    fun problem(config: ForwarderConfig, permissions: SmsPermissions): Problem? = when (config) {
        is ForwarderConfig.SmsRelay -> when {
            config.destinationNumber.isBlank() ->
                Problem("No destination number set. Edit this forwarder and add one.", true)
            !permissions.sendSms ->
                Problem("Send SMS isn't granted, so this won't run until you grant it.", false)
            else -> null
        }

        is ForwarderConfig.Http -> when {
            config.url.isBlank() -> Problem("No URL set. Edit this forwarder and add one.", true)
            else -> null
        }

        is ForwarderConfig.Email -> when {
            config.host.isBlank() -> Problem("No SMTP host set.", true)
            config.to.isBlank() -> Problem("No recipient address set.", true)
            config.from.isBlank() -> Problem("No sender address set.", true)
            else -> null
        }
    }

    data class Problem(val message: String, val blocksEnable: Boolean)
}

/**
 * The three runtime SMS permissions as plain booleans, so [Readiness] stays free of
 * Android imports and testable on the JVM.
 */
data class SmsPermissions(
    val receiveSms: Boolean = false,
    val readSms: Boolean = false,
    val sendSms: Boolean = false,
) {
    /** Both are needed: the broadcast is delivered by one and readable by the other. */
    val canReceive: Boolean get() = receiveSms && readSms

    val allGranted: Boolean get() = receiveSms && readSms && sendSms

    companion object {
        val NONE = SmsPermissions()
        val ALL = SmsPermissions(receiveSms = true, readSms = true, sendSms = true)
    }
}

enum class Severity { Blocking, Warning }

/** What the banner offers to do about an issue. */
enum class Action { GrantPermissions, ConfigureForwarder, OpenRules }

data class Issue(
    val severity: Severity,
    val title: String,
    val detail: String,
    val action: Action?,
    /** Set when the issue belongs to one forwarder, so the fix can open its editor. */
    val forwarderId: String? = null,
)
