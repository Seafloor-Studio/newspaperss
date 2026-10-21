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

    private fun saved() = runBlocking { app.container.db.articles().candidates() }
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
        tap("Save to your reading list")
        idleUntil { saved().isNotEmpty() }
        val article = saved().single()
        assertEquals("https://a.example/story?ref=share", article.url)
        assertEquals("A great piece", article.title)
        toldOnly("Saved for your next edition")
        // Nothing was looked up: saving is all that was asked.
        assertTrue(feeds().isEmpty())
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
        toldOnly("No feed found at plain.example. Saved the page for your next edition instead.")
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
    }

    @Test
    fun theQuestionSurvivesRotationAndOneChoiceSavesOnce() {
        val scenario = share("https://a.example/story")
        shows("Both")
        scenario.recreate()
        tap("Save to your reading list")
        idleUntil { saved().isNotEmpty() }
        assertEquals(1, saved().size)
    }

    @Test
    fun cancellingDoesNothing() {
        val scenario = share("https://a.example/story")
        tap("Cancel")
        var finishing = false
        idleUntil { scenario.onActivity { finishing = it.isFinishing }; finishing }
        assertTrue(saved().isEmpty())
    }

    @Test
    fun textWithoutALinkSavesNothing() {
        share("just words").close()
        assertEquals("There's no link to save in that.", ShadowToast.getTextOfLatestToast())
        assertEquals(0, saved().size)
    }
}
