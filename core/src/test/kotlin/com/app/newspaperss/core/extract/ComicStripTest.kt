package com.app.newspaperss.core.extract

import com.app.newspaperss.core.images.ArticleImages
import com.app.newspaperss.core.images.ImageRules
import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A Webtoons episode: one long strip cut into dozens of lazily loaded images, under a page of other text. */
class ComicStripTest {
    private class FakeHttp(private val responses: Map<String, HttpResponse>) : HttpClient {
        override suspend fun get(url: String): HttpResponse = responses[url] ?: throw IOException("no route to host")
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
        override suspend fun postJson(url: String, body: String): HttpResponse = throw IOException("not used")
    }

    private val url = "https://www.webtoons.com/en/romance/a-series/episode-9/viewer?title_no=1&episode_no=9"
    private fun panel(n: Int) = "https://webtoon-phinf.example/20180408_$n/panel$n.jpg?type=q90"

    /** Shaped like the real viewer: placeholders in src, the panel in data-url, and the site's own text around it. */
    private fun page(panels: Int) = HttpResponse(
        200, url, "text/html; charset=utf-8",
        """<html><head><title>Episode 9 | A Series</title><meta property="og:description" content="The gods after dark."></head><body>
           <div class="subj_info"><h1 class="subj_episode">Episode 9</h1><p>A Series · Romance</p></div>
           <div class="viewer_img _img_viewer_area" id="_imageList">
           ${(1..panels).joinToString("\n") { """<img src="https://static.example/bg_transparency.png" width="800" height="1219.0" alt="image" class="_images" data-url="${panel(it)}">""" }}
           </div>
           <div class="comment_area"><p>Sign in to comment. Read the creator's note and more episodes in the app.</p>
           <p>Subscribe to get notified when a new episode is published. Rate this episode and share it with friends.</p></div>
           </body></html>""",
    )

    private suspend fun extract(panels: Int) = ArticleExtractor(FakeHttp(mapOf(url to page(panels)))).extract(ExtractInput(url, "Episode 9", null, null))

    @Test
    fun anEpisodeIsItsWholeStripInOrder() = runTest {
        val article = extract(55)
        assertEquals((1..55).map(::panel), article.imageUrls)
        assertTrue("not the comments: ${article.html.take(300)}", "Sign in to comment" !in article.html)
        assertEquals("all of it downloaded, past the usual cap", 55, ArticleImages.wanted(article.imageUrls, article.imageLimit).size)
    }

    /** The mobile site, where a phone is sent: the panels are listed in a script. */
    @Test
    fun theMobileSitesStripComesFromItsScript() = runTest {
        val mobile = "https://m.webtoons.com/en/romance/a-series/episode-9/viewer?title_no=1&episode_no=9"
        // The script lists the originals; the strip asks for the size the desktop viewer shows.
        val list = (1..3).joinToString(",") { """{ url: "${panel(it).substringBefore('?')}", spec: "JPG", width: 800, height: 1219, sortOrder: $it }""" }
        val body = """<html><head><title>Episode 9</title></head><body><h1 class="h1_viewer sub_title">Episode 9</h1>
            <div class="txt_area"><strong>The story doesn't have to end</strong><p>Read more episodes for free in the app.</p></div>
            <script type="text/javascript">var imageList = [ $list ];</script></body></html>"""
        val article = ArticleExtractor(FakeHttp(mapOf(mobile to HttpResponse(200, mobile, "text/html", body)))).extract(ExtractInput(mobile, "Episode 9", null, null))
        assertEquals((1..3).map(::panel), article.imageUrls)
        // Elsewhere, a script of that shape is just a script.
        val other = "https://example.com/gallery"
        val elsewhere = ArticleExtractor(FakeHttp(mapOf(other to HttpResponse(200, other, "text/html", body)))).extract(ExtractInput(other, "Gallery", null, null))
        assertTrue(elsewhere.imageUrls.isEmpty())
    }

    @Test
    fun aVeryLongStripIsCutShort() = runTest {
        val article = extract(140)
        assertEquals(PageExtractor.MAX_STRIP_PANELS, article.imageUrls.size)
    }

    /** Not a strip, not more pictures than any other article. */
    @Test
    fun anOrdinaryArticleKeepsTheUsualCap() = runTest {
        val sentence = "The committee met again on Thursday to argue about the budget for the new library wing. "
        val body = "<html><body><article><p>${sentence.repeat(30)}</p>" +
            (1..30).joinToString("") { "<figure><img src=\"https://cdn.example/photo$it.jpg\" width=\"800\" height=\"600\"></figure><p>$sentence</p>" } +
            "</article></body></html>"
        val plain = "https://example.com/a-story"
        val article = ArticleExtractor(FakeHttp(mapOf(plain to HttpResponse(200, plain, "text/html", body))))
            .extract(ExtractInput(plain, "A story", null, null))
        assertEquals(ImageRules.MAX_PER_ARTICLE, article.imageLimit)
    }
}
