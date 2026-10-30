package com.app.newspaperss.listen

import android.app.Application
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithText
import androidx.media3.common.Player
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.edition.ArticleContent
import com.app.newspaperss.edition.ArticleContentProvider
import com.app.newspaperss.edition.BuildResult
import com.app.newspaperss.edition.EditionSettings
import androidx.compose.ui.test.assertIsEnabled
import com.app.newspaperss.settings.Recordings
import com.app.newspaperss.testutil.FakeSpeaker
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.transparentPng
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/** Listening to a real edition's book: the player, the edition page's button, the playing screen and the media controls. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class ListenAppTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val viewModels = ViewModels()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val editionsDir = File(app.filesDir, "editions").apply { mkdirs() }
    private val repo = EditionRepository(db, editionsDir)
    private val speaker = FakeSpeaker()
    private val progress = StoredListenProgress(app)
    private val player = ListenPlayer(speaker, progress, open = { ListenBook.open(repo, it) }, CoroutineScope(SupervisorJob() + Dispatchers.Main))
    private var connected = 0
    private val listening = Listening(player, progress, repo) { connected++ }

    private val knots = EditionArticle(
        title = "The Mathematician Who Counted Knots", sourceTitle = "Quanta Magazine", url = "https://example.com/knots",
        bodyHtml = "<p>Nobody could say how many knots there are. Then a teacher wrote a program.</p>" +
            "<figure><img src=\"images/table.png\"/><figcaption>Tait's table of knots</figcaption></figure>" +
            "<p>The count took a week.</p>",
        minutes = 4.0, author = "Erica Klarreich", images = listOf(EpubImage("images/table.png", "image/png", transparentPng(40, 30))),
    )
    private val lagos = EditionArticle(
        title = "Lagos Drivers Built Their Own App", sourceTitle = "Rest of World", url = "https://example.com/lagos",
        bodyHtml = "<p>The drivers met in a car park.</p>", minutes = 2.0,
    )

    /** An edition with its book written; returns its id. */
    private fun edition(title: String = "Thursday Morning Edition", vararg articles: EditionArticle = arrayOf(knots, lagos)): Long = runBlocking {
        val file = "${title.hashCode()}.epub"
        FileOutputStream(File(editionsDir, file)).use {
            EpubWriter.write(EditionDoc(title, LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-00000000000${articles.size}", listOf(EditionSection(null, articles.toList()))), it)
        }
        val id = db.editions().insert(EditionEntity(title = title, status = EditionStatus.DELIVERED, fileName = file, articleCount = articles.size, minutes = articles.sumOf { it.minutes }))
        db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = id, articleId = null, position = i, title = a.title, sourceTitle = a.sourceTitle, minutes = a.minutes) })
        id
    }

    private fun startAndWait(id: Long) {
        listening.start(id)
        idleUntil { player.state.value.script != null }
    }

    @Test
    fun aPageThatPlaysItsEpisodeTakesTheEpisodesTimeNotItsText() = runBlocking {
        // 1,600 words, ten minutes read aloud, against a 15-minute episode.
        val transcript = EditionArticle(
            title = "Transcript: the ferry planner", sourceTitle = "The Example Show", url = "https://example.com/t",
            bodyHtml = "<p>" + List(160) { (1..10).joinToString(" ") { "word" } + "." }.joinToString(" ") + "</p>", minutes = 8.0,
        )
        FileOutputStream(File(editionsDir, "f.epub")).use {
            EpubWriter.write(EditionDoc("Friday Morning Edition", LocalDate.of(2026, 10, 2), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000020", listOf(EditionSection(null, listOf(transcript, lagos)))), it)
        }
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Show"))
        val article = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = "t", url = transcript.url, title = transcript.title, episodeKind = EpisodeKind.TRANSCRIPT, audioUrl = "https://cdn.example/t.mp3", audioSeconds = 900),
        )
        val id = db.editions().insert(EditionEntity(title = "Friday Morning Edition", status = EditionStatus.DELIVERED, fileName = "f.epub", articleCount = 2, minutes = 10.0))
        db.editions().insertArticles(
            listOf(
                EditionArticleEntity(editionId = id, articleId = article, position = 0, title = transcript.title, sourceTitle = transcript.sourceTitle, minutes = transcript.minutes),
                EditionArticleEntity(editionId = id, articleId = null, position = 1, title = lagos.title, sourceTitle = lagos.sourceTitle, minutes = lagos.minutes),
            ),
        )

        val book = ListenBook.open(repo, id, Readings(db, { Recordings() }))!!

        assertEquals(15.0, book.pages[0].minutes, 0.001)
        assertEquals(ListenTime.fromReading(2.0), book.pages[1].minutes, 0.001)
    }

    /**
     * A notes post's episode plays once the edition is over, after "That's all for today", on a
     * page of its own; the notes are read in their place.
     */
    @Test
    fun aNotesPostsEpisodePlaysAfterTheClosingPage() {
        val notes = EditionArticle(
            title = "Episode 12: The ferry planner", sourceTitle = "The Example Show", url = "https://example.com/12",
            bodyHtml = "<p>This week we talk to a ferry planner.</p>", minutes = 1.0,
        )
        FileOutputStream(File(editionsDir, "n.epub")).use {
            EpubWriter.write(EditionDoc("Saturday Morning Edition", LocalDate.of(2026, 10, 3), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000030", listOf(EditionSection(null, listOf(notes, lagos)))), it)
        }
        val id = runBlocking {
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Show"))
            val article = db.articles().insertIgnoring(
                ArticleEntity(sourceId = source, guid = "12", url = notes.url, title = notes.title, episodeKind = EpisodeKind.NOTES, audioUrl = "https://cdn.example/12.mp3", audioSeconds = 2_280),
            )
            db.editions().insert(EditionEntity(title = "Saturday Morning Edition", status = EditionStatus.DELIVERED, fileName = "n.epub", articleCount = 2, minutes = 3.0)).also { id ->
                db.editions().insertArticles(
                    listOf(
                        EditionArticleEntity(editionId = id, articleId = article, position = 0, title = notes.title, sourceTitle = notes.sourceTitle, minutes = notes.minutes),
                        EditionArticleEntity(editionId = id, articleId = null, position = 1, title = lagos.title, sourceTitle = lagos.sourceTitle, minutes = lagos.minutes),
                    ),
                )
            }
        }
        val readings = Readings(db, { Recordings() })
        val player = ListenPlayer(speaker, progress, open = { ListenBook.open(repo, it, readings) }, CoroutineScope(SupervisorJob() + Dispatchers.Main))

        player.start(id)
        idleUntil { player.state.value.script != null }
        val pages = player.state.value.pages
        assertEquals(listOf(false, false, true, false), pages.map { it.end })
        assertEquals(listOf(false, false, false, true), pages.map { it.episode })
        assertEquals(38.0, pages[3].minutes, 0.001)
        assertEquals("https://cdn.example/12.mp3", readings.at(id, 3)?.url)
        assertNull("the notes are read in their place", readings.at(id, 0))

        // Without the recording's speaker, the voice says the episode page's lines: its heading and marks.
        val heard = mutableListOf<String>()
        while (speaker.queue.isNotEmpty() && player.state.value.at.page < 3) {
            heard += speaker.sayNext()
            idleUntil { speaker.queue.isNotEmpty() || player.state.value.finished }
        }
        val end = heard.indexOf("That's all for today.")
        assertTrue(heard.toString(), heard.indexOf("This week we talk to a ferry planner.") in 0 until end)
        idleUntil { player.state.value.at.page == 3 && speaker.queue.isNotEmpty() }
        assertEquals("The Example Show", speaker.sayNext())
        // Into the episodes, the edition is heard: Resume and "Finish it first?" are about the paper.
        assertTrue(progress.finished(id))
        assertNull("where it is in an episode isn't kept", listening.saved(id))
        assertEquals("the edition's own length, not the episode's", player.state.value.secondsTotal, player.state.value.secondsIn, 0.001)
        // The episode's time is its marks', not its heading's: at "19:00", 19 minutes in.
        player.seek(ListenPosition(3, 2 + 38))
        idleUntil { player.state.value.at == ListenPosition(3, 40) }
        assertEquals(19 * 60.0, player.state.value.secondsInPage, 1.0)
        // ⏮ from its start lands on the closing page, which doesn't make the edition unfinished again.
        player.seek(ListenPosition(3, 0))
        idleUntil { player.state.value.at == ListenPosition(3, 0) }
        player.pause()
        player.previous()
        idleUntil { player.state.value.at.page == 2 }
        assertTrue(progress.finished(id))

        compose.setContent(playing(id, player))
        player.seek(ListenPosition(3, 0))
        idleUntil { player.state.value.at.page == 3 && compose.onAllNodesWithText("Tap a time to play from there.").fetchSemanticsNodes().isNotEmpty() }
        player.seek(ListenPosition(1, 0))
        idleUntil { player.state.value.at.page == 1 && compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        // From the edition, one tap to the episodes after it; on one, its own times and how to move in it.
        compose.onNodeWithText("Then 1 episode ›").performClick()
        idleUntil { player.state.value.at.page == 3 }
        compose.onNodeWithText("Episode 1 of 1 · 0 min in · 38 min left").assertIsDisplayed()
        compose.onNodeWithText("Tap a time to play from there.").assertExists()
        compose.onAllNodesWithText("Then 1 episode ›").fetchSemanticsNodes().let { assertEquals("not on the episode itself", 0, it.size) }
        player.seek(ListenPosition(3, 2 + 38))
        idleUntil { player.state.value.at == ListenPosition(3, 40) }
        compose.onNodeWithText("Episode 1 of 1 · 19 min in · 19 min left").assertIsDisplayed()
        player.stop()
    }

    @Test
    fun theBookIsReadArticleByArticleWithItsPicturesThenItsClosingPage() {
        val id = edition()
        startAndWait(id)
        val heard = mutableListOf<String>()
        while (speaker.queue.isNotEmpty()) {
            heard += speaker.sayNext()
            idleUntil { speaker.queue.isNotEmpty() || player.state.value.finished }
        }
        assertEquals(
            listOf(
                "Quanta Magazine", "The Mathematician Who Counted Knots", "By Erica Klarreich",
                "Nobody could say how many knots there are.", "Then a teacher wrote a program.", "Image: Tait's table of knots", "The count took a week.",
                "Rest of World", "Lagos Drivers Built Their Own App", "The drivers met in a car park.",
            ),
            heard.take(10),
        )
        assertEquals("That's all for today.", heard[10])
        assertTrue(progress.finished(id))
        assertEquals(1, connected)
        runBlocking { assertNotNull(player.image("images/table.png")) }
    }

    @Test
    fun whereItWasLeftIsKeptForTheMostRecentEditions() {
        val stored = StoredListenProgress(app, now = { System.nanoTime() })
        for (id in 1L..12L) stored.set(id, ListenPosition(1, 2))
        assertNull(stored.get(1))
        assertEquals(ListenPosition(1, 2), stored.get(12))
        assertEquals(12L, stored.unfinished().first())
        stored.finish(12)
        assertTrue(stored.finished(12))
        assertFalse(12L in stored.unfinished())
        assertEquals(10, stored.unfinished().size + 1)
    }

    @Test
    fun theEditionPageOffersListenThenResumeAndYesterdaysPaperFirst() {
        val yesterday = edition("Wednesday Morning Edition", lagos)
        val today = edition()
        progress.set(yesterday, ListenPosition(0, 1))
        val vm = viewModels.add(EditionDetailViewModel(repo, today) {})
        var openedPlayer = 0
        compose.setContent { EditionDetailScreen(vm, onBack = {}, listening = listening, onOpenPlayer = { openedPlayer++ }) }
        // 6 minutes to read is 9 to hear.
        idleUntil { compose.onAllNodesWithText("Listen · about 9 min").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Listen · about 9 min").performClick()
        idleUntil { compose.onAllNodesWithText("Finish Wednesday Morning Edition first?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finish Wednesday Morning Edition first?").assertIsDisplayed()
        compose.onNodeWithText("Finish it").performClick()
        idleUntil { player.state.value.script != null }
        assertEquals(yesterday, player.state.value.editionId)
        // Where it was left: its second line.
        assertEquals("Lagos Drivers Built Their Own App", speaker.queue.first().text)
        assertEquals(1, openedPlayer)

        // Playing yesterday's; today's still says Listen, and starting it now is the choice made.
        compose.onNodeWithText("Listen · about 9 min").performClick()
        idleUntil { compose.onAllNodesWithText("Start this one").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Start this one").performClick()
        idleUntil { player.state.value.editionId == today && player.state.value.script != null }
        compose.onNodeWithText("Listening · Open player").performClick()
        assertEquals(3, openedPlayer)

        player.next()
        player.pause()
        idleUntil { compose.onAllNodesWithText("Resume · 3 min left").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Resume · 3 min left").assertIsDisplayed()
    }

    /**
     * Listen is on every edition whose book is still here, whatever it says about sending: ready,
     * sent, or released as not sent when a newer one was made, which may have been heard part way.
     * Not on one being made, one that couldn't be made, or one whose book is gone.
     */
    @Test
    fun everyEditionWithItsBookOffersListenAndWhereItWasLeft() {
        val cases = listOf(
            Triple(EditionStatus.READY, null, true),
            Triple(EditionStatus.DELIVERED, null, true),
            Triple(EditionStatus.FAILED, EditionBuilder.NOT_SENT, true),
            Triple(EditionStatus.FAILED, EditionBuilder.OLD_NOT_SENT, true),
            Triple(EditionStatus.FAILED, "Couldn't reach any of your sources.", false),
            Triple(EditionStatus.BUILDING, null, false),
        )
        var showing by mutableStateOf<EditionDetailViewModel?>(null)
        compose.setContent { showing?.let { vm -> key(vm) { EditionDetailScreen(vm, onBack = {}, listening = listening, onOpenPlayer = {}) } } }
        fun show(id: Long) {
            showing = viewModels.add(EditionDetailViewModel(repo, id) {})
            idleUntil { compose.onAllNodesWithText("Contents").fetchSemanticsNodes().isNotEmpty() }
        }
        for ((status, error, offered) in cases) {
            val id = edition("$status $error")
            runBlocking { db.editions().update(db.editions().byId(id)!!.copy(status = status, error = error)) }
            progress.set(id, ListenPosition(1, 0))
            show(id)
            assertEquals("$status $error", offered, compose.onAllNodesWithText("Resume · 3 min left").fetchSemanticsNodes().isNotEmpty())
            if (offered) {
                compose.onNodeWithText("Resume · 3 min left").performClick()
                idleUntil { player.state.value.editionId == id && player.state.value.at.page == 1 }
                player.stop()
            }
        }
        // Its book gone, a sent one has nothing to read aloud.
        val gone = edition("Gone")
        runBlocking { File(editionsDir, db.editions().byId(gone)!!.fileName!!).delete() }
        show(gone)
        compose.onNodeWithText("This edition's file has been deleted, so it can't be sent or opened.").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Listen", substring = true).fetchSemanticsNodes().size)
    }

    /**
     * An edition released by a real second build, not one given "not sent" by hand: its page
     * still offers Listen where it was left, Mark as sent, Save a copy, and its articles to read.
     */
    @Test
    fun anEditionReleasedByTheNextBuildStillOffersItsBook() {
        val sources = SourceRepository(db)
        val content = ArticleContentProvider { a, _, _, _ -> ArticleContent(a.title, null, "<p>${a.title} first. ${a.title} second.</p>", wordCount = 600) }
        val builder = EditionBuilder(db, content, editionsDir)
        suspend fun article(feed: String, guid: String) {
            val source = sources.addFeed("https://$feed.example/feed", feed)
            db.articles().insertNew(listOf(ArticleEntity(sourceId = source, guid = guid, url = "https://$feed.example/$guid", title = "$feed $guid")))
        }
        val first = runBlocking {
            article("a", "a1")
            article("b", "b1")
            builder.build(EditionSettings()) as BuildResult.Built
        }.editionId
        progress.set(first, ListenPosition(1, 0))
        runBlocking {
            article("c", "c1")
            builder.build(EditionSettings()) as BuildResult.Built
        }
        assertEquals(EditionStatus.FAILED, runBlocking { db.editions().byId(first) }!!.status)

        var read: Int? = null
        val vm = viewModels.add(EditionDetailViewModel(repo, first) {})
        compose.setContent { EditionDetailScreen(vm, onBack = {}, onReadArticle = { read = it }, listening = listening, onOpenPlayer = {}) }
        idleUntil { compose.onAllNodesWithText("Contents").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Mark as sent").assertIsEnabled()
        compose.onNodeWithText("b b1").performClick()
        assertEquals(1, read)
        compose.onNodeWithText("Resume", substring = true).performClick()
        idleUntil { player.state.value.editionId == first && player.state.value.at.page == 1 }
        player.stop()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Save a copy…").assertExists()
    }

    /**
     * The morning's build releases the ready edition whose page is open and which is being
     * listened to: the page keeps Listen (and with it the audio line, which shows on the same
     * condition), gains Mark as sent, and the player carries on.
     */
    @Test
    fun anEditionReleasedWhileItsPageIsOpenKeepsListenAndPlaysOn() {
        val id = edition("Thursday Morning Edition")
        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(status = EditionStatus.READY)) }
        val vm = viewModels.add(EditionDetailViewModel(repo, id) {})
        compose.setContent { EditionDetailScreen(vm, onBack = {}, listening = listening, onOpenPlayer = {}) }
        idleUntil { compose.onAllNodesWithText("Listen", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Listen", substring = true).performClick()
        idleUntil { player.state.value.editionId == id && player.state.value.playing }

        // As EditionBuilder.releaseUndelivered does when the next edition is planned.
        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(status = EditionStatus.FAILED, error = EditionBuilder.NOT_SENT)) }
        idleUntil { compose.onAllNodesWithText(EditionBuilder.NOT_SENT).fetchSemanticsNodes().isNotEmpty() }

        assertTrue("Listen stays", compose.onAllNodesWithText("Listen", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Mark as sent").assertIsEnabled()
        assertEquals(id, player.state.value.editionId)
        assertTrue(player.state.value.playing)
        player.stop()
    }

    @Test
    fun yesterdaysPaperQuestionSurvivesTheScreenBeingRecreated() {
        val yesterday = edition("Wednesday Morning Edition", lagos)
        val today = edition()
        progress.set(yesterday, ListenPosition(0, 1))
        val vm = viewModels.add(EditionDetailViewModel(repo, today) {})
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EditionDetailScreen(vm, onBack = {}, listening = listening, onOpenPlayer = {}) }
        idleUntil { compose.onAllNodesWithText("Listen · about 9 min").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Listen · about 9 min").performClick()
        idleUntil { compose.onAllNodesWithText("Finish Wednesday Morning Edition first?").fetchSemanticsNodes().isNotEmpty() }

        restoration.emulateSavedInstanceStateRestore()

        idleUntil { compose.onAllNodesWithText("Finish Wednesday Morning Edition first?").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Finish it").performClick()
        idleUntil { player.state.value.script != null }
        assertEquals(yesterday, player.state.value.editionId)
    }

    @Test
    fun yesterdaysPaperFinishedMeanwhileIsNotAskedAboutAgainOnRestore() {
        val yesterday = edition("Wednesday Morning Edition", lagos)
        val today = edition()
        progress.set(yesterday, ListenPosition(0, 1))
        val vm = viewModels.add(EditionDetailViewModel(repo, today) {})
        val restoration = StateRestorationTester(compose)
        restoration.setContent { EditionDetailScreen(vm, onBack = {}, listening = listening, onOpenPlayer = {}) }
        idleUntil { compose.onAllNodesWithText("Listen · about 9 min").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Listen · about 9 min").performClick()
        idleUntil { compose.onAllNodesWithText("Finish Wednesday Morning Edition first?").fetchSemanticsNodes().isNotEmpty() }

        // Finished on another screen, or its file pruned, while the app was away.
        progress.finish(yesterday)
        restoration.emulateSavedInstanceStateRestore()

        idleUntil { compose.onAllNodesWithText("Finish Wednesday Morning Edition first?").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theContentsDontComeBackOverAPlayerWithNothingInIt() {
        val id = edition()
        startAndWait(id)
        val restoration = StateRestorationTester(compose)
        restoration.setContent(playing(id))
        idleUntil { compose.onAllNodes(hasContentDescription("Contents")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Thursday Morning Edition").assertIsDisplayed()

        // As after Android ended the app: the screen comes back, the player starts empty.
        player.stop()
        idleUntil { player.state.value.pages.isEmpty() }
        restoration.emulateSavedInstanceStateRestore()

        compose.waitForIdle()
        compose.onNodeWithText("Close").assertDoesNotExist()
    }

    @Test
    fun theOpenContentsSurviveTheScreenBeingRecreated() {
        val id = edition()
        startAndWait(id)
        val restoration = StateRestorationTester(compose)
        restoration.setContent(playing(id))
        idleUntil { compose.onAllNodes(hasContentDescription("Contents")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNodeWithText("Thursday Morning Edition").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("Thursday Morning Edition").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Thursday Morning Edition").assertDoesNotExist()
    }

    @Test
    fun anArticlePlayingItsRecordingSaysSoAndOffersTheVoiceInstead() {
        val id = edition()
        startAndWait(id)
        val now = kotlinx.coroutines.flow.MutableStateFlow<ReadingSpeaker.Now?>(
            ReadingSpeaker.Now(id, 0, Reading("https://cdn.example/r.mp3", 130, own = false, bodyLine = 3, starts = listOf(0.0)), voiceInstead = false),
        )
        val asked = mutableListOf<Boolean>()
        compose.setContent(playing(id, reading = now, onVoiceInstead = { asked += it }))

        idleUntil { compose.onAllNodesWithText("Playing its recorded reading · 2 min.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Have the voice read it instead").performClick()
        assertEquals(listOf(true), asked)

        now.value = now.value!!.copy(voiceInstead = true)
        compose.onNodeWithText("Play its recorded reading").performClick()
        assertEquals(listOf(true, false), asked)

        // Only on its own article.
        compose.onNodeWithContentDescription("Next article").performClick()
        idleUntil { player.state.value.at.page == 1 }
        compose.onNodeWithText("recorded reading", substring = true).assertDoesNotExist()
    }

    @Test
    fun thePlayingViewFollowsTheVoiceAndATappedSentenceIsReadFromThere() {
        val id = edition()
        startAndWait(id)
        compose.setContent(playing(id))
        idleUntil { compose.onAllNodesWithText("Tait's table of knots").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Tait's table of knots").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").performClick()
        assertFalse(player.state.value.playing)
        compose.onNodeWithContentDescription("Play").performClick()
        assertTrue(player.state.value.playing)

        // The paragraph's second sentence: near the end of its text.
        compose.onNodeWithText("Nobody could say", substring = true).performTouchInput { click(centerRight.copy(x = right - 4f)) }
        idleUntil { speaker.queue.firstOrNull()?.text == "Then a teacher wrote a program." }
        compose.onNodeWithContentDescription("Next sentence").performClick()
        assertEquals("Image: Tait's table of knots", speaker.queue.first().text)
        compose.onNodeWithContentDescription("Back a sentence").performClick()
        compose.onNodeWithContentDescription("Next article").performClick()
        idleUntil { player.state.value.at.page == 1 && compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Previous article").performClick()
        idleUntil { player.state.value.at.page == 0 }

        compose.onNodeWithContentDescription("Speed 1×. Change speed").performClick()
        assertEquals(1.2f, player.state.value.speed)

        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNode(hasText("Lagos Drivers Built Their Own App") and hasAnyAncestor(isDialog())).performClick()
        idleUntil { player.state.value.at.page == 1 }
    }

    /**
     * One view for reading and listening: Listen from the article preview plays in place, the
     * player under the page, the sentence being read marked, a tapped sentence read from while it
     * plays (not while paused), and the page turning with the voice and the voice with the page.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun thePreviewListensInPlaceAndTurnsWithTheVoice() {
        val id = edition()
        compose.setContent {
            ArticlePreviewScreen(
                loadFile = { repo.byId(id)?.let(repo::fileOf) }, position = 0, title = "Thursday Morning Edition", onBack = {},
                onListen = { listening.start(id, ListenPosition(it, 0)) }, player = player, editionId = id,
            )
        }
        idleUntil { compose.onAllNodesWithText("Nobody could say", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no player before Listen", compose.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithContentDescription("Listen from here").performClick()
        idleUntil { player.state.value.editionId == id && player.state.value.playing && player.state.value.script != null }
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Listen from here").assertDoesNotExist()
        // The sentence being read is underlined in the page, once the voice reaches it past the heading.
        while (speaker.queue.firstOrNull()?.text != "Nobody could say how many knots there are.") speaker.sayNext()
        speaker.startNext()
        idleUntil { underlined("Nobody could say") == listOf("Nobody could say how many knots there are.") }

        // Playing, a tapped sentence is read from.
        tapWord(hasText("Nobody could say", substring = true), "program")
        idleUntil { speaker.queue.firstOrNull()?.text == "Then a teacher wrote a program." }

        // The voice going on to the next article turns the page.
        compose.onNodeWithContentDescription("Next article").performClick()
        idleUntil { compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        // Back a page, by the voice, and the page follows it there too.
        compose.onNodeWithContentDescription("Previous article").performClick()
        idleUntil { compose.onAllNodesWithText("Nobody could say", substring = true).fetchSemanticsNodes().isNotEmpty() && player.state.value.at.page == 0 }

        // Paused, a tap on a sentence doesn't move the voice.
        compose.onNodeWithContentDescription("Pause").performClick()
        val at = player.state.value.at
        tapWord(hasText("Nobody could say", substring = true), "program")
        compose.waitForIdle()
        assertEquals(at, player.state.value.at)
        assertFalse(player.state.value.playing)

        // Paused, the page's "Next" line turns only the page: the voice stays where it was, quiet.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next:", substring = true))
        tapWord(hasText("Next:", substring = true), "Lagos")
        idleUntil { compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(at, player.state.value.at)
        assertFalse(player.state.value.playing)
        // On another article than the voice's, Listen from here is offered again.
        compose.onNodeWithContentDescription("Listen from here").assertExists()
        player.stop()
    }

    /**
     * The one view, open at the page [player] is on, as the bar and the notification open it.
     * Its top bar has the article's title; "Thursday Morning Edition" is the edition's, in Contents.
     */
    private fun playing(
        id: Long,
        player: ListenPlayer = this.player,
        textSize: com.app.newspaperss.settings.PreviewTextSize? = com.app.newspaperss.settings.PreviewTextSize.DEFAULT,
        reading: kotlinx.coroutines.flow.StateFlow<ReadingSpeaker.Now?> = kotlinx.coroutines.flow.MutableStateFlow(null),
        onVoiceInstead: (Boolean) -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit {
        val at = player.state.value.at.page
        return {
            ArticlePreviewScreen(
                loadFile = { repo.byId(id)?.let(repo::fileOf) }, position = at, title = "An article", onBack = {}, textSize = textSize,
                onListen = { listening.start(id, ListenPosition(it, 0)) }, player = player, editionId = id, reading = reading, onVoiceInstead = onVoiceInstead,
            )
        }
    }

    /** The preview of one article, with Listen's player, as the app shows it. */
    private fun preview(id: Long, position: Int) = @androidx.compose.runtime.Composable {
        ArticlePreviewScreen(
            loadFile = { repo.byId(id)?.let(repo::fileOf) }, position = position, title = "Thursday Morning Edition", onBack = {},
            onListen = { listening.start(id, ListenPosition(it, 0)) }, player = player, editionId = id,
        )
    }

    /**
     * Listen paused on one article, another opened: the page is the one opened, not the voice's,
     * and stays so when the screen is recreated; playing on from there turns it with the voice.
     */
    @Test
    fun anArticleOpenedWhileListenIsPausedElsewhereStaysOnScreen() {
        val trains = EditionArticle(title = "Night Trains Return", sourceTitle = "The Example Review", url = "https://example.com/trains", bodyHtml = "<p>Sleepers are back.</p>", minutes = 2.0)
        val id = edition("Three", knots, lagos, trains)
        startAndWait(id)
        player.pause()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { preview(id, position = 2)() }
        idleUntil { compose.onAllNodesWithText("Sleepers are back.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        idleUntil { compose.onAllNodesWithText("Sleepers are back.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("still not the voice's article", compose.onAllNodesWithText("Nobody could say", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(0, player.state.value.at.page)

        // The voice moving on now turns the page to where it went.
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Next article").performClick()
        idleUntil { player.state.value.at.page == 1 && compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        player.stop()
    }

    /**
     * In the one view, the voice going past the last article draws the closing page there, from
     * Listen's own script, and Listen's contents move the page and the voice together.
     */
    @Test
    fun theClosingPageAndTheContentsAreInTheArticleView() {
        val id = edition()
        compose.setContent { preview(id, position = 1)() }
        idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()
        idleUntil { player.state.value.at.page == 1 && player.state.value.script != null }

        // Not the last article's own "Next" line, which says the same: its text is gone.
        compose.onNodeWithContentDescription("Next article").performClick()
        idleUntil {
            player.state.value.at.page == 2 && compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(EpubWriter.END_TITLE, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNode(hasText("The Mathematician Who Counted Knots") and hasAnyAncestor(isDialog())).performClick()
        idleUntil { player.state.value.at.page == 0 && compose.onAllNodesWithText("Nobody could say", substring = true).fetchSemanticsNodes().isNotEmpty() }
        player.stop()
    }

    /** An edition whose notes post's episode plays after its closing page: page 2 the closing page, page 3 the episode. */
    private fun editionWithAnEpisodeAfterIt(): Long = runBlocking {
        val notes = EditionArticle(
            title = "Episode 12: The ferry planner", sourceTitle = "The Example Show", url = "https://example.com/12",
            bodyHtml = "<p>This week we talk to a ferry planner.</p>", minutes = 1.0,
        )
        FileOutputStream(File(editionsDir, "n.epub")).use {
            EpubWriter.write(EditionDoc("Saturday Morning Edition", LocalDate.of(2026, 10, 3), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000030", listOf(EditionSection(null, listOf(notes, lagos)))), it)
        }
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Show"))
        val article = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = "12", url = notes.url, title = notes.title, episodeKind = EpisodeKind.NOTES, audioUrl = "https://cdn.example/12.mp3", audioSeconds = 2_280),
        )
        db.editions().insert(EditionEntity(title = "Saturday Morning Edition", status = EditionStatus.DELIVERED, fileName = "n.epub", articleCount = 2, minutes = 3.0)).also { id ->
            db.editions().insertArticles(
                listOf(
                    EditionArticleEntity(editionId = id, articleId = article, position = 0, title = notes.title, sourceTitle = notes.sourceTitle, minutes = notes.minutes),
                    EditionArticleEntity(editionId = id, articleId = null, position = 1, title = lagos.title, sourceTitle = lagos.sourceTitle, minutes = lagos.minutes),
                ),
            )
        }
    }

    /** From the closing page to the episode after it and back, the view goes with the voice, never back to the last article. */
    @Test
    fun theClosingPageAndTheEpisodeAfterItFollowTheVoiceBetweenThem() {
        val id = editionWithAnEpisodeAfterIt()
        val player = ListenPlayer(speaker, progress, open = { ListenBook.open(repo, it, Readings(db, { Recordings() })) }, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        player.start(id, ListenPosition(2, 0))
        idleUntil { player.state.value.script != null && player.state.value.at.page == 2 }
        compose.setContent {
            ArticlePreviewScreen(loadFile = { repo.byId(id)?.let(repo::fileOf) }, position = 2, title = "Saturday Morning Edition", onBack = {}, onListen = {}, player = player, editionId = id)
        }
        idleUntil { compose.onAllNodesWithText(EpubWriter.END_TITLE, substring = true).fetchSemanticsNodes().isNotEmpty() }
        val lagosShown = { compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }

        player.seek(ListenPosition(3, 0))
        idleUntil { player.state.value.at.page == 3 && compose.onAllNodesWithText("0:30", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(lagosShown())
        player.seek(ListenPosition(2, 0))
        idleUntil { player.state.value.at.page == 2 && compose.onAllNodesWithText("0:30", substring = true).fetchSemanticsNodes().isEmpty() }
        assertFalse(lagosShown())
        assertTrue(compose.onAllNodesWithText(EpubWriter.END_TITLE, substring = true).fetchSemanticsNodes().isNotEmpty())
        player.stop()
    }

    /** Picked from the contents while another article's on screen, the voice's own article comes to the page too. */
    @Test
    fun pickingTheVoicesOwnArticleFromTheContentsTurnsThePage() {
        val id = edition()
        startAndWait(id)
        player.pause()
        compose.setContent { preview(id, position = 1)() }
        idleUntil { compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNode(hasText("The Mathematician Who Counted Knots", substring = true) and hasAnyAncestor(isDialog())).performClick()
        idleUntil { player.state.value.at.page == 0 && compose.onAllNodesWithText("Nobody could say", substring = true).fetchSemanticsNodes().isNotEmpty() }
        player.stop()
    }

    /** Contents left open when Listen stops don't open by themselves when Listen starts again. */
    @Test
    fun contentsLeftOpenWhenListenStopsDontComeBackByThemselves() {
        val id = edition()
        compose.setContent { preview(id, position = 0)() }
        idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()
        idleUntil { player.state.value.script != null }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.onNode(isDialog()).assertExists()
        player.stop()
        idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()
        idleUntil { player.state.value.script != null }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        player.stop()
    }

    /** What Listen says about the page it's reading shows above the article in the preview too. */
    @Test
    fun theNoteForAMissingVoiceShowsInThePreview() {
        speaker.installed = setOf("en")
        val id = edition("Édition", knots.copy(language = "fr"))
        compose.setContent { preview(id, position = 0)() }
        idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()
        idleUntil { compose.onAllNodesWithText("Get a French voice").fetchSemanticsNodes().isNotEmpty() }
        player.stop()
    }

    /** The spans of the paragraph starting [start] drawn underlined: the sentence the voice is on. */
    private fun underlined(start: String): List<String> {
        val nodes = compose.onAllNodes(hasText(start, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        val text = nodes.firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull() ?: return emptyList()
        return text.spanStyles.filter { it.item.textDecoration == androidx.compose.ui.text.style.TextDecoration.Underline }.map { text.text.substring(it.start, it.end) }
    }

    /** Taps [word] where it's drawn in the text of the node [matcher] finds. */
    private fun tapWord(matcher: SemanticsMatcher, word: String) {
        val node = compose.onNode(matcher, useUnmergedTree = true)
        val text = node.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        val layouts = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        // A point the text's own layout puts inside the word, rather than its glyph box: what a tap is measured by.
        val layout = layouts.single()
        val inside = text.indexOf(word).let { it until it + word.length }
        val point = (0 until layout.lineCount).asSequence().flatMap { l ->
            val y = (layout.getLineTop(l) + layout.getLineBottom(l)) / 2
            (0..layout.size.width step 2).asSequence().map { x -> Offset(x.toFloat(), y) }
        }.firstOrNull { layout.getOffsetForPosition(it) in inside && layout.getOffsetForPosition(it) > inside.first }
            ?: error("\"$word\" isn't where a tap can reach it in \"$text\"")
        node.performTouchInput { click(point) }
    }

    // Real text layout: without it every letter is drawn at no width, and a tap can't land on a word.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theArticleIsDrawnWithItsFormattingAndItsLinksGoWhereTheySay() {
        val formatted = knots.copy(
            bodyHtml = "<p>Plain <b>bold</b> and <a href=\"https://example.com/paper\">the paper</a>.</p><ol start=\"3\"><li>Three</li></ol>" +
                "<table><tr><th>Crossings</th><th>Knots</th></tr><tr><td>3</td><td>1</td></tr></table><pre>x = 1\n  y = 2</pre>",
            // Long enough for the book to end it with a "Next" line.
            images = emptyList(), minutes = 9.0,
        )
        val id = edition("Formatted", formatted, lagos)
        startAndWait(id)
        player.pause()
        val opened = mutableListOf<String>()
        val uris = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        val view = playing(id)
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uris) { view() } }
        idleUntil { compose.onAllNodesWithText("Plain bold and the paper.").fetchSemanticsNodes().isNotEmpty() }
        // The byline whole, not only the author the voice reads; bold where it was bold; a list's own numbers; a code block's spacing.
        compose.onNodeWithText("By Erica Klarreich · 9 min read").assertIsDisplayed()
        val plain = compose.onNodeWithText("Plain bold and the paper.", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(listOf("bold"), plain.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { plain.text.substring(it.start, it.end) })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Three"))
        compose.onNodeWithContentDescription("3.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("x = 1\n  y = 2"))

        // Playing, a table row tapped is read from, a row at a time.
        player.play()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Crossings"))
        compose.onNode(hasText("3") and hasClickAction()).performClick()
        idleUntil { speaker.queue.firstOrNull()?.text == "3, 1" }
        player.pause()

        // A web link opens; the "Next" line moves the voice to the next article.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Plain bold", substring = true))
        tapWord(hasText("Plain bold and the paper."), "the paper")
        assertEquals(listOf("https://example.com/paper"), opened)
        // TalkBack has each link as an action of its paragraph.
        val actions = compose.onNodeWithText("Plain bold and the paper.").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Open the paper"), actions.map { it.label })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next:", substring = true))
        val next = compose.onNodeWithText("Next:", substring = true).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertTrue(next.label, next.label.startsWith("Go to Next: Lagos Drivers Built Their Own App"))
        // Paused, it turns the page and leaves the voice; playing, it moves the voice there too.
        tapWord(hasText("Next:", substring = true), "Lagos")
        idleUntil { compose.onAllNodesWithText("The drivers met in a car park.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, player.state.value.at.page)
        // The voice moved there and back brings the page back with it.
        player.seek(ListenPosition(1, 0))
        idleUntil { player.state.value.at.page == 1 }
        compose.waitForIdle()
        player.seek(ListenPosition(0, 0))
        player.play()
        idleUntil { compose.onAllNodesWithText("Plain bold and the paper.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next:", substring = true))
        val playingNext = compose.onNodeWithText("Next:", substring = true).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        compose.runOnIdle { playingNext.action() }
        idleUntil { player.state.value.at.page == 1 }
    }

    /** Heard to the end, the page the voice stopped on says so: there is no "Next" left to follow. */
    @Test
    fun theEditionHeardToTheEndSaysSo() {
        val id = edition("Short", knots.copy(bodyHtml = "<p>One line.</p>", images = emptyList()))
        startAndWait(id)
        compose.setContent(playing(id))
        while (!player.state.value.finished) {
            idleUntil { speaker.queue.isNotEmpty() || player.state.value.finished }
            if (speaker.queue.isNotEmpty()) speaker.sayNext()
        }
        idleUntil { compose.onAllNodesWithText("You've heard the whole edition.").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aLongTableIsFollowedDownToTheRowBeingRead() {
        val rows = (1..80).joinToString("") { "<tr><td>Row $it</td><td>${it * 2}</td></tr>" }
        val id = edition("Long table", knots.copy(bodyHtml = "<p>The counts.</p><table><tr><th>Row</th><th>Twice</th></tr>$rows</table>", images = emptyList()), lagos)
        startAndWait(id)
        player.pause()
        compose.setContent(playing(id))
        idleUntil { compose.onAllNodesWithText("The counts.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Row 70").assertIsNotDisplayed()
        // Source, title, byline, the paragraph, the header row, then rows 1 to 70.
        player.seek(ListenPosition(0, 4 + 70))
        idleUntil { player.state.value.at.line == 74 }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Row 70").assertIsDisplayed() }.isSuccess }
    }

    @Test
    fun theArticleIsReadAtTheArticleTextSize() {
        val id = edition()
        startAndWait(id)
        var size by androidx.compose.runtime.mutableStateOf(com.app.newspaperss.settings.PreviewTextSize.DEFAULT)
        compose.setContent { playing(id, textSize = size)() }
        idleUntil { compose.onAllNodesWithText("The count took a week.").fetchSemanticsNodes().isNotEmpty() }
        fun laidOut(): androidx.compose.ui.text.TextLayoutResult {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("The count took a week.").fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single()
        }
        val usual = laidOut().layoutInput.style.fontSize.value
        size = com.app.newspaperss.settings.PreviewTextSize.LARGEST
        compose.waitForIdle()
        // Largest is 175%, as in the preview.
        assertEquals(usual * 1.75f, laidOut().layoutInput.style.fontSize.value, 0.01f)
    }

    @Test
    fun listeningFromAnArticleStartsAtItsBeginningEvenPartWayThroughAnother() {
        val id = edition()
        progress.set(id, ListenPosition(0, 2))
        listening.start(id, ListenPosition(1, 0))
        idleUntil { player.state.value.script != null }
        assertEquals(ListenPosition(1, 0), player.state.value.at)
        assertEquals("Rest of World", speaker.queue.first().text)
        assertEquals(1, connected)
    }

    @Test
    fun scrollingAwayStopsFollowingUntilAsked() {
        val id = edition()
        startAndWait(id)
        compose.setContent(playing(id))
        idleUntil { compose.onAllNodesWithText("The count took a week.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The count took a week.").performTouchInput { swipeDown() }
        compose.onNodeWithText("Back to where it's reading").performClick()
        assertEquals(0, compose.onAllNodesWithText("Back to where it's reading").fetchSemanticsNodes().size)
    }

    @Test
    fun anArticleWithNoVoiceForItsLanguageOffersToGetOne() {
        speaker.installed = setOf("en")
        val id = edition("Édition", knots.copy(language = "fr"))
        startAndWait(id)
        compose.setContent(playing(id))
        idleUntil { compose.onAllNodesWithText("Get a French voice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Get a French voice").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, started.action)
    }

    @Test
    fun theMediaNotificationsBackAndForwardMoveBySentenceAndItsSecondPairByArticle() {
        val id = edition()
        startAndWait(id)
        val session = SessionPlayer(player)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(3, session.mediaItemCount)
        assertEquals("The Mathematician Who Counted Knots", session.mediaMetadata.title)
        assertEquals("Quanta Magazine", session.mediaMetadata.artist)
        assertTrue(session.playWhenReady)
        // Its own back and forward, as the notification, headphones and a car send them: a sentence.
        session.seekToNext()
        assertEquals(1, player.state.value.at.line)
        assertEquals(0, player.state.value.at.page)
        // Just started, so back goes to the sentence before.
        speaker.startNext()
        session.seekToPrevious()
        assertEquals(0, player.state.value.at.line)
        session.seekForward()
        assertEquals(1, player.state.value.at.line)
        speaker.startNext()
        session.seekBack()
        assertEquals(0, player.state.value.at.line)
        // The second pair: an article.
        session.seekToNextMediaItem()
        idleUntil { player.state.value.at.page == 1 }
        session.seekToPreviousMediaItem()
        idleUntil { player.state.value.at.page == 0 }
        session.seekToDefaultPosition(1)
        idleUntil { player.state.value.at.page == 1 }
        session.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, player.state.value.speed)
        session.pause()
        assertFalse(player.state.value.playing)
        session.play()
        assertTrue(player.state.value.playing)
        session.stop()
        assertFalse(player.state.value.playing)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Player.STATE_READY, session.playbackState)
        session.release()
    }
}
