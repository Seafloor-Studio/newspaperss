package com.app.newspaperss.core.ttrss

import com.app.newspaperss.core.net.OkHttpHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * [TtrssClient] against a real tt-rss, where TtrssClientTest has only what we believe it answers.
 * Skipped unless TTRSS_LIVE is set; tools/ttrss-live/compose.yml starts the server, and the
 * "tt-rss" workflow runs this daily against tt-rss's latest image, so a change on their side
 * shows up before it reaches a reader. Feeds are files this test writes into the folder that
 * server serves, named per run so a rerun against the same server starts clean.
 */
class TtrssLiveTest {
    private val base = System.getenv("TTRSS_LIVE_URL") ?: "http://127.0.0.1:8280/tt-rss/"
    private val feedDir = File(System.getenv("TTRSS_LIVE_FEEDS") ?: "../tools/ttrss-live/feeds")
    private val run = UUID.randomUUID().toString().take(8)
    private val http = OkHttpHttpClient()
    private val api = TtrssClient.apiUrl(base)
    private lateinit var client: TtrssClient
    private val written = mutableListOf<File>()

    @Before
    fun setUp() {
        assumeTrue("TTRSS_LIVE not set", System.getenv("TTRSS_LIVE") != null)
        client = TtrssClient(http, api, USER, PASSWORD)
    }

    @After
    fun tearDown() = runBlocking {
        if (!::client.isInitialized) return@runBlocking
        written.forEach { it.delete() }
        client.allFeeds().filter { it.feedUrl?.contains(run) == true }.forEach { client.unsubscribeFeed(it.id) }
    }

    // An unknown user, not a wrong password, which would hold off the other tests' logins (below).
    @Test
    fun anUnknownUserIsALoginFailure() = runBlocking {
        val failure = runCatching { TtrssClient(http, api, "nobody-$run", "not-it").login() }.exceptionOrNull()
        assertTrue("got $failure", failure is TtrssException.LoginFailed)
    }

    // TtrssRepository tells the reader to wait when a refusal comes this soon after another.
    @Test
    fun aWrongPasswordHoldsOffEvenTheRightOneForFiveSeconds() = runBlocking {
        val wrong = runCatching { TtrssClient(http, api, USER, "not-it").login() }.exceptionOrNull()
        assertTrue("got $wrong", wrong is TtrssException.LoginFailed)
        val tooSoon = runCatching { TtrssClient(http, api, USER, PASSWORD).login() }.exceptionOrNull()
        assertTrue("the right password straight after: $tooSoon", tooSoon is TtrssException.LoginFailed)
        // Ends signed in, so the tests after this one aren't held off.
        Thread.sleep(5_000)
        assertTrue(TtrssClient(http, api, USER, PASSWORD).login().isNotEmpty())
    }

    @Test
    fun subscribingSaysWhatHappened() = runBlocking {
        val added = client.subscribeToFeed(feed("news", item("one")), 0)
        assertTrue("got $added", added is TtrssSubscription.Added && added.feedId != null)
        val again = client.subscribeToFeed(feedUrl("news"), 0)
        assertEquals(TtrssSubscription.AlreadySubscribed((added as TtrssSubscription.Added).feedId), again)

        val page = client.subscribeToFeed(file("page.html", "<!doctype html><html><head><title>A page</title></head><body><p>Not a feed.</p></body></html>"), 0)
        assertTrue("a page: $page", page is TtrssSubscription.Refused && page.couldntFetch)
        val missing = client.subscribeToFeed(feedUrl("never-written"), 0)
        assertTrue("a 404: $missing", missing is TtrssSubscription.Refused && missing.couldntFetch)
    }

    @Test
    fun anUpdatedFeedsArticlesArriveWithTheirTextAudioAndByline() = runBlocking {
        val feedId = subscribed(
            "articles",
            item("plain", title = "Tom &amp; Jerry", body = "<p>Hello <b>world</b>.</p>", author = "A. N. Other"),
            item("arabic", title = "عنوان المقال", body = "<p dir=\"rtl\">نص المقال.</p>"),
            item("episode", title = "Episode 12", body = "<p>Show notes.</p>", enclosure = "http://web/feeds/ep12.mp3"),
        )
        val headlines = client.unreadHeadlines(feedId).associateBy { it.title }
        assertEquals(setOf("Tom & Jerry", "عنوان المقال", "Episode 12"), headlines.keys)
        val plain = headlines.getValue("Tom & Jerry")
        assertEquals("A. N. Other", plain.author)
        assertTrue(plain.content!!.contains("<b>world</b>"))
        assertEquals(feedId.toString(), plain.feedId)
        assertNotNull(plain.updated)
        val audio = headlines.getValue("Episode 12").attachments.single()
        assertEquals("http://web/feeds/ep12.mp3", audio.url)
        assertEquals("audio/mpeg", audio.type)
    }

    @Test
    fun readAndUnreadReachTheServerAndStarsAreListed() = runBlocking {
        val feedId = subscribed("states", item("a"), item("b"))
        val (a, b) = client.unreadHeadlines(feedId).sortedBy { it.title }.map { it.id }
        client.markRead(listOf(a))
        assertEquals(listOf(b), client.unreadHeadlines(feedId).map { it.id })
        assertEquals(mapOf(a to false, b to true), client.unreadStates(feedId, sinceId = 0))
        client.markUnread(listOf(a))
        assertEquals(setOf(a, b), client.unreadHeadlines(feedId).map { it.id }.toSet())

        raw("updateArticle", "\"article_ids\":\"$b\",\"mode\":1,\"field\":0")
        assertTrue(b in client.starredIds(pages = 1).ids)
        assertEquals(listOf("b"), client.articles(listOf(b)).map { it.title })
    }

    @Test
    fun catchingUpTwoWeeksLeavesTodaysArticlesUnread() = runBlocking {
        val feedId = subscribed("catchup", item("today"))
        client.markReadOlderThanTwoWeeks()
        assertEquals(listOf("today"), client.unreadHeadlines(feedId).map { it.title })
    }

    @Test
    fun feedsListTheirAddressAndTheErrorTtrssMet() = runBlocking {
        val feedId = subscribed("errors", item("x"))
        assertEquals(feedUrl("errors"), client.allFeeds().single { it.id == feedId }.feedUrl)
        written.single { it.name.contains("errors") }.delete()
        // tt-rss reuses a feed it fetched in the last 30 seconds, so it may take a few updates.
        val deadline = System.nanoTime() + 60_000_000_000
        var error: String? = null
        while (error == null && System.nanoTime() < deadline) {
            raw("updateFeed", "\"feed_id\":$feedId")
            error = client.allFeeds().single { it.id == feedId }.lastError ?: run { Thread.sleep(2_000); null }
        }
        assertTrue("got $error", error != null && "404" in error)
    }

    @Test
    fun aSessionTtrssDroppedIsReplacedByLoggingInAgain() = runBlocking {
        val feedId = subscribed("session", item("x"))
        val sid = client.login()
        http.postJson(api, """{"sid":"$sid","op":"logout"}""")
        assertEquals(listOf("x"), client.unreadHeadlines(feedId).map { it.title })
    }

    @Test
    fun unsubscribingRemovesTheFeed() = runBlocking {
        val feedId = subscribed("leaving", item("x"))
        client.unsubscribeFeed(feedId)
        assertFalse(client.allFeeds().any { it.id == feedId })
    }

    /** Subscribes to a new feed of [items] and has tt-rss fetch it, as its updater would. */
    private suspend fun subscribed(name: String, vararg items: String): Int {
        val result = client.subscribeToFeed(feed(name, *items), 0)
        val feedId = (result as? TtrssSubscription.Added)?.feedId ?: error("subscribing to $name: $result")
        raw("updateFeed", "\"feed_id\":$feedId")
        return feedId
    }

    /** An API call [TtrssClient] doesn't make, on a session of its own. */
    private suspend fun raw(op: String, fields: String) {
        val login = Json.parseToJsonElement(http.postJson(api, """{"op":"login","user":"$USER","password":"$PASSWORD"}""").body)
        val sid = login.jsonObject.getValue("content").jsonObject.getValue("session_id").jsonPrimitive.content
        val answer = Json.parseToJsonElement(http.postJson(api, """{"sid":"$sid","op":"$op",$fields}""").body).jsonObject
        assertEquals("$op: $answer", "0", answer.getValue("status").jsonPrimitive.content)
    }

    private fun feedUrl(name: String) = "http://web/feeds/$run-$name.xml"

    private fun feed(name: String, vararg items: String) = file(
        "$name.xml",
        """<?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel>
        <title>Live test: $name</title><link>http://web/</link><description>Made up for TtrssLiveTest</description>
        ${items.joinToString("\n")}
        </channel></rss>""",
    )

    private fun file(name: String, text: String): String {
        val f = File(feedDir, "$run-$name").apply { parentFile.mkdirs(); writeText(text) }
        written += f
        return "http://web/feeds/${f.name}"
    }

    private fun item(id: String, title: String = id, body: String = "<p>$id</p>", author: String? = null, enclosure: String? = null) = """
        <item><title>$title</title><link>http://web/$run/$id</link><guid>$run-$id</guid>
        <pubDate>${java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now())}</pubDate>
        ${author?.let { "<dc:creator>$it</dc:creator>" } ?: ""}
        ${enclosure?.let { "<enclosure url=\"$it\" length=\"1000\" type=\"audio/mpeg\"/>" } ?: ""}
        <description><![CDATA[$body]]></description></item>"""

    private companion object {
        // The user tools/ttrss-live/compose.yml creates.
        const val USER = "reader"
        const val PASSWORD = "reader-password"
    }
}
