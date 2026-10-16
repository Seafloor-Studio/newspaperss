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
import androidx.work.workDataOf
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class TtrssMarkReadWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container = app.container

    private suspend fun worker(editionId: Long, attempt: Int) = TestListenableWorkerBuilder<TtrssMarkReadWorker>(app)
        .setInputData(workDataOf(TtrssMarkReadWorker.EDITION_ID to editionId))
        .setRunAttemptCount(attempt)
        .build()
        .doWork()

    @Test
    fun retriesWhileTtrssIsUnreachableThenGivesUp() = runTest {
        val server = FakeTtrss(app.http)
        assertNull(container.ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        container.feedSync.syncAll()
        val db = container.db
        val source = db.sources().ofKind(SourceKind.TTRSS).single()
        val article = db.articles().allForSource(source.id).single()
        val editionId = db.editions().insert(EditionEntity(title = "Tuesday", status = EditionStatus.DELIVERED))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = editionId, articleId = article.id, position = 0, title = "One", sourceTitle = "Example News", minutes = 1.0)))

        app.http.unreachable += server.apiUrl
        assertTrue(worker(editionId, attempt = 0) is ListenableWorker.Result.Retry)
        assertTrue(worker(editionId, attempt = TtrssMarkReadWorker.MAX_ATTEMPTS - 1) is ListenableWorker.Result.Failure)

        app.http.unreachable.clear()
        assertTrue(worker(editionId, attempt = 1) is ListenableWorker.Result.Success)
        assertEquals(listOf(10L), server.markedRead)
    }

    private fun deliveredEdition(server: FakeTtrss): Long = runBlocking {
        assertNull(container.ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        server.add(10, "One", feedId = 1, feedTitle = "Example News")
        container.feedSync.syncAll()
        val db = container.db
        val article = db.articles().allForSource(db.sources().ofKind(SourceKind.TTRSS).single().id).single()
        val editionId = db.editions().insert(EditionEntity(title = "Tuesday", status = EditionStatus.DELIVERED))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = editionId, articleId = article.id, position = 0, title = "One", sourceTitle = "Example News", minutes = 1.0)))
        editionId
    }

    private fun markingWork(editionId: Long) = WorkManager.getInstance(app).getWorkInfosForUniqueWork("ttrss-mark-read-$editionId").get().single()

    /** Delivered, the articles are marked read in tt-rss once the short wait is over. */
    @Test
    fun aDeliveryReachesTtrssAfterTheWait() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val server = FakeTtrss(app.http)
        val editionId = deliveredEdition(server)

        TtrssMarkReadWorker.enqueue(app, editionId)
        val work = markingWork(editionId)
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        driver.setAllConstraintsMet(work.id)
        assertEquals("waits", WorkInfo.State.ENQUEUED, markingWork(editionId).state)
        assertTrue(server.markedRead.isEmpty())

        driver.setInitialDelayMet(work.id)
        idleUntil { markingWork(editionId).state == WorkInfo.State.SUCCEEDED }
        assertEquals(listOf(10L), server.markedRead)
    }

    /** Sent to the wrong app and marked as not sent within the wait: tt-rss is never told it was read. */
    @Test
    fun aQuickMarkAsNotSentNeverReachesTtrssAsRead() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val server = FakeTtrss(app.http)
        val editionId = deliveredEdition(server)
        TtrssMarkReadWorker.enqueue(app, editionId)
        val delivered = markingWork(editionId)
        val driver = WorkManagerTestInitHelper.getTestDriver(app)!!
        // Online: only the wait holds it back.
        driver.setAllConstraintsMet(delivered.id)

        runBlocking { container.db.editions().run { update(byId(editionId)!!.copy(status = EditionStatus.READY)) } }
        TtrssMarkReadWorker.enqueue(app, editionId)
        val unsent = markingWork(editionId)
        driver.setAllConstraintsMet(unsent.id)
        driver.setInitialDelayMet(unsent.id)
        idleUntil { markingWork(editionId).state == WorkInfo.State.SUCCEEDED }

        // Replaced: cancelled, or already pruned.
        val replaced = WorkManager.getInstance(app).getWorkInfoById(delivered.id).get()
        assertTrue(replaced == null || replaced.state == WorkInfo.State.CANCELLED)
        assertTrue(server.markedRead.isEmpty())
    }
}
