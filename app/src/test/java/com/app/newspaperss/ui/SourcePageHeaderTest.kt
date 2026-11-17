package com.app.newspaperss.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.lists.CuratedLists
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.PaperRecord
import com.app.newspaperss.data.SentBatch
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.UnsentCount
import com.app.newspaperss.data.insertNew
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.components.LocalClock
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.paperLine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** The top of a source's page: what it has done for the paper, its problems and their fixes, and the legend under its list. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class, qualifiers = "w411dp-h1600dp")
class SourcePageHeaderTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val viewModels = ViewModels()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    // A Tuesday morning.
    private val clock = Clock.fixed(Instant.parse("2026-09-29T07:15:00Z"), ZoneOffset.UTC)
    private val repo = SourceRepository(db, clock)
    private val sunday = Instant.parse("2026-09-27T08:00:00Z")

    @Composable
    private fun atClock(content: @Composable () -> Unit) = CompositionLocalProvider(LocalClock provides clock, content = content)

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private var shown by mutableStateOf<SourceDetailViewModel?>(null)

    /** Shows [id]'s page, in place of any shown before. */
    private fun show(id: Long, until: String): SourceDetailViewModel {
        val vm = viewModels.add(SourceDetailViewModel(repo, id, flowOf(1)))
        if (shown == null) compose.setContent { atClock { shown?.let { key(it) { SourceDetailScreen(it, onBack = {}) } } } }
        shown = vm
        compose.idleUntil { visible(until) }
        return vm
    }

    private fun feed(articles: Int = 0): Long = runBlocking {
        val id = repo.addFeed("https://example.com/feed", "Example")
        db.articles().insertNew(
            (1..articles).map { i ->
                ArticleEntity(sourceId = id, guid = "$i", url = "https://example.com/$i", title = "Article $i", discoveredAt = clock.instant().minus(Duration.ofMinutes(10L * i)))
            },
        )
        db.sources().recordSuccess(id, clock.instant(), null, "https://example.com", "")
        id
    }

    private fun line(record: PaperRecord, hasArticles: Boolean = true) = paperLine(record, hasArticles, clock.instant(), ZoneOffset.UTC, Locale.US)

    /** The first that applies: an unsent edition, then the last delivery, then waiting, then nothing. Never a count of what waits. */
    @Test
    fun thePaperLineSaysWhatTheSourceDidForThePaper() {
        assertEquals("No articles yet", line(PaperRecord(), hasArticles = false))
        assertEquals("Nothing sent from here yet", line(PaperRecord()))
        assertEquals("Waiting for your next edition", line(PaperRecord(waiting = true)))

        // Two editions on Sunday are one day's paper; Friday's isn't counted with them.
        val sent = listOf(SentBatch(sunday.plus(Duration.ofHours(10)), 1), SentBatch(sunday, 1), SentBatch(sunday.minus(Duration.ofDays(2)), 3))
        assertEquals("Last sent Sunday · 2 articles", line(PaperRecord(sent = sent, waiting = true)))
        assertEquals("Last sent today · 1 article", line(PaperRecord(sent = listOf(SentBatch(clock.instant().minusSeconds(60), 1)))))
        assertEquals("Last sent Sep 4 · 1 article", line(PaperRecord(sent = listOf(SentBatch(Instant.parse("2026-09-04T08:00:00Z"), 1)))))

        assertEquals(
            "2 articles in Tuesday's edition, not sent yet",
            line(PaperRecord(sent = sent, unsent = UnsentCount("Tuesday Morning Edition, Sep 29", 2), waiting = true)),
        )
    }

    /**
     * On a busy feed held to one an edition, what went out can be older than every listed article:
     * the line still says it was sent, not "Waiting".
     */
    @Test
    fun aBusyFeedsLastDeliveryCountsEvenPastTheListedArticles() {
        val id = feed(articles = 40)
        runBlocking {
            val oldest = db.articles().allForSource(id).single { it.guid == "40" }.id
            db.articles().setDelivered(listOf(oldest))
            db.articles().rememberDelivered(listOf(oldest), sunday)
        }
        val vm = show(id, until = "Article 1")

        compose.idleUntil { visible("Last sent Sunday · 1 article") }
        assertTrue("it isn't among the listed", vm.detail.value!!.articles.none { it.state == ArticleState.DELIVERED })
        assertFalse(visible("Waiting for your next edition"))
    }

    @Test
    fun anArticleInAnUnsentEditionLeadsThePage() {
        val id = feed(articles = 2)
        runBlocking {
            val article = db.articles().allForSource(id).single { it.guid == "2" }.id
            db.articles().setState(listOf(article), ArticleState.IN_EDITION)
            val edition = db.editions().insert(EditionEntity(title = "Thursday Morning Edition", status = EditionStatus.READY))
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = article, position = 0, title = "Article 2", sourceTitle = "Example", minutes = 1.0)))
        }
        show(id, until = "1 article in Thursday's edition, not sent yet")
    }

    /** A problem has a mark and the words, not only red, so it reads in grayscale; once cleared, the mark goes. */
    @Test
    fun aFailingSourceIsMarkedAsAProblemUntilItRecovers() {
        val id = feed()
        runBlocking {
            db.sources().recordFailure(id, clock.instant().minus(Duration.ofDays(3)), "Couldn't reach the site.")
            db.sources().recordFailure(id, clock.instant(), "Couldn't reach the site.")
        }
        show(id, until = "Couldn't reach the site.")
        compose.onNode(hasContentDescription("Problem")).assertExists()
        assertTrue(visible("Nothing new comes from it until this clears."))
        assertTrue("with nothing to list, the list says so", visible("No articles from here yet."))

        runBlocking { db.sources().recordSuccess(id, clock.instant(), null, "https://example.com", "") }

        compose.idleUntil { visible("No articles yet") }
        compose.onNode(hasContentDescription("Problem")).assertDoesNotExist()
        assertFalse("the header said it already", visible("No articles from here yet."))
    }

    @Test
    fun startAgainUnpausesFromThePage() {
        val id = feed(articles = 1)
        runBlocking { repo.setPaused(id, true) }
        show(id, until = "Paused · nothing new comes in")

        compose.onNodeWithText("Start again").performClick()

        compose.idleUntil { runBlocking { db.sources().byId(id)!!.paused } == false }
        compose.idleUntil { !visible("Start again") && !visible("Paused") }
    }

    /** The legend sits under the last row and says why an article ends up "Not picked", which depends on the kind of source. */
    @Test
    fun theLegendUnderTheListSaysHowLongArticlesWait() {
        val id = feed(articles = 3)
        show(id, until = "Article 3")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tap a mark to change it", substring = true))
        assertTrue(visible("An article waits 7 days for a place, then it's not picked."))
        val legend = compose.onNodeWithText("Tap a mark to change it", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue("after the last row", legend.top >= compose.onNodeWithText("Article 3").fetchSemanticsNode().boundsInRoot.bottom)

        val list = runBlocking {
            val list = repo.addList(CuratedLists.all.first())
            db.articles().insertNew(listOf(ArticleEntity(sourceId = list, guid = "pick", url = "https://example.org/pick", title = "A pick")))
            db.sources().recordSuccess(list, clock.instant(), null, null, "")
            list
        }
        show(list, until = "A pick")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tap a mark to change it", substring = true))
        assertTrue(visible("The list keeps its newest 12."))
        assertFalse(visible("waits 7 days"))
    }
}
