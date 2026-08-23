package com.personal.smsforwarder.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.personal.smsforwarder.SmsForwarderApp
import com.personal.smsforwarder.core.ForwardDispatcher
import com.personal.smsforwarder.core.describe
import com.personal.smsforwarder.forwarder.ForwarderConfigException
import com.personal.smsforwarder.model.AttemptStatus
import com.personal.smsforwarder.model.ForwardRequest
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import java.util.concurrent.TimeUnit

/**
 * Hands each (rule -> forwarder) pair to WorkManager, so a flaky network retries with
 * exponential backoff instead of losing the code. The SMS receiver never blocks on I/O.
 */
class WorkManagerDispatcher(context: Context) : ForwardDispatcher {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun dispatch(
        historyEntryId: String,
        sms: IncomingSms,
        rule: Rule,
        config: ForwarderConfig,
    ) {
        // The SMS relay goes over the radio, not the network — don't gate it on connectivity.
        val needsNetwork = config !is ForwarderConfig.SmsRelay

        val request = OneTimeWorkRequestBuilder<ForwardWorker>()
            .setInputData(
                workDataOf(
                    ForwardWorker.KEY_ENTRY_ID to historyEntryId,
                    ForwardWorker.KEY_FORWARDER_ID to config.id,
                    ForwardWorker.KEY_RULE_ID to rule.id,
                    ForwardWorker.KEY_RULE_NAME to rule.name,
                    ForwardWorker.KEY_SENDER to sms.sender,
                    ForwardWorker.KEY_BODY to sms.body,
                    ForwardWorker.KEY_TIMESTAMP to sms.timestampMillis,
                )
            )
            .apply {
                if (needsNetwork) {
                    setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                }
            }
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(ForwardWorker.TAG)
            .build()

        workManager.enqueue(request)
    }
}

/** Runs exactly one forwarder for one message, and writes the outcome into history. */
class ForwardWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SmsForwarderApp).container
        val data: Data = inputData

        val entryId = data.getString(KEY_ENTRY_ID) ?: return Result.failure()
        val forwarderId = data.getString(KEY_FORWARDER_ID) ?: return Result.failure()
        val ruleId = data.getString(KEY_RULE_ID).orEmpty()

        // A forwarder that throws instead of returning Result.failure would otherwise
        // leave this attempt showing PENDING forever, with no clue why.
        return try {
            forward(container, entryId, forwarderId, ruleId, data)
        } catch (t: Throwable) {
            runCatching {
                container.store.updateAttempt(
                    entryId, ruleId, forwarderId, AttemptStatus.FAILED,
                    "forwarder threw — ${t.describe()}",
                )
            }
            Result.failure()
        }
    }

    private suspend fun forward(
        container: com.personal.smsforwarder.AppContainer,
        entryId: String,
        forwarderId: String,
        ruleId: String,
        data: Data,
    ): Result {
        val ruleName = data.getString(KEY_RULE_NAME).orEmpty()
        val config = container.store.forwarder(forwarderId)
        if (config == null) {
            container.store.updateAttempt(
                entryId, ruleId, forwarderId, AttemptStatus.FAILED, "Forwarder was deleted"
            )
            return Result.failure()
        }

        val request = ForwardRequest(
            sms = IncomingSms(
                sender = data.getString(KEY_SENDER).orEmpty(),
                body = data.getString(KEY_BODY).orEmpty(),
                timestampMillis = data.getLong(KEY_TIMESTAMP, System.currentTimeMillis()),
            ),
            ruleName = ruleName,
            // Resolved here rather than at match time: the worker may run minutes later,
            // and the permission can be granted or revoked in between.
            contactName = container.contacts.displayName(data.getString(KEY_SENDER).orEmpty()),
        )

        // Progress lands in the attempt's detail line while it stays PENDING, so History
        // shows "carrier accepted 1/1 part(s) — waiting for a delivery report" instead of
        // an unexplained pause.
        val outcome = container.forwarders.forConfig(config).send(request, config) { progress ->
            runCatching {
                container.store.updateAttempt(
                    entryId, ruleId, forwarderId, AttemptStatus.PENDING, progress
                )
            }
        }

        return outcome.fold(
            onSuccess = { detail ->
                container.store.updateAttempt(entryId, ruleId, forwarderId, AttemptStatus.SUCCESS, detail)
                Result.success()
            },
            onFailure = { error ->
                val message = error.describe()
                val attemptsSoFar = runAttemptCount + 1
                if (error is ForwarderConfigException) {
                    // Retrying cannot fix a misconfigured forwarder.
                    container.store.updateAttempt(
                        entryId, ruleId, forwarderId, AttemptStatus.FAILED,
                        "not retried — $message",
                    )
                    container.notifier.notifyFailure(
                        config.name, ruleName, request.sms.sender, "not retried — $message"
                    )
                    Result.failure()
                } else if (attemptsSoFar < MAX_ATTEMPTS) {
                    container.store.updateAttempt(
                        entryId, ruleId, forwarderId, AttemptStatus.RETRYING,
                        "attempt $attemptsSoFar/$MAX_ATTEMPTS failed: $message",
                    )
                    Result.retry()
                } else {
                    container.store.updateAttempt(
                        entryId, ruleId, forwarderId, AttemptStatus.FAILED,
                        "gave up after $MAX_ATTEMPTS attempts: $message",
                    )
                    container.notifier.notifyFailure(
                        config.name, ruleName, request.sms.sender,
                        "gave up after $MAX_ATTEMPTS attempts: $message",
                    )
                    Result.failure()
                }
            },
        )
    }

    companion object {
        const val TAG = "sms-forward"
        const val KEY_ENTRY_ID = "entry_id"
        const val KEY_FORWARDER_ID = "forwarder_id"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_RULE_NAME = "rule_name"
        const val KEY_SENDER = "sender"
        const val KEY_BODY = "body"
        const val KEY_TIMESTAMP = "timestamp"

        /** Total tries including the first. 5 tries with 30s exponential backoff ≈ 8 minutes. */
        const val MAX_ATTEMPTS = 5
    }
}
