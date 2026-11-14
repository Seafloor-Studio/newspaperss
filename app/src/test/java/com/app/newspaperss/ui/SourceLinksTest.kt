package com.app.newspaperss.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.insertNew
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.shownAddress
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** The addresses on a source's page: what each opens, when the feed's own shows, and copying where there's no browser. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SourceLinksTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val compose = createComposeRule()
    @get:Rule(order = 2) val viewModels = ViewModels()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val repo = SourceRepository(db)

    private fun visible(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun opened(): String? = shadowOf(app).nextStartedActivity?.also { assertEquals(Intent.ACTION_VIEW, it.action) }?.dataString

    private fun copied(): String? = app.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()

    private fun feed(siteUrl: String? = "https://example.com/", articles: Boolean = true, error: String? = null): Long = runBlocking {
        val id = repo.addFeed("https://example.com/weekly/rss.xml", "Example Weekly")
        db.sources().recordSuccess(id, Instant.now(), null, siteUrl, "")
        if (articles) db.articles().insertNew(listOf(ArticleEntity(sourceId = id, guid = "1", url = "https://example.com/1", title = "An article")))
        error?.let { db.sources().recordFailure(id, Instant.now(), it) }
        id
    }

    private fun show(id: Long, key: String = PublicationEntity.OWN, until: String) {
        val vm = viewModels.add(SourceDetailViewModel(repo, id, flowOf(1), key = key))
        compose.setContent { SourceDetailScreen(vm, onBack = {}) }
        compose.idleUntil { visible(until) }
    }

    @Test
    fun aSourceLinksItsSiteAndItsFeed() {
        show(feed(), until = "Last checked")
        // The feed's own link is marked as one, to TalkBack too.
        compose.onNode(hasText("example.com/weekly/rss.xml") and hasContentDescription("Feed")).assertExists()

        compose.onNodeWithText("example.com").performClick()
        assertEquals("https://example.com/", opened())
        compose.onNodeWithText("example.com/weekly/rss.xml").performClick()
        assertEquals("https://example.com/weekly/rss.xml", opened())
    }

    @Test
    fun aFailingSourceAlsoLinksTheFeedItReads() {
        show(feed(error = "Couldn't reach the site."), until = "Couldn't reach the site.")

        compose.onNodeWithText("example.com/weekly/rss.xml").performClick()
        assertEquals("https://example.com/weekly/rss.xml", opened())
    }

    @Test
    fun aSourceWithNoArticlesYetLinksTheFeedToo() {
        show(feed(articles = false), until = "example.com/weekly/rss.xml")
    }

    /** Until its articles name its site, the site is the feed's front page, and the feed keeps its own link. */
    @Test
    fun withoutASiteTheFrontPageStandsInAndTheFeedKeepsItsLink() {
        show(feed(siteUrl = null), until = "Last checked")

        compose.onNodeWithText("example.com").performClick()
        assertEquals("https://example.com/", opened())
        compose.onNodeWithText("example.com/weekly/rss.xml").performClick()
        assertEquals("https://example.com/weekly/rss.xml", opened())
    }

    /** A feed at its site's root: both links, the same address, so the feed's own is always there. */
    @Test
    fun aFeedAtItsSitesRootStillHasItsOwnLink() {
        val id = runBlocking { repo.addFeed("https://example.com/", "Example Weekly").also { db.sources().recordSuccess(it, Instant.now(), null, null, "") } }
        show(id, until = "Last checked")
        compose.onNode(hasContentDescription("Feed")).assertExists()
    }

    @Test
    fun aSiteAddressThatIsNotTheWebIsNeverOpened() {
        show(feed(siteUrl = "intent://scan/#Intent;scheme=zxing;end"), until = "Last checked")

        compose.onNodeWithText("example.com").performClick()
        assertEquals("the feed's front page stands in", "https://example.com/", opened())
    }

    @Test
    fun withNoBrowserATapCopiesTheAddressAndSaysSo() {
        show(feed(error = "Couldn't reach the site."), until = "Couldn't reach the site.")
        // A real phone with no browser refuses to start the view.
        shadowOf(app).checkActivities(true)

        compose.onNodeWithText("example.com/weekly/rss.xml").performClick()

        assertEquals("https://example.com/weekly/rss.xml", copied())
        compose.idleUntil { visible("No browser here. Copied example.com/weekly/rss.xml") }
    }

    /** The feed line is small text: the space around it still takes the tap, up to 48dp. */
    @Test
    fun aTapJustBelowTheSmallFeedLinkStillOpensIt() {
        show(feed(error = "Couldn't reach the site."), until = "Couldn't reach the site.")

        compose.onNodeWithText("example.com/weekly/rss.xml").performTouchInput {
            // Just inside the 48dp: Robolectric's stand-in font sets the line's own height.
            val spare = (48.dp.toPx() - height) / 2
            click(Offset(centerX, height + spare - 1.dp.toPx()))
        }

        assertEquals("https://example.com/weekly/rss.xml", opened())
    }

    @Test
    fun aSiteAddressShowsWhatItOpensEvenWithHiddenLineBreaks() {
        // Browsers drop the line breaks, so this opens google.com.evil.example.
        show(feed(siteUrl = "https://google.com\n\n\n.evil.example/"), until = "Last checked")

        compose.onNodeWithText("google.com.evil.example").assertExists()
    }

    @Test
    fun aLongPressCopiesInsteadOfOpening() {
        show(feed(), until = "Last checked")

        compose.onNodeWithText("example.com").performTouchInput { longClick() }

        assertNull(opened())
        assertEquals("https://example.com/", copied())
        compose.idleUntil { visible("Copied example.com") }
    }

    @Test
    fun aTtrssFeedsPageLinksItsFeed() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.sources().savePublication(PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://www.quantamagazine.org/feed/", category = "Science", listed = true))
            account
        }
        show(account, key = "5", until = "quantamagazine.org")

        compose.onNodeWithText("quantamagazine.org").performClick()
        assertEquals("its front page, until its articles name its site", "https://www.quantamagazine.org/", opened())
        compose.onNodeWithText("www.quantamagazine.org/feed/").performClick()
        assertEquals("https://www.quantamagazine.org/feed/", opened())
    }

    /** Once its articles have named its site, the feed's page links the site, and the feed below. */
    @Test
    fun aTtrssFeedsPageLinksItsSiteAndItsFeed() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.sources().savePublication(
                PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://api.quantamagazine.org/feed/", siteUrl = "https://www.quantamagazine.org/", listed = true),
            )
            account
        }
        show(account, key = "5", until = "api.quantamagazine.org/feed/")

        compose.onNodeWithText("quantamagazine.org").performClick()
        assertEquals("https://www.quantamagazine.org/", opened())
        compose.onNodeWithText("api.quantamagazine.org/feed/").performClick()
        assertEquals("https://api.quantamagazine.org/feed/", opened())
    }

    @Test
    fun aTtrssFeedsSiteThatIsNotTheWebIsNeverOpened() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.sources().savePublication(
                PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://www.quantamagazine.org/feed/", siteUrl = "intent://scan/#Intent;scheme=zxing;end", listed = true),
            )
            account
        }
        show(account, key = "5", until = "quantamagazine.org")

        compose.onNodeWithText("quantamagazine.org").performClick()
        assertEquals("the feed's front page stands in", "https://www.quantamagazine.org/", opened())
    }

    @Test
    fun aTtrssFeedTtrssCantFetchSaysSoBesideItsLink() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.sources().savePublication(PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://www.quantamagazine.org/feed/", listed = true, serverError = "HTTP Code: 404"))
            account
        }
        show(account, key = "5", until = "tt-rss says: HTTP Code: 404")

        assertTrue(visible("couldn't fetch it last time"))
        // The address to check in tt-rss is the feed's own, a tap away.
        compose.onNodeWithText("www.quantamagazine.org/feed/").performClick()
        assertEquals("https://www.quantamagazine.org/feed/", opened())
    }

    /** A day on, a feed tt-rss has never tried: its updates are most likely off, and its settings there are a tap away. */
    @Test
    fun aFeedTtrssNeverFetchedLinksToItsSettingsThere() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.sources().savePublication(
                PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://www.quantamagazine.org/feed/", listed = true, awaitingFirstFetch = true, watchedSince = Instant.now().minusSeconds(3 * 86_400L)),
            )
            db.sources().setFeedsListed(account, Instant.now())
            account
        }
        show(account, key = "5", until = "Never fetched by tt-rss")

        assertTrue(visible("set its update interval to Default"))
        compose.onNodeWithText("Its settings in tt-rss").performClick()
        assertEquals("https://rss.example.org/tt-rss/prefs.php?tab=feeds&method=editfeed&methodparam=5", opened())
    }

    @Test
    fun aFeedJustAddedInTtrssWaitsForItsFirstFetchWithoutTheLink() {
        val account = runBlocking {
            val account = repo.addTtrss("https://rss.example.org/tt-rss/api/")
            db.sources().savePublication(PublicationEntity(account, "5", title = "Quanta Magazine", feedUrl = "https://www.quantamagazine.org/feed/", listed = true, awaitingFirstFetch = true, watchedSince = Instant.now()))
            db.sources().setFeedsListed(account, Instant.now())
            account
        }
        show(account, key = "5", until = "usually within the hour")

        compose.onNodeWithText("Its settings in tt-rss").assertDoesNotExist()
    }

    @Test
    fun anAddressReadsWithoutItsSchemeOrHiddenReordering() {
        assertEquals("example.org", shownAddress("https://example.org/"))
        assertEquals("example.org/a/", shownAddress("HTTP://example.org/a/"))
        assertEquals("example.org/gpj.exe", shownAddress("https://example.org/‮gpj.exe"))
    }
}
