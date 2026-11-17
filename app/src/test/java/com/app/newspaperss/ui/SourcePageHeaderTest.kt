package com.app.newspaperss.ui

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.flow.first
import com.app.newspaperss.data.PublicationEntity
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

    /** Delivers [ids] in an edition at [at], as sending one does, the other sources' copies of their links included. */
    private fun deliver(ids: List<Long>, at: Instant = sunday, title: String = "Sunday Morning Edition") = runBlocking {
        val edition = db.editions().insert(EditionEntity(title = title, createdAt = at, status = EditionStatus.DELIVERED, deliveredAt = at))
        db.editions().insertArticles(ids.mapIndexed { i, id -> EditionArticleEntity(editionId = edition, articleId = id, position = i, title = "t", sourceTitle = "s", minutes = 1.0) })
        db.articles().setDelivered(ids)
        db.articles().rememberDelivered(ids, at)
        db.articles().deliverCopies(ids)
    }

    private fun unsent(ids: List<Long>, title: String = "Tuesday Morning Edition") = runBlocking {
        db.articles().setState(ids, ArticleState.IN_EDITION)
        val edition = db.editions().insert(EditionEntity(title = title, createdAt = clock.instant(), status = EditionStatus.READY))
        db.editions().insertArticles(ids.mapIndexed { i, id -> EditionArticleEntity(editionId = edition, articleId = id, position = i, title = "t", sourceTitle = "s", minutes = 1.0) })
    }

    private fun paper(id: Long, key: String = "") = runBlocking { repo.observePaper(id, key).first() }

    private fun ids(source: Long) = runBlocking { db.articles().allForSource(source).sortedBy { it.guid }.map { it.id } }

    /**
     * What went out is what was in the source's delivered editions: not another source's copy of
     * the same link that delivery used up, and not changed by the reader marking it unread later.
     */
    @Test
    fun onlyTheSourcesOwnEditionsCountAsSentAndMarksDontChangeThem() {
        val a = feed(articles = 1)
        val b = runBlocking {
            val b = repo.addFeed("https://example.org/feed", "Other")
            db.articles().insertNew(listOf(ArticleEntity(sourceId = b, guid = "1", url = "https://example.com/1", title = "The same story")))
            b
        }
        deliver(ids(a))

        assertEquals(listOf(SentBatch(sunday, 1)), paper(a).sent)
        assertEquals("the copy was used up, not sent from here", ArticleState.DELIVERED, runBlocking { db.articles().allForSource(b).single().state })
        assertEquals(emptyList<SentBatch>(), paper(b).sent)

        runBlocking { repo.toggleRead(ids(a).single()) }
        assertEquals(ArticleState.NEW, runBlocking { db.articles().allForSource(a).single().state })
        assertEquals("it still went out on Sunday", listOf(SentBatch(sunday, 1)), paper(a).sent)
    }

    /** Marking an unsent edition's articles read doesn't take them out of its book, so the count stays. */
    @Test
    fun theUnsentEditionsCountIsWhatsInItsBook() {
        val id = feed(articles = 2)
        unsent(ids(id))
        runBlocking { repo.markRead(ids(id).take(1)) }
        assertEquals(ArticleState.SKIPPED, runBlocking { db.articles().byId(ids(id).first())!!.state })
        assertEquals(2, paper(id).unsent?.count)
    }

    /** tt-rss can give a headline no link; it still went out in the edition. Another feed of the account's doesn't count it. */
    @Test
    fun aTtrssFeedsArticleWithNoLinkCountsWhenSent() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.articles().insertNew(
                listOf(
                    ArticleEntity(sourceId = account, guid = "ttrss:1", url = "", title = "A headline", originId = "5", originTitle = "Quanta"),
                    ArticleEntity(sourceId = account, guid = "ttrss:2", url = "https://example.org/2", title = "Another", originId = "6", originTitle = "Other"),
                ),
            )
            account
        }
        deliver(runBlocking { db.articles().allForSource(account).filter { it.originId == "5" }.map { it.id } })
        assertEquals(listOf(SentBatch(sunday, 1)), paper(account, "5").sent)
        assertEquals(emptyList<SentBatch>(), paper(account, "6").sent)
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
        deliver(listOf(runBlocking { db.articles().allForSource(id).single { it.guid == "40" }.id }))
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

    /**
     * A second tap that lands after the source has started again but before the button has gone
     * mustn't pause it again: Start again only ever starts.
     */
    @Test
    fun aSecondTapOnStartAgainDoesNotPauseItAgain() {
        val id = feed(articles = 1)
        runBlocking { repo.setPaused(id, true) }
        val vm = show(id, until = "Paused · nothing new comes in")
        val tap = compose.onNodeWithText("Start again").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // No frames from here, so the button stays while the change reaches the page's state.
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { tap() }
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.detail.value?.source?.paused != false && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(false, vm.detail.value?.source?.paused)

        compose.runOnUiThread { tap() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        Thread.sleep(200)
        compose.waitForIdle()
        assertFalse(runBlocking { db.sources().byId(id)!!.paused })
    }

    /** A tt-rss account with one feed, "5", which has an article, and [change] made to them. */
    private fun ttrssFeed(change: suspend (Long) -> Unit = {}): Long = runBlocking {
        val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
        db.sources().savePublication(PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://www.quantamagazine.org/feed/", listed = true))
        db.articles().insertNew(listOf(ArticleEntity(sourceId = account, guid = "ttrss:1", url = "https://example.org/1", title = "An article", originId = "5", originTitle = "Quanta Magazine")))
        db.sources().recordSuccess(account, clock.instant(), null, null, SourceRepository.TTRSS_TITLE)
        change(account)
        account
    }

    private fun showFeed(account: Long, until: String) {
        val vm = viewModels.add(SourceDetailViewModel(repo, account, flowOf(1), key = "5"))
        compose.setContent { atClock { SourceDetailScreen(vm, onBack = {}) } }
        compose.idleUntil { visible(until) }
    }

    /** Editions leave a paused account's feeds out, so a feed's page says so, and starts the account again. */
    @Test
    fun aFeedOfAPausedAccountSaysSoAndStartsItAgain() {
        val account = ttrssFeed { repo.setPaused(it, true) }
        showFeed(account, until = "Your tt-rss is paused · nothing new comes in")
        assertFalse(visible("Waiting for your next edition"))

        compose.onNodeWithText("Start again").performClick()

        compose.idleUntil { runBlocking { db.sources().byId(account)!!.paused } == false }
        compose.idleUntil { visible("Waiting for your next edition") }
    }

    /** With the account failing, nothing comes from any of its feeds: each feed's page says the account's problem. */
    @Test
    fun aFeedOfAFailingAccountShowsTheAccountsProblem() {
        val account = ttrssFeed { db.sources().recordFailure(it, clock.instant(), "Couldn't reach tt-rss.") }
        showFeed(account, until = "Couldn't reach tt-rss.")
        compose.onNode(hasContentDescription("Problem")).assertExists()
        assertTrue(visible("Nothing new comes from it until this clears."))
    }

    /** Left out is the feed's own choice and means it isn't fetched at all, so it wins over the account's state. */
    @Test
    fun aLeftOutFeedSaysSoEvenUnderAPausedAccount() {
        val account = ttrssFeed {
            repo.setPaused(it, true)
            db.sources().savePublication(db.sources().publication(it, "5")!!.copy(leftOut = true))
        }
        showFeed(account, until = "Left out of your paper")
        assertFalse(visible("Your tt-rss is paused"))
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
        // Its links expire by age like a feed's, and also past its newest 12.
        assertTrue(visible("An article waits 7 days for a place, then it's not picked. The list keeps its newest 12."))
    }
}
