package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import com.app.newspaperss.testutil.TestApp
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Test
    fun anUnexpectedErrorFailsTheWorkWithAReasonToShow() = runTest {
        // A broken database stands in for any error nothing below the worker handles.
        app.container.db.openHelper.writableDatabase.execSQL("DROP TABLE sources")

        val result = TestListenableWorkerBuilder<EditionWorker>(app).build().doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        val error = result.outputData.getString(EditionWorker.ERROR)
        assertTrue(error, error!!.startsWith("Something went wrong"))
    }

    @Test
    fun aTimedRunThatCantReadAnySourceIsRetriedThenReportedWithTheReason() = runTest {
        runBlocking { com.app.newspaperss.data.SourceRepository(app.container.db).addFeed("https://example.com/unreadable", "Gone") }
        val input = workDataOf(EditionWorker.SCHEDULED to true)

        val first = TestListenableWorkerBuilder<EditionWorker>(app).setInputData(input).build().doWork()
        assertTrue(first is ListenableWorker.Result.Retry)

        val last = TestListenableWorkerBuilder<EditionWorker>(app).setInputData(input).setRunAttemptCount(EditionWorker.RETRIES).build().doWork()
        assertTrue(last is ListenableWorker.Result.Failure)
        assertTrue(last.outputData.getString(EditionWorker.ERROR)!!.startsWith("None of your 1 source could be read"))
    }

    @Test
    fun aBuildMadeByHandThatCantReadAnySourceSaysSoAtOnce() = runTest {
        runBlocking { com.app.newspaperss.data.SourceRepository(app.container.db).addFeed("https://example.com/unreadable", "Gone") }

        // The reader is looking at Today: no silent retries.
        val result = TestListenableWorkerBuilder<EditionWorker>(app).build().doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun aTimedEditionTakesThePlaceOfOneMadeByHandStillWaitingForAConnection() {
        // With KEEP, the timed run was dropped: the paper came from the manual run when the
        // connection did, with no Ready notification, podcast or retries.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        try {
            val work = WorkManager.getInstance(app)
            fun waiting() = work.getWorkInfosForUniqueWork(EditionWorker.UNIQUE).get().filter { it.state == WorkInfo.State.ENQUEUED }
            EditionWorker.buildNow(app)
            val byHand = waiting().single().id

            EditionWorker.buildNow(app, scheduled = true, dueAt = 1_000L)

            val timed = waiting().single()
            assertNotEquals("the timed run is the one waiting now", byHand, timed.id)
            // Cancelled, and pruned at once.
            assertTrue(work.getWorkInfoById(byHand).get()?.state.let { it == null || it == WorkInfo.State.CANCELLED })

            // Made by hand while the timed one waits: the timed one stays, which makes the paper anyway.
            EditionWorker.buildNow(app)
            assertEquals(timed.id, waiting().single().id)
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
}
