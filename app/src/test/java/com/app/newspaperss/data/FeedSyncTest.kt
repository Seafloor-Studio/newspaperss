package com.app.newspaperss.data

import com.app.newspaperss.core.episodes.EpisodeKind
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.rss
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FeedSyncTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private var now = Instant.parse("2026-09-29T06:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val sync = FeedSync(db, http, clock, keepFor = Duration.ofDays(7))
    private val repo = SourceRepository(db)
    private val url = "https://example.com/feed"

    @Test
    fun newArticlesAreAddedOnceAndThePlaceholderTitleIsReplaced() = runTest {
        val id = repo.addFeed(url, title = null)
        assertEquals("example.com", db.sources().byId(id)!!.title)
        http.page(url, rss("Example Blog", "1" to "One", "2" to "Two"))

        assertEquals(SyncResult(newArticles = 2, failedSources = 0, sources = 1), sync.syncAll())
        http.page(url, rss("Example Blog", "2" to "Two", "3" to "Three"))
        assertEquals(1, sync.syncAll().newArticles)

        assertEquals(setOf("One", "Two", "Three"), db.articles().candidates().map { it.title }.toSet())
        assertEquals("Example Blog", db.sources().byId(id)!!.title)
    }

    @Test
    fun aTitleTheReaderChoseIsKept() = runTest {
        val id = repo.addFeed(url, title = "My name for it")
        http.page(url, rss("Example Blog", "1" to "One"))
        sync.syncAll()
        assertEquals("My name for it", db.sources().byId(id)!!.title)
    }

    @Test
    fun failuresAreRecordedAndClearedByTheNextSuccess() = runTest {
        val id = repo.addFeed(url, "Blog")
        http.page(url, "oops", code = 403)
        assertEquals(1, sync.syncAll().failedSources)
        // The same plain words as adding the site, not a bare "error 403".
        assertEquals("The site turned NewspapeRSS away (error 403). Some sites block apps: try again later.", db.sources().byId(id)!!.lastError)
        val firstFailure = now

        now = now.plus(Duration.ofDays(1))
        http.page(url, "<html>not a feed</html>")
        sync.syncAll()
        assertEquals("a run of failures keeps the day it started", firstFailure, db.sources().byId(id)!!.failingSince)
        assertEquals("We can't get new articles from this site any more. It may have moved; try adding it again.", db.sources().byId(id)!!.lastError)

        http.unreachable += url
        sync.syncAll()
        assertEquals("Couldn't reach the site.", db.sources().byId(id)!!.lastError)

        http.unreachable.clear()
        http.page(url, rss("Blog", "1" to "One"))
        sync.syncAll()
        assertNull(db.sources().byId(id)!!.lastError)
        assertNull(db.sources().byId(id)!!.failingSince)
    }

    /** Shaped like SiteGround's: a 202 whose page only sends the browser on to a captcha. */
    @Test
    fun aBotCheckInsteadOfTheFeedSaysTheSiteTurnedTheAppAway() = runTest {
        val id = repo.addFeed(url, "Blog")
        http.page(url, "<html><head><meta http-equiv=\"refresh\" content=\"0;/.well-known/sgcaptcha/?r=%2Ffeed%2F\"></meta></head></html>", code = 202)
        assertEquals(1, sync.syncAll().failedSources)
        // Not "it may have moved": adding it again would meet the same check.
        assertEquals("The site turned NewspapeRSS away with a bot check. Some sites block apps: try again later.", db.sources().byId(id)!!.lastError)

        // A feed whose post is about bot checks is still a feed.
        http.page(url, rss("Blog", "1" to "What Cloudflare's /cdn-cgi/challenge-platform/ does"))
        assertEquals(1, sync.syncAll().newArticles)
        assertNull(db.sources().byId(id)!!.lastError)

        // Even one the quick "is it a feed?" look misses: a JSON Feed written with its keys sorted.
        val post = "<p>${"A wall of words. ".repeat(150)}</p>"
        http.page(url, """{"items":[{"content_html":"$post","id":"2","title":"Enable JavaScript and cookies to continue, and other walls","url":"https://example.com/2"}],"title":"Blog","version":"https://jsonfeed.org/version/1.1"}""")
        assertEquals(1, sync.syncAll().newArticles)
        assertNull(db.sources().byId(id)!!.lastError)
    }

    /** Android reads a row through a 2 MB window, so a feed's text is kept well under it. */
    @Test
    fun feedTextTooBigToReadBackIsntKept() = runTest {
        repo.addFeed(url, "Blog")
        val words = "<p>${"Words of the post. ".repeat(1_000)}</p>"
        val picture = "<img src=\"data:image/png;base64,${"A".repeat(600_000)}\">"
        fun item(guid: String, html: String) = "<item><guid>$guid</guid><title>$guid</title><link>https://example.com/$guid</link><description><![CDATA[$html]]></description></item>"
        http.page(url, "<rss version=\"2.0\"><channel><title>Blog</title>${item("pictured", words + picture)}${item("long", "<p>${"word ".repeat(150_000)}</p>")}</channel></rss>")
        sync.syncAll()
        val stored = db.articles().candidates().associate { it.guid to it.feedHtml }
        assertEquals("the inlined picture goes, the words stay", words + "<img src=\"\">", stored["pictured"])
        assertNull("too long even then: the page is fetched instead", stored["long"])
    }

    /** A feed over the size limit comes back cut short: its whole items are read, the cut one isn't. */
    @Test
    fun aFeedCutShortAddsItsWholeItems() = runTest {
        val id = repo.addFeed(url, "Archive")
        val feed = rss("Archive", "1" to "One", "2" to "Two", "3" to "Three")
        http.page(url, feed.substring(0, feed.indexOf("Body of Three")))
        http.cutShort += url

        assertEquals(SyncResult(newArticles = 2, failedSources = 0, sources = 1), sync.syncAll())
        assertEquals(setOf("One", "Two"), db.articles().candidates().map { it.title }.toSet())
        assertNull(db.sources().byId(id)!!.lastError)
    }

    /** One that can't be read in part says it's too large, rather than that the site has moved. */
    @Test
    fun aFeedCutShortThatListsItsOldestFirstSaysItsTooLarge() = runTest {
        val id = repo.addFeed(url, "Archive")
        fun item(n: Int) = "<item><title>Post $n</title><link>https://example.com/$n</link><pubDate>0$n Oct 2026 10:00:00 GMT</pubDate></item>"
        http.page(url, "<rss version=\"2.0\"><channel><title>Archive</title>${(1..4).joinToString("") { item(it) }}<item><title>Po")
        http.cutShort += url

        assertEquals(1, sync.syncAll().failedSources)
        assertEquals("This feed is too large to read. It lists its oldest posts first, so its new ones are past what can be read.", db.sources().byId(id)!!.lastError)
    }

    @Test
    fun pausedSourcesAreNotFetched() = runTest {
        val id = repo.addFeed(url, "Blog")
        repo.update(db.sources().byId(id)!!.copy(paused = true))
        http.page(url, rss("Blog", "1" to "One"))
        assertEquals(0, sync.syncAll().newArticles)
    }

    @Test
    fun oldUnpickedFeedArticlesExpireButStarredOnesStay() = runTest {
        repo.addFeed(url, "Blog")
        http.page(url, rss("Blog", "old" to "Old", "kept" to "Kept"))
        sync.syncAll()
        val kept = db.articles().candidates().first { it.guid == "kept" }
        repo.setStarred(kept.id, true)

        now = now.plus(Duration.ofDays(8))
        http.page(url, rss("Blog", "new" to "New"))
        sync.syncAll()

        assertEquals(setOf("kept", "new"), db.articles().candidates().map { it.guid }.toSet())
    }

    @Test
    fun aPauseMadeDuringASyncIsNotUndone() = runTest {
        val id = repo.addFeed(url, "Blog")
        val source = db.sources().byId(id)!!
        http.page(url, rss("Blog", "1" to "One"))
        http.beforeResponse = { repo.setPaused(id, true) }

        sync.sync(source)

        assertEquals(true, db.sources().byId(id)!!.paused)
    }

    @Test
    fun aSourceRemovedDuringASyncDoesntBreakTheOthers() = runTest {
        val goneId = repo.addFeed(url, "Gone")
        repo.addFeed("https://other.example/feed", "Other")
        http.page(url, rss("Gone", "1" to "One"))
        http.page("https://other.example/feed", rss("Other", "2" to "Two"))
        http.beforeResponse = { u -> if (u == url) repo.remove(db.sources().byId(goneId)!!) }

        val result = sync.syncAll()

        assertEquals(1, result.newArticles)
        assertEquals(listOf("Two"), db.articles().candidates().map { it.title })
    }

    private suspend fun deliver(vararg articles: ArticleEntity) {
        val editionId = db.editions().insert(EditionEntity(title = "Edition", status = EditionStatus.READY))
        db.editions().insertArticles(
            articles.mapIndexed { i, a -> EditionArticleEntity(editionId = editionId, articleId = a.id, position = i, title = a.title, sourceTitle = "Blog", minutes = 1.0) },
        )
        EditionRepository(db, java.io.File("unused"), clock).markDelivered(editionId)
    }

    /** Removing a source and adding it back, or meeting a story in a second source, mustn't deliver it again. */
    @Test
    fun aDeliveredLinkIsntOfferedAgainFromAnySource() = runTest {
        repo.addFeed(url, "Blog")
        repo.addFeed("https://other.example/feed", "Other")
        http.page(url, rss("Blog", "1" to "One", "2" to "Two"))
        // The same link under the other feed's own guid and title.
        http.page("https://other.example/feed", rss("Other", "1" to "One, reposted"))
        sync.syncAll()
        deliver(db.articles().candidates().first { it.title == "One" })
        assertEquals("a waiting copy is used up too", listOf("Two"), db.articles().candidates().map { it.title })

        repo.remove(db.sources().byUrl(url)!!)
        repo.addFeed(url, "Blog")

        assertEquals(1, sync.syncAll().newArticles)
        assertEquals(setOf("Two"), db.articles().candidates().map { it.title }.toSet())
    }

    /** Saving a link on purpose is the reader asking for it, whatever was delivered before. */
    @Test
    fun aDeliveredLinkCanStillBeSavedToTheReadingList() = runTest {
        repo.addFeed(url, "Blog")
        http.page(url, rss("Blog", "1" to "One"))
        sync.syncAll()
        deliver(db.articles().candidates().single())

        ReadingListRepository(db).save("https://example.com/1", "One")

        assertEquals(listOf("https://example.com/1"), db.articles().candidates().map { it.url })
    }

    @Test
    fun deliveredLinksAreForgottenAfterAYear() = runTest {
        repo.addFeed(url, "Blog")
        http.page(url, rss("Blog", "1" to "One"))
        sync.syncAll()
        deliver(db.articles().candidates().single())
        repo.remove(db.sources().byUrl(url)!!)

        now = now.plus(FeedSync.REMEMBER_DELIVERED).plusSeconds(60)
        sync.syncAll()
        repo.addFeed(url, "Blog")
        sync.syncAll()

        assertEquals(listOf("One"), db.articles().candidates().map { it.title })
    }

    @Test
    fun oldArticlesLoseTheirFeedTextButKeepTheirRow() = runTest {
        val id = repo.addFeed(url, title = "Example")
        db.articles().insertNew(listOf(
            ArticleEntity(sourceId = id, guid = "old", url = "https://example.com/old", title = "Old", feedHtml = "<p>old</p>", discoveredAt = now.minus(Duration.ofDays(40)), state = ArticleState.DELIVERED),
            ArticleEntity(sourceId = id, guid = "recent", url = "https://example.com/recent", title = "Recent", feedHtml = "<p>recent</p>", discoveredAt = now.minus(Duration.ofDays(5)), state = ArticleState.DELIVERED),
            ArticleEntity(sourceId = id, guid = "sent-today", url = "https://example.com/sent-today", title = "Found long ago", feedHtml = "<p>kept</p>", discoveredAt = now.minus(Duration.ofDays(60)), state = ArticleState.DELIVERED),
        ))
        val sentToday = db.articles().allForSource(id).single { it.guid == "sent-today" }.id
        db.articles().rememberDelivered(listOf(sentToday), now)
        repo.setPaused(id, true)

        sync.syncAll()

        val byGuid = db.articles().allForSource(id).associateBy { it.guid }
        assertEquals("the row stays, so the feed can't offer it again", setOf("old", "recent", "sent-today"), byGuid.keys)
        assertEquals("a month counts from delivery", "<p>kept</p>", byGuid.getValue("sent-today").feedHtml)
        assertEquals(null, byGuid.getValue("old").feedHtml)
        assertEquals("<p>recent</p>", byGuid.getValue("recent").feedHtml)
    }

    /** A link remembered as delivered with its tracking tags still counts once stored links lose them, so re-adding its feed doesn't send it again. */
    @Test
    fun aLinkDeliveredWithItsTrackingTagsIsntStoredAgainWhenItsFeedIsAddedBack() = runTest {
        db.openHelper.writableDatabase.execSQL("INSERT INTO delivered_urls (url, deliveredAt) VALUES ('https://example.com/1?utm_source=rss', 0)")
        repo.addFeed(url, "Example")
        http.page(
            url,
            """<rss version="2.0"><channel><title>Example</title>
               <item><title>One</title><link>https://example.com/1?utm_source=rss</link><guid>1</guid><description>Sent before.</description></item>
               </channel></rss>""",
        )

        assertEquals(0, sync.syncAll().newArticles)
    }

    /**
     * A link post is stored as the story it points to, so every check on links (delivered, copies
     * in other sources, stars) sees the story; its guid stays the feed's, which is how the feed's
     * next copy of it is recognised.
     */
    @Test
    fun aLinkPostIsStoredAsItsStoryAndTrackingComesOffOtherLinks() = runTest {
        val id = repo.addFeed(url, "Example")
        val pick = "<p>A short pitch for a story elsewhere.</p><p><a href=\"https://news.example/story?src=example\">Read the story</a></p>"
        http.page(
            url,
            """<rss version="2.0"><channel><title>Example</title><link>https://example.com/</link>
               <item><title>Pick</title><link>https://example.com/pick</link><guid>pick-1</guid><description><![CDATA[$pick]]></description></item>
               <item><title>Own</title><link>https://example.com/own?utm_source=rss&amp;id=2</link><guid>own-2</guid><description>Our own post.</description></item>
               </channel></rss>""",
        )

        sync.syncAll()
        assertEquals("the same items again aren't new", 0, sync.syncAll().newArticles)

        val byGuid = db.articles().allForSource(id).associateBy { it.guid }
        assertEquals("https://news.example/story", byGuid.getValue("pick-1").url)
        assertEquals("https://example.com/pick", byGuid.getValue("pick-1").viaUrl)
        assertEquals("https://example.com/own?id=2", byGuid.getValue("own-2").url)
        assertNull(byGuid.getValue("own-2").viaUrl)
    }

    @Test
    fun aPodcastsEpisodesKeepTheirAudioAndAreJudged() = runTest {
        repo.addFeed(url, "A Show")
        val essay = (1..400).joinToString(" ") { "word$it" }
        http.page(
            url,
            """
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel><title>A Show</title><itunes:type>episodic</itunes:type>
                <item><title>Episode 12</title><link>https://show.example/12</link><guid>e12</guid>
                  <description>Our guest talks about trains.</description>
                  <enclosure url="https://cdn.example/12.mp3" type="audio/mpeg" length="54321"/></item>
                <item><title>An essay</title><link>https://show.example/essay</link><guid>essay</guid>
                  <description>$essay</description></item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        sync.syncAll()

        val byTitle = db.articles().candidates().associateBy { it.title }
        with(byTitle.getValue("Episode 12")) {
            assertEquals("https://cdn.example/12.mp3", audioUrl)
            assertEquals(54321L, audioBytes)
            assertEquals(EpisodeKind.NOTES, episodeKind)
            assertNull(episodePair)
        }
        assertEquals(EpisodeKind.ARTICLE, byTitle.getValue("An essay").episodeKind)
    }
}
