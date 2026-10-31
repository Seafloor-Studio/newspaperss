package com.app.newspaperss.listen

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeSpeaker
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.TestDataStores
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.kokoroInstall
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * The natural voice's audio line under Listen, on the edition page itself: shown for every
 * edition whose book is here (ready, sent, or released as not sent, when audio made overnight is
 * most often waiting), in each state of its audio, and not for one that couldn't be made.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class EditionPageAudioTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val stores = TestDataStores()
    @get:Rule(order = 3) val compose = createComposeRule()
    @get:Rule(order = 4) val viewModels = ViewModels()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val editionsDir = File(app.filesDir, "editions").apply { mkdirs() }
    private val repo = EditionRepository(db, editionsDir)
    private val settings by lazy { SettingsStore(stores.preferences("s")) }
    private val store by lazy { PodcastStore(tmp.newFolder("podcasts")) }
    private val listening by lazy {
        val progress = StoredListenProgress(app)
        val player = ListenPlayer(FakeSpeaker(), progress, open = { ListenBook.open(repo, it) }, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        Listening(player, progress, repo, Podcasts(store, settings, kokoroInstall(tmp.newFolder("kokoro"))) {}) {}
    }

    private val article = EditionArticle(
        title = "Lagos Drivers Built Their Own App", sourceTitle = "Rest of World", url = "https://example.com/lagos",
        bodyHtml = "<p>The drivers met in a car park.</p>", minutes = 8.0,
    )

    /** An edition with its book, in [status] with [error]; returns its id. */
    private fun edition(name: String, status: EditionStatus, error: String?, withFile: Boolean = true): Long = runBlocking {
        val file = "$name.epub"
        if (withFile) {
            FileOutputStream(File(editionsDir, file)).use {
                EpubWriter.write(EditionDoc(name, LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-0000000000${name.length}0", listOf(EditionSection(null, listOf(article)))), it)
            }
        }
        val id = db.editions().insert(EditionEntity(title = name, status = status, error = error, fileName = file.takeIf { withFile }, articleCount = 1, minutes = article.minutes))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = id, articleId = null, position = 0, title = article.title, sourceTitle = article.sourceTitle, minutes = article.minutes)))
        id
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theAudioLineIsOnEveryEditionWithItsBookInEachStateOfItsAudio() {
        runBlocking { settings.update { it.copy(listenVoice = ListenVoice.PODCAST, podcastPace = 1.2f) } }
        data class Case(val status: EditionStatus, val error: String?, val withFile: Boolean, val line: Boolean)
        val cases = listOf(
            Case(EditionStatus.READY, null, withFile = true, line = true),
            Case(EditionStatus.DELIVERED, null, withFile = true, line = true),
            Case(EditionStatus.FAILED, EditionBuilder.NOT_SENT, withFile = true, line = true),
            Case(EditionStatus.FAILED, EditionBuilder.OLD_NOT_SENT, withFile = true, line = true),
            Case(EditionStatus.FAILED, EditionBuilder.INTERRUPTED, withFile = false, line = false),
            Case(EditionStatus.DELIVERED, null, withFile = false, line = false),
        )
        // Each audio state, as the store holds it, with what the line says then.
        val states: List<Pair<String, (Long) -> Unit>> = listOf(
            "Waiting to make the audio" to { id -> store.want(id, PodcastVoice.HEART) },
            "min made" to { id -> store.want(id, PodcastVoice.HEART); store.making(id); store.lines(id, 0, 2); store.keep(id, 0, 0, store.scratch(id, 0, 0).apply { writeText("audio") }, listOf(0.0)) },
            "Audio ready." to { id -> store.want(id, PodcastVoice.HEART); store.complete(id, 0); store.finish(id) },
        )
        // One screen shown each edition in turn: a test can set its content only once.
        var vm by mutableStateOf<EditionDetailViewModel?>(null)
        compose.setContent { vm?.let { key(it) { EditionDetailScreen(it, onBack = {}, listening = listening, onOpenPlayer = {}) } } }
        var n = 0
        for (case in cases) for ((says, make) in states) {
            val label = "$case, $says"
            val id = edition("Edition${n++}", case.status, case.error, case.withFile)
            make(id)
            vm = viewModels.add(EditionDetailViewModel(repo, id) {})
            compose.idleUntil { shown("Contents") }
            if (case.line) {
                compose.idleUntil { shown(says) }
            } else {
                compose.waitForIdle()
                assertEquals(label, false, shown("audio"))
            }
            store.making(null)
        }
    }
}
