package com.app.newspaperss

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.rss
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** A link shared into the app: saved, its site subscribed to, or both, as the reader picks. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ShareActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Before fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(app)

    @After fun closeWork() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun share(text: String, subject: String? = null): ActivityScenario<ShareActivity> {
        val intent = Intent(app, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        return ActivityScenario.launch(intent)
    }

    private fun shows(text: String) = idleUntil { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun tap(text: String) {
        shows(text)
        compose.onNodeWithText(text).performClick()
    }

    /** The choices' labels, top to bottom as drawn. */
    private fun order(): List<String> {
        shows("Both")
        return listOf("Save to your reading list", "Subscribe to this site", "Both")
            .sortedBy { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
    }

    private fun saved() = runBlocking { app.container.db.articles().buildCandidates() }
    // A choice is acted on in the app's scope after the screen has gone: waits for it to be done,
    // so a check that nothing happened isn't made before anything could.
    private fun settled() = idleUntil { app.container.appScope.coroutineContext[Job]!!.children.none { it.isActive } }
    private fun feeds() = runBlocking { app.container.db.sources().ofKind(SourceKind.FEED) }
    private fun toldOnly(text: String) {
        runCatching { idleUntil(timeoutMs = 10_000) { ShadowToast.getTextOfLatestToast() == text } }
        assertEquals(text, ShadowToast.getTextOfLatestToast())
    }

    /** A news site whose front page advertises its science section's feed before its own. */
    private fun newsSite() {
        app.http.page(
            "https://news.example/",
            """<html><head><link rel="alternate" type="application/rss+xml" title="Science" href="/science/feed">""" +
                """<link rel="alternate" type="application/rss+xml" title="Example News" href="/feed"></head></html>""",
        )
        app.http.page("https://news.example/science/feed", rss("Science", "s1" to "A comet"))
        app.http.page("https://news.example/feed", rss("Example News", "n1" to "A story"))
        app.http.page("https://news.example/2026/10/a-story", """<html><head><link rel="alternate" type="application/rss+xml" title="Example News" href="/feed"></head></html>""")
    }

    @Test
    fun aSharedArticleLeadsWithSaveAndLandsOnTheReadingListWithItsTitle() {
        share("Great piece https://a.example/story?ref=share", subject = "A great piece")
        shows("A great piece")
        assertEquals(listOf("Save to your reading list", "Subscribe to this site", "Both"), order())
        val fetched = mutableListOf<String>()
        app.http.beforeResponse = { fetched += it }
        tap("Save to your reading list")
        idleUntil { saved().isNotEmpty() }
        settled()
        val article = saved().single()
        assertEquals("https://a.example/story?ref=share", article.url)
        assertEquals("A great piece", article.title)
        toldOnly("Saved for your next edition")
        // Nothing was looked up: saving is all that was asked.
        assertEquals(emptyList<String>(), fetched)
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun aSubjectThatIsJustTheUrlIsntUsedAsTheTitle() {
        share("https://a.example/post", subject = "https://a.example/post")
        tap("Save to your reading list")
        idleUntil { saved().isNotEmpty() }
        assertEquals("", saved().single().title)
    }

    @Test
    fun aSharedFrontPageLeadsWithSubscribeAndAddsTheWholeSitesFeed() {
        newsSite()
        share("https://news.example/")
        assertEquals(listOf("Subscribe to this site", "Save to your reading list", "Both"), order())
        tap("Subscribe to this site")
        idleUntil { feeds().isNotEmpty() }
        assertEquals("https://news.example/feed", feeds().single().url)
        toldOnly("Added Example News to your sites.")
        assertTrue(saved().isEmpty())
    }

    @Test
    fun subscribingToASiteWithNoFeedSavesThePageInsteadAndSaysSo() {
        app.http.page("https://plain.example/notes/one", "<html><head><title>One</title></head><body>Words</body></html>")
        share("https://plain.example/notes/one")
        tap("Subscribe to this site")
        idleUntil { saved().isNotEmpty() }
        assertEquals("https://plain.example/notes/one", saved().single().url)
        toldOnly("No feed found at https://plain.example/notes/one. Saved the page for your next edition instead.")
        assertTrue(feeds().isEmpty())
    }

    @Test
    fun bothOnASiteAlreadyFollowedSaysSoAndStillSaves() {
        newsSite()
        runBlocking { app.container.sources.addFeed("http://www.news.example/feed/", "Example News") }
        share("https://news.example/2026/10/a-story", subject = "A story")
        tap("Both")
        idleUntil { saved().isNotEmpty() }
        toldOnly("Example News is already in your sites. Saved for your next edition.")
        assertEquals(1, feeds().size)
    }

    @Test
    fun inTheServerSetupSubscribeGoesToTtrssInTheCategoryLastPicked() {
        newsSite()
        val server = FakeTtrss(app.http)
        server.categories[4] = "Science"
        server.categories[5] = "News"
        runBlocking {
            assertNull(app.container.ttrss.connect("rss.example.com/tt-rss", server.user, server.password))
            app.container.settings.update { it.copy(feedsFrom = FeedsFrom.SERVER, lastCategoryId = 5) }
        }
        share("https://news.example/")
        tap("Subscribe to this site")
        idleUntil { server.subscribed.isNotEmpty() }
        assertEquals(listOf("https://news.example/feed" to 5), server.subscribed)
        toldOnly("Added Example News to your tt-rss, in News.")
        // In tt-rss, not on the phone as well.
        assertTrue(feeds().isEmpty())
        // Sources hears of it too: with Undo, and for an Add dialog asking about the same feed meanwhile.
        assertEquals(1, app.container.ttrssSubscriptions.results.value.size)
    }

    private fun signedIn(): FakeTtrss {
        val server = FakeTtrss(app.http)
        runBlocking {
            assertNull(app.container.ttrss.connect("rss.example.com/tt-rss", server.user, server.password))
            app.container.settings.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
        }
        return server
    }

    @Test
    fun aFeedTtrssRefusesIsSavedInstead() {
        newsSite()
        val server = signedIn()
        server.refuse["https://news.example/feed"] = 5
        share("https://news.example/")
        tap("Subscribe to this site")
        idleUntil { saved().isNotEmpty() }
        assertEquals("https://news.example/", saved().single().url)
        idleUntil { ShadowToast.getTextOfLatestToast().startsWith("tt-rss") }
        val told = ShadowToast.getTextOfLatestToast()
        assertTrue(told, told.startsWith("tt-rss didn't add Example News.") && told.endsWith("Saved the page for your next edition instead."))
        // The toast said it all: Sources doesn't say it again later.
        assertTrue(app.container.ttrssSubscriptions.results.value.isEmpty())
    }

    @Test
    fun anAddDialogAskingAboutTheSameFeedMeanwhileGetsTtrssAnswer() {
        newsSite()
        val server = signedIn()
        server.refuse["https://news.example/feed"] = 5
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        server.subscribeGate = gate
        share("https://news.example/")
        tap("Subscribe to this site")
        idleUntil { server.subscribed.isNotEmpty() }
        // Sources' Add, asking while the share waits: it can't start, and waits for the share's answer.
        val subscriptions = app.container.ttrssSubscriptions
        val request = com.app.newspaperss.data.TtrssSubscriptions.Request("https://news.example/feed", "Example News", com.app.newspaperss.core.ttrss.TtrssCategory(0, "Uncategorized"))
        assertTrue(!subscriptions.subscribe(request))
        gate.complete(Unit)
        idleUntil { subscriptions.results.value.isNotEmpty() }
        val outcome = subscriptions.results.value.single().outcome as com.app.newspaperss.data.TtrssSubscriptions.Outcome.Subscribed
        assertTrue(outcome.answer is com.app.newspaperss.data.TtrssRepository.Subscribed.Failed)
    }

    @Test
    fun bothSavesThePageBeforeWaitingOnTtrss() {
        newsSite()
        val server = signedIn()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        server.subscribeGate = gate
        share("https://news.example/2026/10/a-story")
        tap("Both")
        idleUntil { server.subscribed.isNotEmpty() }
        // tt-rss hasn't answered, and the page is safe already.
        assertEquals(1, saved().size)
        gate.complete(Unit)
        toldOnly("Added Example News to your tt-rss, in Uncategorized. Saved for your next edition.")
    }

    @Test
    fun theQuestionSurvivesRotationAndOneChoiceSavesOnce() {
        val scenario = share("https://a.example/story")
        shows("Both")
        scenario.recreate()
        tap("Save to your reading list")
        idleUntil { saved().isNotEmpty() }
        settled()
        assertEquals(1, saved().size)
        // Saved twice would still be one row: the second toast is what shows it.
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun cancellingDoesNothing() {
        val scenario = share("https://a.example/story")
        tap("Cancel")
        var finishing = false
        idleUntil { scenario.onActivity { finishing = it.isFinishing }; finishing }
        settled()
        assertTrue(saved().isEmpty())
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun textWithoutALinkSavesNothing() {
        share("just words").close()
        assertEquals("There's no link to save in that.", ShadowToast.getTextOfLatestToast())
        assertEquals(0, saved().size)
    }
}
