package com.app.newspaperss.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.app.newspaperss.container
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub for a newer build every few hours, so builds arrive overnight without the app being
 * opened. [com.app.newspaperss.DebugUpdates]' own three-hour limit still applies.
 */
class LiveCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A newer build reaches LiveUpdates.newerBuild, which starts the download.
        applicationContext.container.debugUpdates.checkIfDue()
        return Result.success()
    }

    companion object {
        fun schedule(work: WorkManager) {
            val request = PeriodicWorkRequestBuilder<LiveCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(LiveUpdates.CHECK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Downloads the newest build on an unmetered network: it's about 50 MB. */
class LiveDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updates = LiveUpdates.of(applicationContext) ?: return Result.success()
        return when (updates.download(inputData.getInt(BUILD, 0), finalAttempt = runAttemptCount >= ATTEMPTS - 1)) {
            LiveUpdates.Outcome.Retry -> Result.retry()
            LiveUpdates.Outcome.Failed -> Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private const val BUILD = "build"
        /** With the backoff, about a quarter of an hour of a bad connection before giving up on the build. */
        const val ATTEMPTS = 5

        fun enqueue(work: WorkManager, build: Int) {
            val request = OneTimeWorkRequestBuilder<LiveDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
                .setInputData(workDataOf(BUILD to build))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .addTag(LiveUpdates.BUILD_TAG + build)
                .build()
            work.enqueueUniqueWork(LiveUpdates.DOWNLOAD, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Installs the downloaded build once nothing is running that installing would kill. */
class LiveInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val updates = LiveUpdates.of(applicationContext) ?: return Result.success()
        return when (updates.install(userAsked = false)) {
            is LiveUpdates.Outcome.Busy, LiveUpdates.Outcome.Retry -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        /**
         * Kept if one is waiting: a timed edition overnight pushes the install back by
         * [LiveUpdates.RETRY_MINUTES] at a time, more each try, rather than being cut off.
         */
        fun enqueue(work: WorkManager, delayMinutes: Long = 0) {
            val request = OneTimeWorkRequestBuilder<LiveInstallWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, LiveUpdates.RETRY_MINUTES, TimeUnit.MINUTES)
                .build()
            work.enqueueUniqueWork(LiveUpdates.INSTALL, if (delayMinutes > 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
