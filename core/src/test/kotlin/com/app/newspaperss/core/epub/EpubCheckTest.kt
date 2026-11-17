package com.app.newspaperss.core.epub

import com.adobe.epubcheck.api.EPUBLocation
import com.adobe.epubcheck.api.EpubCheck
import com.adobe.epubcheck.api.MasterReport
import com.adobe.epubcheck.messages.Message
import com.adobe.epubcheck.messages.Severity
import com.adobe.epubcheck.util.FeatureEnum
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.extract.PlatformCorpusTest
import com.app.newspaperss.core.images.ArticleImages
import com.app.newspaperss.core.images.EncodedImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.imageio.ImageIO

/**
 * The W3C's own validator on whole books. EpubWriterTest checks the writer's parts one by one;
 * this catches what they don't name, on the real extractor's output for every post in the
 * platform corpus. Send to Kindle and Kobo reject or mangle books epubcheck flags, so a warning
 * fails too. The books are left in build/epubcheck/ for Ace by DAISY and the page renders.
 */
class EpubCheckTest {
    @Test
    fun anEditionOfTheWholePlatformCorpusIsValid() {
        // Each post twice, from its feed and as tt-rss passes it on, which differ.
        val posts = PlatformCorpusTest.posts().map { it[0] as PlatformCorpusTest.Post }
        val articles = posts.withIndex().map { (i, post) -> runBlocking { corpusArticle(i, post) } }
        assertValid("corpus-edition", edition(articles + madeUpArticles()))
    }

    @Test
    fun anEditionWithNoArticlesIsValid() {
        assertValid("empty-edition", edition(emptyList(), cover = null, reflection = null))
    }

    private suspend fun corpusArticle(index: Int, post: PlatformCorpusTest.Post): EditionArticle {
        val e = post.extract()
        // Picture bytes stand in for downloads, in all three formats the book takes.
        val encoded = ArticleImages.wanted(e.imageUrls).withIndex().associate { (n, url) -> url to picture(FORMATS[(index + n) % FORMATS.size]) }
        val embedded = ArticleImages.embed(e.html, "a$index", encoded)
        return EditionArticle(
            title = e.title, sourceTitle = post.dir.name.substringBefore('-'), url = post.url, bodyHtml = embedded.html,
            minutes = ReadingTime.minutes(e.wordCount), author = e.author, note = e.note, images = embedded.images,
            language = e.language, published = LocalDate.of(2026, 10, 1),
        )
    }

    /** What the corpus doesn't have: right-to-left and CJK text, a blank title, markup in titles. */
    private fun madeUpArticles() = listOf(
        EditionArticle(
            title = "عنوان المقال", sourceTitle = "صحيفة", url = "https://example.com/ar", minutes = 2.0,
            bodyHtml = "<p>هذا نص تجريبي باللغة العربية، مع <a href=\"https://example.com\">رابط</a>.</p>", language = "ar",
        ),
        EditionArticle(
            title = "記事の見出し", sourceTitle = "新聞", url = "https://example.jp/a", minutes = 1.0,
            bodyHtml = "<p>これはテスト用の文章です。</p>", language = "ja", note = "The full article couldn't be fetched.",
        ),
        EditionArticle(title = "", sourceTitle = "Example & Co", url = "https://example.com/blank", minutes = 0.5, bodyHtml = "<p>No title.</p>"),
        EditionArticle(
            title = "<b>Bold</b> & \"quoted\" <script>x</script>", sourceTitle = "Example", url = "https://example.com/q?a=1&b=2",
            minutes = 1.0, bodyHtml = "<p>Markup in a title stays text.</p>", author = "A. N. Other",
        ),
    )

    private fun edition(articles: List<EditionArticle>, cover: EpubImage? = coverImage(), reflection: String? = "What surprised you?") = EditionDoc(
        title = "Tuesday Morning Edition, Oct 20", date = LocalDate.of(2026, 10, 20),
        identifier = "urn:uuid:5b1f3c8e-0000-4000-8000-000000000002", articles = articles,
        modified = Instant.parse("2026-10-20T06:30:00Z"), cover = cover, reflection = reflection,
    )

    private fun assertValid(name: String, doc: EditionDoc) {
        val file = File("build/epubcheck/$name.epub").apply { parentFile.mkdirs() }
        file.outputStream().use { EpubWriter.write(doc, it) }
        val report = CollectingReport()
        EpubCheck(file, report).doValidate()
        val problems = report.messages.filter { it.first >= Severity.WARNING }.map { it.second }
        assertTrue("epubcheck on ${file.path}:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private class CollectingReport : MasterReport() {
        val messages = mutableListOf<Pair<Severity, String>>()
        override fun message(message: Message, location: EPUBLocation, vararg args: Any?) {
            messages += message.severity to "${message.severity} ${message.id} ${location.path}:${location.line}: ${message.getMessage(*args)}"
        }
        override fun info(resource: String?, feature: FeatureEnum?, value: String?) = Unit
        override fun generate() = 0
        override fun initialize() = Unit
    }

    private fun coverImage() = EpubImage("images/cover.jpg", "image/jpeg", picture("image/jpeg", 600, 800).bytes)

    private fun picture(mediaType: String, width: Int = 64, height: Int = 48): EncodedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = Color.GRAY; fillRect(0, 0, width / 2, height); dispose() }
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, mediaType.substringAfter('/'), out)) { "no writer for $mediaType" }
        return EncodedImage(out.toByteArray(), mediaType, width, height)
    }

    private companion object {
        val FORMATS = listOf("image/jpeg", "image/png", "image/gif")
    }
}
