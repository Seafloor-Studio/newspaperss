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
        assertTrue(extract(http, feed).html.contains(noteText))
        assertTrue("its Note was there all along", http.requested.isEmpty())
    }

    /** A paid post's page has only the free part; a subscriber's tt-rss has it all. */
    @Test
    fun aShorterPostOnThePageKeepsTtrssText() = runTest {
        val free = Jsoup.parseBodyFragment(feed).body().children().take(3).joinToString("") { it.outerHtml() }
        val article = extract(FakeHttp(mapOf(url to page(free))))
        assertEquals(extract(FakeHttp(emptyMap())).html, article.html)
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
}
