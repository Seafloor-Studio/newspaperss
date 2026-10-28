package com.app.newspaperss.core.listen

import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class ListenScriptTest {
    private val png = EpubImage("images/knots.png", "image/png", byteArrayOf(1, 2, 3))

    /** The book's own pages, as the player reads them out of the EPUB. */
    private fun book(vararg articles: EditionArticle, reflection: String? = null): Map<String, String> {
        val out = ByteArrayOutputStream()
        val doc = EditionDoc(
            "Thursday Morning Edition", LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000002",
            listOf(EditionSection(null, articles.toList())), reflection = reflection,
        )
        EpubWriter.write(doc, out)
        val pages = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { pages[it.name.removePrefix("OEBPS/")] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return pages
    }

    private fun article(body: String, author: String? = "Erica Klarreich", images: List<EpubImage> = emptyList(), minutes: Double = 9.0) = EditionArticle(
        title = "The Mathematician Who Counted the Shapes of Knots", sourceTitle = "Quanta Magazine", url = "https://example.com/knots",
        bodyHtml = body, minutes = minutes, author = author, published = LocalDate.of(2026, 9, 30), images = images,
    )

    /** What's read of each block, a table a row at a time; not what's only shown. */
    private fun texts(script: ListenScript) = script.blocks.filter { it.said.isNotEmpty() }.flatMap { block ->
        when (block) {
            is Block.Text -> listOf(block.kind to block.sentences.joinToString(" "))
            is Block.Image -> listOf(null to block.spoken)
            is Block.Table -> block.rows.map { Kind.PARAGRAPH to it.sentences.joinToString(" ") }
            Block.Rule -> emptyList()
        }
    }

    @Test
    fun anArticleIsReadFromItsSourceAndTitleToItsLastSentenceAndNoFurther() {
        val pages = book(
            article("<p>For forty years, nobody could say. Then a teacher wrote a program.</p><h2>The count</h2><p>It took a week.</p>"),
            article("<p>The second.</p>"),
        )
        val script = ListenScript.parse(pages.getValue("article-001.xhtml"))
        assertEquals(
            listOf(
                Kind.KICKER to "Quanta Magazine",
                Kind.TITLE to "The Mathematician Who Counted the Shapes of Knots",
                // The author, not the date and reading time.
                Kind.BYLINE to "By Erica Klarreich",
                Kind.PARAGRAPH to "For forty years, nobody could say. Then a teacher wrote a program.",
                Kind.HEADING to "The count",
                Kind.PARAGRAPH to "It took a week.",
            ),
            texts(script),
        )
        // Each sentence is a line of its own: what ↶ goes back to. Not the link to the original
        // nor the "Next" line after a long read.
        assertEquals(
            listOf(
                "Quanta Magazine", "The Mathematician Who Counted the Shapes of Knots", "By Erica Klarreich",
                "For forty years, nobody could say.", "Then a teacher wrote a program.", "The count", "It took a week.",
            ),
            script.lines.map { it.spoken },
        )
        assertEquals("en", script.language)
    }

    @Test
    fun aPictureIsSaidInOneLineFromItsCaptionOrAltText() {
        val pages = book(
            article(
                "<p>Tait drew the first table.</p>" +
                    "<figure><img src=\"images/knots.png\" alt=\"knots\"/><figcaption>Tait's 1877 table of knots</figcaption></figure>" +
                    "<p>Before <img src=\"images/knots.png\" alt=\"A trefoil\"/> after.</p>" +
                    "<p><img src=\"images/knots.png\"/></p>",
                images = listOf(png),
            ),
        )
        val script = ListenScript.parse(pages.getValue("article-001.xhtml"))
        val body = texts(script).drop(3)
        assertEquals(
            listOf(
                Kind.PARAGRAPH to "Tait drew the first table.",
                // The caption over the alt text, and not read a second time.
                null to "Image: Tait's 1877 table of knots",
                Kind.PARAGRAPH to "Before",
                null to "Image: A trefoil",
                Kind.PARAGRAPH to "after.",
                null to "An image.",
            ),
            body,
        )
        assertEquals("images/knots.png", script.blocks.filterIsInstance<Block.Image>().first().src)
    }

    @Test
    fun quotesListsAndTablesAreReadAsTheirOwnBlocks() {
        val script = ListenScript.parse(
            book(
                article(
                    "<blockquote><p>Knots are hard.</p></blockquote><ul><li>One</li><li>Two <b>bold</b> words</li></ul>" +
                        "<table><tr><th>Crossings</th><th>Knots</th></tr><tr><td>3</td><td>1</td></tr></table>" +
                        "<p>Text<br/>broken.</p>",
                ),
            ).getValue("article-001.xhtml"),
        )
        assertEquals(
            listOf(
                Kind.QUOTE to "Knots are hard.",
                Kind.ITEM to "One",
                Kind.ITEM to "Two bold words",
                Kind.PARAGRAPH to "Crossings, Knots",
                Kind.PARAGRAPH to "3, 1",
                Kind.PARAGRAPH to "Text broken.",
            ),
            texts(script).drop(3),
        )
    }

    @Test
    fun footnoteMarkersAreNotReadOut() {
        val script = ListenScript.parse(
            book(article("<p>A claim.<sup><a href=\"#fn1\" id=\"r1\">1</a></sup> More, see <a href=\"https://example.com\">the paper</a> and <a href=\"#notes\">here</a>.</p><p id=\"fn1\"><a href=\"#r1\">1</a> The source.</p>"))
                .getValue("article-001.xhtml"),
        )
        assertEquals(listOf("A claim.", "More, see the paper and here.", "The source."), script.lines.drop(3).map { it.spoken })
    }

    @Test
    fun withoutAnAuthorThereIsNoByline() {
        val script = ListenScript.parse(book(article("<p>Text.</p>", author = null)).getValue("article-001.xhtml"))
        assertEquals(listOf(Kind.KICKER, Kind.TITLE, Kind.PARAGRAPH), script.blocks.filter { it.said.isNotEmpty() }.map { (it as Block.Text).kind })
    }

    @Test
    fun theEndPageIsReadToo() {
        val pages = book(article("<p>Text.</p>"), reflection = "What surprised you?")
        val script = ListenScript.parse(pages.getValue("end.xhtml"))
        val spoken = script.lines.map { it.spoken }
        assertEquals("That's all for today.", spoken.first())
        assertTrue(spoken.toString(), spoken.any { it.startsWith("1 article") })
        assertTrue(spoken.toString(), "To think about" in spoken && "What surprised you?" in spoken)
        // Not the imprint: the masthead and date are for the page.
        assertTrue(spoken.none { it.contains("NewspapeRSS") })
    }

    private fun page(body: String, author: String? = "Erica Klarreich") = ListenScript.parse(book(article(body, author), article("<p>The second.</p>")).getValue("article-001.xhtml"))

    private fun Block.Text.said(i: Int) = shown.substring(ranges[i].first, ranges[i].last + 1)

    @Test
    fun theWholePageIsShownThoughNotAllOfItIsSaid() {
        val script = page("<p>Text.</p>")
        val shown = script.blocks.map { block ->
            when (block) {
                is Block.Text -> block.kind to block.shown
                Block.Rule -> null to "rule"
                else -> null to block.toString()
            }
        }
        assertEquals(
            listOf(
                Kind.KICKER to "Quanta Magazine",
                Kind.TITLE to "The Mathematician Who Counted the Shapes of Knots",
                Kind.BYLINE to "By Erica Klarreich · Sep 30, 2026 · 9 min read",
                null to "rule",
                Kind.PARAGRAPH to "Text.",
                Kind.META to "■",
                Kind.META to "Read the original at example.com",
                Kind.META to "Next: The Mathematician Who Counted the Shapes of Knots · Quanta Magazine · 9 min",
            ),
            shown,
        )
        // The byline's author is said, from where it's shown; the rest of the furniture isn't.
        val byline = script.blocks[2] as Block.Text
        assertEquals(listOf("By Erica Klarreich"), byline.sentences)
        assertEquals("By Erica Klarreich", byline.said(0))
        assertTrue(script.blocks.filterIsInstance<Block.Text>().filter { it.kind == Kind.META }.all { it.sentences.isEmpty() })
    }

    @Test
    fun boldItalicCodeAndLinksAreKeptOverTheirWords() {
        val text = page("<p>Plain <b>bold</b>, <em>slanted <code>x = 1</code></em> and <a href=\"https://example.com/a\">a link</a>.</p>").blocks
            .filterIsInstance<Block.Text>().single { it.kind == Kind.PARAGRAPH }
        assertEquals("Plain bold, slanted x = 1 and a link.", text.shown)
        fun over(kind: ListenScript.StyleKind) = text.styles.filter { it.kind == kind }.map { text.shown.substring(it.start, it.end) }
        assertEquals(listOf("bold"), over(ListenScript.StyleKind.BOLD))
        assertEquals(listOf("slanted x = 1"), over(ListenScript.StyleKind.ITALIC))
        assertEquals(listOf("x = 1"), over(ListenScript.StyleKind.CODE))
        assertEquals(listOf("a link"), over(ListenScript.StyleKind.LINK))
        assertEquals("https://example.com/a", text.styles.single { it.kind == ListenScript.StyleKind.LINK }.href)
    }

    @Test
    fun aLinkOrBoldAroundABlockCoversItsWordsInEachBlock() {
        // As HTML allows: a link around whole blocks, not inside a paragraph (which the book's writer would split).
        val texts = ListenScript.parse(
            "<html><body><div class=\"article-body\"><div>Before <a href=\"https://example.com/u\">link words <b>bold start <div>a block</div> bold tail</b></a> end.</div></div></body></html>",
        ).blocks.filterIsInstance<Block.Text>()
        fun over(text: Block.Text, kind: ListenScript.StyleKind) = text.styles.filter { it.kind == kind }.map { text.shown.substring(it.start, it.end) }
        assertEquals(listOf("Before link words bold start", "a block", "bold tail end."), texts.map { it.shown })
        assertEquals(listOf(listOf("link words bold start"), listOf("a block"), listOf("bold tail")), texts.map { over(it, ListenScript.StyleKind.LINK) })
        assertEquals(listOf(listOf("bold start"), listOf("a block"), listOf("bold tail")), texts.map { over(it, ListenScript.StyleKind.BOLD) })
    }

    @Test
    fun listsAreNumberedFromTheirStartAndNestedInsideEachOther() {
        val items = page("<ol start=\"3\"><li>Three</li><li>Four<ul><li>Inside</li></ul>still four</li></ol><dl><dt>Term</dt></dl>").blocks
            .filterIsInstance<Block.Text>().filter { it.kind == Kind.ITEM }
        assertEquals(
            listOf(Triple("Three", "3.", 1), Triple("Four", "4.", 1), Triple("Inside", "•", 2), Triple("still four", null, 1), Triple("Term", null, 0)),
            items.map { Triple(it.shown, it.marker, it.depth) },
        )
        fun markers(html: String) = page(html).blocks.filterIsInstance<Block.Text>().filter { it.kind == Kind.ITEM || it.kind == Kind.PARAGRAPH }.map { it.shown to it.marker }
        // An item opening with a list gives that list's first item its number too.
        assertEquals(listOf("in" to "1. •", "tail" to null, "two" to "2."), markers("<ol><li><ul><li>in</li></ul>tail</li><li>two</li></ol>"))
        // An item with no text of its own leaves its bullet with it, not on what follows.
        assertEquals(listOf("After." to null), markers("<ul><li><img src=\"a.png\" alt=\"x\"/></li></ul><p>After.</p>"))
        assertEquals(listOf("Three" to "3.", "Two" to "2.", "Seven" to "7."), markers("<ol reversed=\"reversed\"><li>Three</li><li>Two</li><li value=\"7\">Seven</li></ol>"))
    }

    @Test
    fun aFootnotesMarkerAndAQuotesMarksAreShownButNotSaid() {
        val text = page("<p>A claim.<sup><a href=\"#fn1\">1</a></sup> She said <q>yes</q>, then\n left.<br/>Next line.</p><p id=\"fn1\">The source.</p>").blocks
            .filterIsInstance<Block.Text>().first { it.kind == Kind.PARAGRAPH }
        assertEquals("A claim.1 She said “yes”, then left.\nNext line.", text.shown)
        assertEquals(listOf("A claim.", "She said yes, then left.", "Next line."), text.sentences)
        // Each sentence is where it's shown, the marker and the marks inside or beside it.
        assertEquals(listOf("A claim.", "She said “yes”, then left.", "Next line."), text.sentences.indices.map { text.said(it) })
        assertEquals(listOf("1"), text.styles.filter { it.kind == ListenScript.StyleKind.SUP }.map { text.shown.substring(it.start, it.end) })
    }

    @Test
    fun aCodeBlockKeepsItsSpacingThoughTheVoiceRunsItTogether() {
        val text = page("<pre>\nfun main() {\n    println(1)\n}</pre>").blocks.filterIsInstance<Block.Text>().single { it.preformatted }
        assertEquals("fun main() {\n    println(1)\n}", text.shown)
        assertEquals(listOf("fun main() { println(1) }"), text.sentences)
        // Only an opening line break is dropped, as a browser does; the first line's indent stays.
        // (On a bare page: the book's writer already tidies a code block's opening.)
        fun bare(pre: String) = ListenScript.parse("<html><body><div class=\"article-body\">$pre</div></body></html>").blocks.filterIsInstance<Block.Text>().single().shown
        assertEquals("\n  x", bare("<pre>\n\n  x</pre>"))
        assertEquals("    indented", bare("<pre>    indented</pre>"))
        // Inside a quote it's still a quote's, as the playing screen draws it.
        assertEquals(Kind.QUOTE, page("<blockquote><pre>code</pre></blockquote>").blocks.filterIsInstance<Block.Text>().single { it.preformatted }.kind)
    }

    @Test
    fun aTableIsShownAsItsCellsAndSaidARowAtATime() {
        val table = page("<table><caption>Knots by crossings</caption><tr><th>Crossings</th><th>Knots</th></tr><tr><td colspan=\"2\">none</td></tr></table>").blocks
            .filterIsInstance<Block.Table>().single()
        assertEquals("Knots by crossings", table.caption)
        assertEquals(listOf(listOf("Crossings" to true, "Knots" to true), listOf("none" to false)), table.rows.map { row -> row.cells.map { it.shown to it.header } })
        assertEquals(2, table.rows[1].cells.single().colspan)
        assertEquals(listOf("Crossings, Knots", "none"), table.said)
    }

    @Test
    fun anArticleInAnotherLanguageSaysSo() {
        val pages = book(article("<p>Bonjour.</p>").copy(language = "fr"))
        assertEquals("fr", ListenScript.parse(pages.getValue("article-001.xhtml")).language)
    }

    @Test
    fun listeningTakesLongerThanReading() {
        assertEquals(15.0, ListenTime.fromReading(10.0), 0.2)
        val script = ListenScript.parse(book(article("<p>" + "word ".repeat(160) + "end.</p>")).getValue("article-001.xhtml"))
        // 161 words of body at 160 a minute, after the source, title and byline.
        assertEquals(60.0, script.lines.drop(3).sumOf { ListenTime.seconds(it.spoken) }, 2.0)
        assertTrue(script.seconds > 60.0)
    }
}
