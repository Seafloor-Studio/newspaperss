package com.app.newspaperss.core.listen

import com.app.newspaperss.core.ReadingTime
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

/**
 * A page of the edition: what the screen shows, in blocks, and what the voice says, one [Line] at
 * a time. A line is a sentence, or an image's one-line description, so going back a line is going
 * back a sentence.
 *
 * A block can show more than is said: the byline's date, a footnote's marker, the link to the
 * original. Each spoken sentence is a stretch of what its block shows (see [Block.Text.ranges]).
 */
data class ListenScript(val blocks: List<Block>, val language: String? = null) {
    /** Everything spoken, in order. */
    val lines: List<Line> = blocks.flatMapIndexed { b, block -> block.said.mapIndexed { s, sentence -> Line(b, s, sentence) } }

    // Each line's start in seconds, and the whole at the end: asked for at every sentence.
    private val starts: DoubleArray by lazy {
        val out = DoubleArray(lines.size + 1)
        lines.forEachIndexed { i, line -> out[i + 1] = out[i] + ListenTime.seconds(line.spoken) }
        out
    }

    /** About how long reading it aloud takes, at normal speed. */
    val seconds: Double get() = starts.last()

    /** About how long reading the lines before [line] takes. */
    fun secondsBefore(line: Int): Double = starts[line.coerceIn(0, lines.size)]

    sealed interface Block {
        /** What the voice says of it, a line each; empty for what's only shown. */
        val said: List<String>

        /**
         * A run of text: a paragraph, heading, list item and so on.
         *
         * @param shown the text as drawn: spaces run together, a line break as `\n`, a code block's
         *   own spacing kept.
         * @param ranges where each of [sentences] is in [shown].
         * @param marker a list item's number ("3.") or bullet, drawn before it.
         * @param depth how many lists it's inside.
         */
        data class Text(
            val kind: Kind,
            val sentences: List<String>,
            val shown: String = sentences.joinToString(" "),
            val ranges: List<IntRange> = joinedRanges(sentences),
            val styles: List<Style> = emptyList(),
            val marker: String? = null,
            val depth: Int = 0,
        ) : Block {
            override val said: List<String> get() = sentences
        }

        /**
         * @param description its caption or alt text, or null if the article gives neither.
         * @param caption the figure's caption, drawn under it.
         */
        data class Image(val src: String, val description: String?, val caption: String? = null) : Block {
            val spoken: String get() = description?.let { "Image: $it" } ?: "An image."
            override val said: List<String> get() = listOf(spoken)
        }

        /** Read a row at a time, its cells run together: cell by cell, with a pause after each, is hard to follow. */
        data class Table(val rows: List<Row>, val caption: String? = null) : Block {
            override val said: List<String> get() = rows.flatMap { it.sentences }

            /** @param sentences what's said of it, its cells joined by commas. */
            data class Row(val cells: List<Cell>, val sentences: List<String>)

            data class Cell(val shown: String, val header: Boolean, val colspan: Int = 1, val rowspan: Int = 1)
        }

        /** A horizontal rule. */
        data object Rule : Block {
            override val said: List<String> get() = emptyList()
        }
    }

    /** [kind] over [start] until [end] of a block's shown text; [href] for a link. */
    data class Style(val start: Int, val end: Int, val kind: StyleKind, val href: String? = null)

    enum class StyleKind { BOLD, ITALIC, UNDERLINE, STRIKE, CODE, SUB, SUP, SMALL, MARK, LINK }

    /** [META] is page furniture, shown small and never read: the link to the original, the "Next" line. */
    enum class Kind { KICKER, TITLE, BYLINE, HEADING, PARAGRAPH, QUOTE, ITEM, PREFORMATTED, META }

    /** One thing the voice says: the [index]th line of the [block]th block. */
    data class Line(val block: Int, val index: Int, val spoken: String)

    companion object {
        /**
         * Reads a page of the book EpubWriter made: an article or the closing page. The voice
         * reads the kicker, title, the author from its byline, then the body; not the link to the
         * original or the "Next" line, which are only shown. The body is anything an article's
         * HTML can be, so it's walked generally: text that sits together is a paragraph, and every
         * picture is a block of its own.
         */
        fun parse(xhtml: String): ListenScript {
            val doc = Jsoup.parse(xhtml, "", Parser.xmlParser())
            val html = doc.selectFirst("html")
            val bodyElement = doc.selectFirst("div.article-body")
            val language = (bodyElement?.attr("xml:lang")?.ifEmpty { null } ?: bodyElement?.attr("lang")?.ifEmpty { null })
                ?: html?.attr("xml:lang")?.ifEmpty { null } ?: html?.attr("lang")?.ifEmpty { null }
            val out = Builder()
            val page = doc.selectFirst("body") ?: return ListenScript(emptyList(), language)
            for (element in page.children()) {
                when {
                    element.hasClass("kicker") -> out.body(element, Kind.KICKER)
                    element.hasClass("article-title") || element.hasClass("end-title") -> out.body(element, Kind.TITLE)
                    // The date and reading time are for the page; spoken, they'd be noise.
                    element.hasClass("byline") -> out.byline(element.text())
                    element.hasClass("article-body") -> out.body(element, Kind.PARAGRAPH)
                    element.hasClass("reflection") -> out.body(element, Kind.PARAGRAPH)
                    element.hasClass("note") || element.hasClass("end-summary") -> out.body(element, Kind.PARAGRAPH)
                    element.normalName() == "hr" -> out.rule()
                    // The end mark, the original's address, the "Next" line and the end page's imprint.
                    else -> out.body(element, Kind.META, silent = true)
                }
            }
            return ListenScript(out.blocks, language)
        }

        /** Where each of [sentences] is once they're joined by spaces. */
        fun joinedRanges(sentences: List<String>): List<IntRange> {
            var at = 0
            return sentences.map { sentence -> (at until at + sentence.length).also { at += sentence.length + 1 } }
        }

        private class Builder {
            val blocks = mutableListOf<Block>()
            private val text = Inline()
            private var marker: String? = null
            private val lists = ArrayDeque<ListState>()

            private class ListState(val ordered: Boolean, var next: Int)

            fun byline(byline: String) {
                val parts = byline.split(" · ")
                val author = parts.firstOrNull { it.startsWith("By ") }
                parts.forEachIndexed { i, part ->
                    if (i > 0) text.append(" · ", spoken = false)
                    text.append(part, spoken = part === author)
                }
                flush(Kind.BYLINE)
            }

            fun rule() {
                blocks += Block.Rule
            }

            fun body(container: Element, kind: Kind, silent: Boolean = false) {
                for (node in container.childNodes()) visit(node, kind, silent)
                flush(kind)
            }

            private fun flush(kind: Kind) {
                val taken = text.take()
                if (taken.shown.isNotEmpty()) {
                    blocks += Block.Text(kind, taken.sentences, taken.shown, taken.ranges, taken.styles, marker, lists.size)
                    marker = null
                }
            }

            private fun visit(node: Node, kind: Kind, silent: Boolean) {
                when (node) {
                    is TextNode -> text.append(node.wholeText, spoken = !silent, keepSpacing = kind == Kind.PREFORMATTED)
                    is Element -> when (val tag = node.normalName()) {
                        in SKIPPED -> {}
                        // A footnote's marker or its link back, which would be read as a stray
                        // number or arrow: a short link within the page, with no word in it. Shown, not said.
                        "a" -> {
                            val marker = (node.attr("href").startsWith("#") || node.attr("href").contains(".xhtml#")) &&
                                node.text().trim().length <= FOOTNOTE_MARKER_MAX && node.text().none { it.isLetter() }
                            text.styled(StyleKind.LINK, node.attr("href").ifEmpty { null }) { for (child in node.childNodes()) visit(child, kind, silent || marker) }
                        }
                        "br" -> text.lineBreak(spoken = !silent)
                        "img" -> {
                            flush(kind)
                            image(node, null)
                        }
                        "figure" -> {
                            flush(kind)
                            figure(node, kind, silent)
                        }
                        "table" -> {
                            flush(kind)
                            table(node)
                        }
                        "hr" -> {
                            flush(kind)
                            rule()
                        }
                        // Drawn with quotation marks, as a browser does; the voice says the words.
                        "q" -> {
                            text.append("“", spoken = false)
                            for (child in node.childNodes()) visit(child, kind, silent)
                            text.append("”", spoken = false)
                        }
                        in STYLES -> text.styled(STYLES.getValue(tag), null) { for (child in node.childNodes()) visit(child, kind, silent) }
                        in BLOCKS -> {
                            flush(kind)
                            val inner = when (tag) {
                                "h1", "h2", "h3", "h4", "h5", "h6" -> Kind.HEADING
                                "blockquote" -> Kind.QUOTE
                                "pre" -> Kind.PREFORMATTED
                                "li", "dd", "dt" -> if (kind == Kind.QUOTE) kind else Kind.ITEM
                                else -> kind
                            }
                            if (tag == "ul" || tag == "ol") lists.addLast(ListState(tag == "ol", node.attr("start").toIntOrNull() ?: 1))
                            // The item's first block carries its number; one after a list inside it doesn't.
                            if (tag == "li") marker = lists.lastOrNull()?.let { if (it.ordered) "${it.next++}." else "•" }
                            for (child in node.childNodes()) visit(child, inner, silent)
                            flush(inner)
                            if (tag == "ul" || tag == "ol") lists.removeLast()
                        }
                        else -> for (child in node.childNodes()) visit(child, kind, silent)
                    }
                    else -> {}
                }
            }

            private fun figure(figure: Element, kind: Kind, silent: Boolean) {
                val caption = figure.selectFirst("figcaption")?.text()?.trim()?.ifEmpty { null }
                val images = figure.select("img")
                images.forEach { image(it, caption.takeIf { images.size == 1 }) }
                if (images.size != 1 && caption != null) {
                    text.append(caption, spoken = !silent)
                    flush(kind)
                }
                // Anything else in the figure (a quote, a table) is read as text.
                for (child in figure.childNodes()) {
                    if (child is Element && (child.normalName() == "figcaption" || child.normalName() == "img" || child.select("img").isNotEmpty())) continue
                    visit(child, kind, silent)
                }
                flush(kind)
            }

            private fun image(img: Element, caption: String?) {
                val description = caption ?: img.attr("alt").trim().ifEmpty { null }
                blocks += Block.Image(img.attr("src"), description, caption)
            }

            private fun table(table: Element) {
                val rows = table.select("tr").map { row ->
                    val cells = row.select("th, td")
                    Block.Table.Row(
                        cells.map { Block.Table.Cell(it.text(), it.normalName() == "th", it.attr("colspan").toIntOrNull() ?: 1, it.attr("rowspan").toIntOrNull() ?: 1) },
                        Sentences.split(cells.joinToString(", ") { it.text() }),
                    )
                }
                if (rows.isNotEmpty()) blocks += Block.Table(rows, table.selectFirst("caption")?.text()?.trim()?.ifEmpty { null })
            }
        }

        private val SKIPPED = setOf("script", "style", "noscript", "svg", "math", "aside", "nav")
        private const val FOOTNOTE_MARKER_MAX = 4
        private val BLOCKS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "blockquote", "ul", "ol", "li", "dl", "dt", "dd",
            "h1", "h2", "h3", "h4", "h5", "h6", "pre", "address", "details", "summary", "figcaption",
        )
        private val STYLES = mapOf(
            "b" to StyleKind.BOLD, "strong" to StyleKind.BOLD,
            "i" to StyleKind.ITALIC, "em" to StyleKind.ITALIC, "cite" to StyleKind.ITALIC, "var" to StyleKind.ITALIC,
            "u" to StyleKind.UNDERLINE, "ins" to StyleKind.UNDERLINE,
            "s" to StyleKind.STRIKE, "del" to StyleKind.STRIKE,
            "code" to StyleKind.CODE, "kbd" to StyleKind.CODE, "samp" to StyleKind.CODE,
            "sub" to StyleKind.SUB, "sup" to StyleKind.SUP, "small" to StyleKind.SMALL, "mark" to StyleKind.MARK,
        )
    }
}

/**
 * One block's text as it's built: each character shown, and whether it's said. [take] makes the
 * text shown and the sentences said, and where each sentence is in what's shown.
 */
internal class Inline {
    class Taken(val shown: String, val sentences: List<String>, val ranges: List<IntRange>, val styles: List<ListenScript.Style>)

    private val chars = StringBuilder()
    private val said = java.util.BitSet()
    private val kept = java.util.BitSet()
    private val styles = mutableListOf<ListenScript.Style>()

    /** Outside a code block, any run of spaces, tabs and newlines is one space, as on a web page. */
    fun append(text: String, spoken: Boolean, keepSpacing: Boolean = false) {
        for (c in text) {
            if (spoken) said.set(chars.length)
            if (keepSpacing) kept.set(chars.length)
            chars.append(if (!keepSpacing && c in SPACES) ' ' else c)
        }
    }

    fun lineBreak(spoken: Boolean) {
        if (spoken) said.set(chars.length)
        chars.append(BREAK)
    }

    fun styled(kind: ListenScript.StyleKind, href: String?, inner: () -> Unit) {
        val start = chars.length
        inner()
        if (chars.length > start) styles += ListenScript.Style(start, chars.length, kind, href)
    }

    fun take(): Taken {
        // Shown: runs of space are one (a line break wins), none at either end.
        val shown = StringBuilder()
        val shownSaid = java.util.BitSet()
        val at = IntArray(chars.length + 1)
        for (i in chars.indices) {
            at[i] = shown.length
            val c = chars[i]
            val last = shown.lastOrNull()
            when {
                // A code block's own spacing, but not a line break opening it, as a browser drops.
                kept[i] -> if (last != null || c !in SPACES) shown.append(c)
                c != ' ' && c != BREAK -> shown.append(c)
                last == null -> {}
                last == ' ' || last == BREAK -> if (c == BREAK) shown.setCharAt(shown.length - 1, BREAK)
                else -> shown.append(c)
            }
            if (said[i] && shown.isNotEmpty()) shownSaid.set(shown.length - 1)
        }
        at[chars.length] = shown.length
        while (shown.isNotEmpty() && (shown.last() == ' ' || shown.last() == BREAK)) shown.setLength(shown.length - 1)
        // Said: the shown characters that are said, spaces again run together, and where each is shown.
        val spoken = StringBuilder()
        val from = mutableListOf<Int>()
        for (i in shown.indices) {
            if (!shownSaid[i]) continue
            val c = shown[i]
            if (c in SPACES) {
                if (spoken.isEmpty() || spoken.last() == ' ') continue
                spoken.append(' ')
            } else spoken.append(c)
            from += i
        }
        while (spoken.isNotEmpty() && spoken.last() == ' ') { spoken.setLength(spoken.length - 1); from.removeAt(from.lastIndex) }
        val sentences = Sentences.split(spoken.toString())
        var cursor = 0
        val ranges = sentences.map { sentence ->
            val i = spoken.indexOf(sentence, cursor).takeIf { it >= 0 } ?: cursor
            cursor = (i + sentence.length).coerceAtMost(spoken.length)
            from[i.coerceAtMost(from.lastIndex)]..from[(cursor - 1).coerceIn(0, from.lastIndex)]
        }
        val mapped = styles.mapNotNull { s ->
            val start = at[s.start]
            val end = minOf(at[s.end], shown.length)
            if (end > start) s.copy(start = start, end = end) else null
        }
        val out = Taken(shown.toString(), sentences, ranges, mapped)
        chars.clear(); said.clear(); kept.clear(); styles.clear()
        return out
    }

    private companion object {
        const val BREAK = '\n'
        // As Sentences sees them (\s): not the no-break space, which keeps its words together.
        const val SPACES = " \t\n\u000B\u000C\r"
    }
}

/** How long reading aloud takes: a voice reads about 160 words a minute, against 238 read silently. */
object ListenTime {
    const val WPM = 160

    /** Seconds to say [text], counting the pause after it. */
    fun seconds(text: String): Double = ReadingTime.words(text) * 60.0 / WPM + PAUSE

    /** Listening minutes for something that takes [readingMinutes] to read. */
    fun fromReading(readingMinutes: Double): Double = readingMinutes * ReadingTime.DEFAULT_WPM / WPM

    private const val PAUSE = 0.4
}
