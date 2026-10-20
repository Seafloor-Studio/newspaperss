package com.app.newspaperss.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.container
import java.util.concurrent.TimeUnit

/** Fills in reading times for saved links that have none (see [com.app.newspaperss.data.ReadingTimes]). */
class ReadingTimeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.readingTimes.measureNext()
        // A page that didn't load waits for a later pass, not a retry: nothing is in a hurry.
        return Result.success()
    }

    companion object {
        internal const val PERIODIC = "reading-times-12h"

        /**
         * Nothing waits on these, so they cost the reader nothing: Wi-Fi only, while charging.
         * KEEP, so starting the app doesn't put the next run back.
         */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()
            val request = PeriodicWorkRequestBuilder<ReadingTimeWorker>(12, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
