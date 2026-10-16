package com.app.newspaperss.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.app.newspaperss.edition.BuildResult
import com.app.newspaperss.edition.EditionSettings
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class NotesWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container = app.container
    private val saved = File(app.filesDir, "notes/saved")

    @Before fun chooseNotesFolder() = runTest {
        container.settings.update { it.copy(notesFolderUri = "content://vault", notesFolderName = "Vault") }
    }

    private suspend fun work(editionId: Long) = TestListenableWorkerBuilder<NotesWorker>(app)
        .setInputData(workDataOf(NotesWorker.EDITION_ID to editionId))
        .build()
        .doWork()

    @Test
    fun theEditionItIsGivenHasItsNotesSaved() = runTest {
        container.sources.addFeed("https://example.com/feed", "Blog")
        app.http.page("https://example.com/feed", rss("Blog", "1" to "One"))
        container.feedSync.syncAll()
        val id = (container.editionBuilder.build(EditionSettings()) as BuildResult.Built).editionId
        container.editions.markSent(id)
        val title = container.db.editions().byId(id)!!.title

        assertTrue(work(id) is ListenableWorker.Result.Success)

        assertTrue(File(saved, "$title notes.md").readText().contains("## One"))
    }

    @Test
    fun anEditionThatIsGoneIsNotRetried() = runTest {
        assertTrue(work(42L) is ListenableWorker.Result.Success)
        assertEquals("nothing to save", null, saved.list()?.toList())
    }
}
