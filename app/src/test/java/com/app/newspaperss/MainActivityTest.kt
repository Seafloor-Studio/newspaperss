package com.app.newspaperss

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.testutil.writeEpub
import java.io.File
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.FeedMoves
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import java.time.Instant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class MainActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(app)

    @After fun closeWork() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun launchWith(onboarded: Boolean) {
        runBlocking { app.container.settings.update { it.copy(onboarded = onboarded) } }
        ActivityScenario.launch(MainActivity::class.java)
    }

    private fun shows(text: String) = idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun thePlayersNotificationOpensThePlayingScreenAndSoDoesTappingItAgain() {
        runBlocking { app.container.settings.update { it.copy(onboarded = true) } }
        val open = android.content.Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_LISTENING)
        val scenario = ActivityScenario.launch<MainActivity>(open)
        shows("Nothing is playing")
        compose.onNode(hasText("Back") or androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        shows("Sources")
        scenario.onActivity { it.onNewIntent(open) }
        shows("Nothing is playing")
    }

    /** A background update mustn't end the app while a picker or the browser it opened is on top. */
    @Test
    fun anotherAppOpenedFromHereHoldsUpdatesUntilTheReaderIsBack() {
        runBlocking { app.container.settings.update { it.copy(onboarded = true) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        shows("Sources")
        val hold = app.container.updateHold
        scenario.onActivity { it.startActivity(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).setType("*/*")) }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertTrue(hold.held)

        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertFalse(hold.held)
    }

    @Test
    fun aFirstRunStartsWithOnboarding() {
        launchWith(onboarded = false)
        shows("Get started")
    }

    /** A screen inside the tabs clears the status bar once, not again on top of the tabs' own padding. */
    @Test
    fun aScreensTopBarSitsJustBelowTheStatusBar() {
        runBlocking { app.container.settings.update { it.copy(onboarded = true) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        shows("Sources")
        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        shows("About 30 minutes")
        val density = app.resources.displayMetrics.density
        val statusBar = (40 * density).toInt()
        scenario.onActivity { activity ->
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 60))
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity.window.decorView, insets)
        }
        compose.waitForIdle()

        val title = compose.onAllNodes(hasText("Settings")).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
        // Counted twice, the title would sit a whole status bar lower.
        assertTrue("title at $title", title > statusBar && title < 2 * statusBar)
    }

    @Test
    fun aSettingsPageOpensFromTheSummaryAndTheTabGoesBackToIt() {
        launchWith(onboarded = true)
        shows("Sources")
        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        shows("Article text size")
        compose.onNodeWithText("Article text size").performScrollTo().performClick()
        shows("The quiet return of the night train")

        compose.onAllNodes(hasText("Settings") and hasClickAction()).onFirst().performClick()
        shows("About 30 minutes")
        compose.onNodeWithText("The quiet return of the night train").assertDoesNotExist()
    }

    @Test
    fun anUpgradedTtrssReaderWhosePasswordIsGoneIsAskedToSignInFromSources() = try {
        // tt-rss was added before there was a choice, and its login didn't survive (a restore, say).
        runBlocking {
            app.container.sources.addTtrss("https://rss.example.com/tt-rss/api/")
            app.container.settings.update { it.copy(feedsFrom = null) }
            assertEquals(FeedsFrom.SERVER, app.container.settleFeedsFrom())
        }
        launchWith(onboarded = true)
        shows("Sources")
        compose.onAllNodes(hasText("Sources") and hasClickAction()).onFirst().performClick()
        shows("Sign in to your tt-rss")
        compose.onNodeWithText("Sign in").performClick()
        shows("Where your feeds live")
        shows("Sign in again")
    } finally {
        runBlocking { app.container.settings.update { it.copy(feedsFrom = null) } }
    }

    @Test
    fun anEarlierEditionOpensFromTodayWithItsContents() {
        runBlocking {
            val db = app.container.db
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
            val article = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "a", url = "https://example.com/a", title = "The older story", state = ArticleState.DELIVERED))
            val older = db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = Instant.parse("2026-10-12T06:00:00Z"), status = EditionStatus.DELIVERED, articleCount = 1, minutes = 4.0))
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = older, articleId = article, position = 0, title = "The older story", sourceTitle = "Example News", minutes = 4.0)))
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = Instant.parse("2026-10-13T06:00:00Z"), status = EditionStatus.DELIVERED))
        }
        launchWith(onboarded = true)
        shows("Earlier editions")
        // Only the edition page lists an earlier edition's articles; Today shows its title and count.
        compose.onNodeWithText("The older story").assertDoesNotExist()
        // A lazy list: the row isn't composed until it's scrolled to.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Monday Morning Edition"))
        compose.onNodeWithText("Monday Morning Edition").performClick()
        shows("The older story")
    }

    @Test
    fun onceOnboardedTheAppOpensOnToday() {
        launchWith(onboarded = true)
        shows("Make an edition now")
        shows("Sources")
    }

    /**
     * The way back from the paper: an article in an edition opens the page of the feed it came
     * from, which is where its cap, its article text, leaving it out and marking it unread are. A
     * tt-rss article goes to its feed's page within the account, not to the account.
     */
    @Test
    fun goToTheSourceOpensTheTtrssFeedsPageTheArticleCameFrom() {
        val story = "The quiet return of the night train"
        runBlocking {
            val db = app.container.db
            val account = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = SourceRepository.TTRSS_TITLE))
            db.sources().savePublication(PublicationEntity(account, "7", title = "Quanta Magazine", feedUrl = "https://quanta.example/feed", category = "Science", listed = true))
            val article = db.articles().insertIgnoring(
                ArticleEntity(sourceId = account, guid = "ttrss:7", url = "https://quanta.example/night-train", title = story, state = ArticleState.DELIVERED, originId = "7", originTitle = "Quanta Magazine"),
            )
            val edition = db.editions().insert(
                EditionEntity(title = "Monday Morning Edition", createdAt = Instant.parse("2026-10-12T06:00:00Z"), status = EditionStatus.DELIVERED, fileName = "e.epub", articleCount = 1, minutes = 4.0),
            )
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = article, position = 0, title = story, sourceTitle = "Quanta Magazine", minutes = 4.0)))
            File(app.filesDir, "editions").apply { mkdirs() }.resolve("e.epub")
                .writeEpub(listOf(EditionArticle(title = story, sourceTitle = "Quanta Magazine", url = "https://quanta.example/night-train", bodyHtml = "<p>Sleeper services are coming back.</p>", minutes = 4.0)))
        }
        launchWith(onboarded = true)
        shows("Monday Morning Edition")

        compose.onAllNodesWithText("Monday Morning Edition").onFirst().performClick()
        shows(story)
        compose.onAllNodesWithText(story).onFirst().performClick()
        idleUntil { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Quanta Magazine").performClick()

        shows("In your tt-rss \u00b7 category Science")
    }

    /**
     * A phone feed moved to tt-rss is kept, paused, while editions still take from it, and Sources
     * hides it: its settings are read from the tt-rss feed it became, and its page's Resume would
     * start it fetching beside that feed. So the paper doesn't offer it either.
     */
    @Test
    fun aFeedOnItsWayToTtrssIsNotOfferedAsAPageToGoTo() {
        val wired = EditionContent(
            entry = EditionArticleEntity(editionId = 1, articleId = 7, position = 0, title = "A story", sourceTitle = "Wired", minutes = 4.0),
            state = ArticleState.DELIVERED,
            sourceId = 5,
            sourceKind = SourceKind.FEED,
            sourceName = "Wired",
            sourcePaused = true,
        )
        val moving = FeedMoves.State(retiring = mapOf(5L to true))

        assertNull(goTo(listOf(wired), moving, 0))
        assertEquals("resumed from its page, it's a phone feed again", "Wired", goTo(listOf(wired.copy(sourcePaused = false)), moving, 0)?.title)
        assertEquals("paused by the reader, not moved: its page is where Resume lives", "Wired", goTo(listOf(wired), FeedMoves.State(), 0)?.title)
    }

    @Test
    fun goToTheSourceOnASavedLinkOpensTheReadingList() {
        val story = "On walking"
        runBlocking {
            val db = app.container.db
            val list = db.sources().insert(SourceEntity(kind = SourceKind.READING_LIST, url = "newspaperss://reading-list", title = "Your reading list"))
            val article = db.articles().insertIgnoring(
                ArticleEntity(sourceId = list, guid = "s", url = "https://example.org/essays/on-walking", title = story, state = ArticleState.DELIVERED),
            )
            val edition = db.editions().insert(
                EditionEntity(title = "Monday Morning Edition", createdAt = Instant.parse("2026-10-12T06:00:00Z"), status = EditionStatus.DELIVERED, fileName = "r.epub", articleCount = 1, minutes = 4.0),
            )
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = article, position = 0, title = story, sourceTitle = "Your reading list", minutes = 4.0)))
            File(app.filesDir, "editions").apply { mkdirs() }.resolve("r.epub")
                .writeEpub(listOf(EditionArticle(title = story, sourceTitle = "Your reading list", url = "https://example.org/essays/on-walking", bodyHtml = "<p>x</p>", minutes = 4.0)))
        }
        launchWith(onboarded = true)
        shows("Monday Morning Edition")

        compose.onAllNodesWithText("Monday Morning Edition").onFirst().performClick()
        shows(story)
        compose.onAllNodesWithText(story).onFirst().performClick()
        idleUntil { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        // The reading list has a screen of its own, not a source page.
        compose.onNodeWithText("Go to Your reading list").performClick()

        shows("Paste a link")
    }
}
