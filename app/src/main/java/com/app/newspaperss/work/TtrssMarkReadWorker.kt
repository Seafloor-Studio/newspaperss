package com.app.newspaperss.work

import com.app.newspaperss.container
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration

/**
 * Marks an edition's tt-rss articles read on the server once it's delivered, or unread once it's
 * marked as not sent, retrying a few times.
 */
class TtrssMarkReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val editionId = inputData.getLong(EDITION_ID, -1)
        val done = applicationContext.container.ttrss.syncRead(editionId)
        return when {
            done -> Result.success()
            runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
            // The source shows the error; tt-rss is left as it was, which loses nothing.
            else -> Result.failure()
        }
    }

    companion object {
        const val EDITION_ID = "editionId"
        const val MAX_ATTEMPTS = 4

        /**
         * How long a change waits before reaching tt-rss. A send to the wrong app, marked as not sent
         * straight away, replaces its marking before it runs, so tt-rss never flips the articles
         * read and then unread again.
         */
        val DELAY: Duration = Duration.ofMinutes(2)

        fun enqueue(context: Context, editionId: Long) {
            val request = OneTimeWorkRequestBuilder<TtrssMarkReadWorker>()
                .setConstraints(CONNECTED_NETWORK)
                .setInitialDelay(DELAY)
                .setInputData(workDataOf(EDITION_ID to editionId))
                .build()
            // Replaced, not kept: the work reads the edition's state when it runs, so only the
            // newest request matters, and one still retrying a delivery mustn't block undoing it.
            WorkManager.getInstance(context).enqueueUniqueWork("ttrss-mark-read-$editionId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
