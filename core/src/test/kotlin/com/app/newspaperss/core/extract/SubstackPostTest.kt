package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * A Substack post with a Note in it, through tt-rss: the corpus' real post (scrambled), as its feed
 * has it and as tt-rss passes it on, and a page carrying the feed's version in its data the way
 * Substack's pages do (the corpus drops pages' scripts).
 */
class SubstackPostTest {
    private class FakeHttp(private val responses: Map<String, HttpResponse>) : HttpClient {
        val requested = mutableListOf<String>()
        override suspend fun get(url: String): HttpResponse {
            requested += url
            return responses[url] ?: throw IOException("no route to host")
        }
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
        override suspend fun postJson(url: String, body: String): HttpResponse = throw IOException("not used")
    }

    private val dir = File(javaClass.getResource("/platforms/substack/countercraft.substack.com-the-roots-of-the-novel-are-parody")!!.toURI())
    private fun read(name: String) = GZIPInputStream(File(dir, name).inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
    private val url = "https://countercraft.substack.com/p/the-roots-of-the-novel-are-parody"
    private val feed = read("feed.html.gz")
    private val ttrss = read("ttrss.html.gz")
    private val noteText = "I just saf to the fihi in Taf Tujorib"

    private fun page(body: String) = HttpResponse(
        200, url, "text/html; charset=utf-8",
        """<html><head><script type="application/json" id="preloads-json">{"isEU":false,"post":{"id":1,"body_html":${JsonPrimitive(body)}}}</script></head>
           <body><article><p>The page's own rendering, with a placeholder where the Note is.</p></article></body></html>""",
    )

    private suspend fun extract(http: HttpClient, html: String = ttrss, mode: ContentMode = ContentMode.AUTO) =
        ArticleExtractor(http).extract(ExtractInput(url, "The roots of the novel are parody", html, null, mode = mode))

    @Test
    fun aNoteStrippedByTtrssComesBackFromThePage() = runTest {
        val http = FakeHttp(mapOf(url to page(feed)))
        val article = extract(http)
        assertTrue(article.usedFeedContent)
        val quote = Jsoup.parseBodyFragment(article.html).select("blockquote").firstOrNull { noteText in it.text() }
        assertTrue("the Note is quoted: ${article.html.take(400)}", quote != null)
        assertEquals("fetched once", listOf(url), http.requested)
        // Without the page, tt-rss's text has nothing where the Note was.
        assertFalse(extract(FakeHttp(emptyMap())).html.contains(noteText))
    }

    @Test
    fun theFeedsOwnTextNeedsNoPage() = runTest {
        val http = FakeHttp(mapOf(url to page(feed)))
        // An empty block of its own doesn't make it look stripped.
        assertTrue(extract(http, "$feed<div></div>").html.contains(noteText))
        assertTrue("its Note was there all along", http.requested.isEmpty())
    }

    /** A paid post's page has only the free part, however late the writer put the paywall; a subscriber's tt-rss has it all. */
    @Test
    fun aShorterPostOnThePageKeepsTtrssText() = runTest {
        val blocks = Jsoup.parseBodyFragment(feed).body().children()
        val free = blocks.dropLast(2).joinToString("") { it.outerHtml() }
        val article = extract(FakeHttp(mapOf(url to page(free))))
        assertEquals(extract(FakeHttp(emptyMap())).html, article.html)
    }

    /** A non-subscriber's tt-rss has a paid post's free part, ending in "Read more": it stays a paid post. */
    @Test
    fun aPaidPreviewStaysAPaidPost() = runTest {
        val preview = "$ttrss<p><a href=\"$url\">Read more</a></p>"
        val paywalled = HttpResponse(
            200, url, "text/html; charset=utf-8",
            page(feed).body.replace("</article>", "<div data-testid=\"paywall\"><h2>Keep reading with a 7-day free trial</h2></div></article>"),
        )
        val http = FakeHttp(mapOf(url to paywalled))
        val article = extract(http, preview)
        assertTrue(article.paidPost)
        assertEquals("fetched once", listOf(url), http.requested)
    }

    @Test
    fun aPageWithoutThePostsDataKeepsTtrssText() = runTest {
        val plain = HttpResponse(200, url, "text/html", "<html><body><p>Just a page.</p></body></html>")
        assertEquals(extract(FakeHttp(emptyMap())).html, extract(FakeHttp(mapOf(url to plain))).html)
    }

    @Test
    fun onlySubstackPostsAreFetched() = runTest {
        val http = FakeHttp(emptyMap())
        val other = ttrss.replace("substackcdn.com", "cdn.example.com")
        ArticleExtractor(http).extract(ExtractInput("https://example.com/p/a-post", "A post", other, null))
        assertTrue(http.requested.isEmpty())
    }

    /** A custom domain is told by Substack's image host. */
    @Test
    fun aCustomDomainIsSubstackByItsImages() {
        assertTrue(SubstackPost.stripped(ttrss, "https://www.slowboring.com/p/a-post"))
        assertFalse(SubstackPost.stripped(ttrss.replace("substackcdn.com", "cdn.example.com"), "https://www.slowboring.com/p/a-post"))
    }

    /** A gallery tt-rss stripped leaves an empty block too, and a heading can introduce it. */
    @Test
    fun aGalleryStrippedAfterAHeadingIsFetched() {
        assertTrue(SubstackPost.stripped("<p>Peace.</p><h3>Field photos</h3><div></div><p>Bye.</p>", "https://writer.substack.com/p/a-post"))
    }

    /** Most Substack posts have an empty block where tt-rss stripped a picture's zoom button: no Note there, no fetch. */
    @Test
    fun aPicturesStrippedButtonIsntANote() {
        val other = File(dir.parentFile, "www.slowboring.com-cracks-in-the-republican-coalition")
        val text = GZIPInputStream(File(other, "ttrss.html.gz").inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        assertTrue("it has one", Regex("""</picture>\s*<div>\s*<div>\s*</div>""").containsMatchIn(text))
        assertFalse(SubstackPost.stripped(text, "https://www.slowboring.com/p/cracks-in-the-republican-coalition"))
    }
}
