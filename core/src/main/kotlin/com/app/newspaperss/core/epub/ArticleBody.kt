package com.app.newspaperss.core.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

/**
 * Re-serializes an article's HTML body as XHTML that a strict XML parser accepts and that can sit
 * in a book next to other articles.
 */
internal object ArticleBody {
    private const val REMOVED_ELEMENTS =
        "script, style, noscript, iframe, frame, frameset, object, embed, applet, template, " +
            "form, input, button, select, textarea, link, meta, base, title, head, " +
            "svg, math, canvas, video, audio, source, track, map"

    // Undeclared prefixes (Word's <o:p>, xlink:href) break namespace-aware parsers, and framework
    // syntax like @click isn't a legal XML name, so only plain HTML names are kept.
    private val PLAIN_NAME = Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*")
    private val ID_UNSAFE = Regex("[^A-Za-z0-9_.-]")

    // These refer to ids by name; after prefixing they would point at nothing.
    private val ID_REFERENCE_ATTRIBUTES = setOf("for", "headers", "usemap", "aria-labelledby", "aria-describedby", "aria-controls")

    // Elements XHTML lets hold only text and inline elements. Browsers accept a block inside them
    // (Blogger wraps a captioned picture's divs in <i> or <h2>); epubcheck rejects the book.
    // An inline one is unwrapped. A heading or pre matters more than its blocks, so they go instead.
    private const val INLINE =
        "abbr, b, bdi, bdo, big, cite, code, data, dfn, em, font, i, kbd, label, mark, q, s, samp, small, " +
            "span, strike, strong, sub, sup, time, tt, u, var"
    private const val KEPT = "h1, h2, h3, h4, h5, h6, pre, legend, summary"
    private const val BLOCKS =
        "address, article, aside, blockquote, center, details, div, dl, dd, dt, fieldset, figcaption, " +
            "figure, footer, h1, h2, h3, h4, h5, h6, header, hgroup, hr, li, main, menu, nav, ol, p, pre, " +
            "search, section, table, ul"

    /**
     * @param idPrefix prepended to every id (and to the `#fragment` links that point at them) so
     *   ids stay unique across the book. Must start with a letter.
     * @param imageHrefs the image hrefs the book contains; any other `img` is removed.
     */
    fun toXhtml(html: String, idPrefix: String, imageHrefs: Set<String>): String {
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()
        body.select(REMOVED_ELEMENTS).remove()
        removeComments(body)
        for (element in body.select("*")) {
            if (element !== body && !PLAIN_NAME.matches(element.tagName())) element.unwrap()
        }
        body.select("picture").unwrap()
        unwrapInlinesHoldingBlocks(body)
        cleanAttributes(body)
        fixFootnotes(body)
        val ids = prefixIds(body, idPrefix)
        fixLinks(body, ids)
        fixImages(body, imageHrefs)
        nameUnnamedLinks(body)
        levelHeadings(body)
        stripInvalidCharacters(body)

        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return body.html()
    }

    /**
     * Headings as a screen reader lists them: the article's title is the page's h1, so the body's
     * start at h2 and go down one level at a time. Sites skip levels for their look (an h3 straight
     * under the title); the book's styles go by level. Siblings stay siblings: h3, h5, h3 becomes
     * h2, h3, h2.
     */
    private fun levelHeadings(root: Element) {
        // The site's level and the book's, for each heading still open above the current one.
        val open = ArrayDeque<Pair<Int, Int>>()
        for (heading in root.select("h1, h2, h3, h4, h5, h6")) {
            val level = heading.tagName()[1].digitToInt()
            while (open.isNotEmpty() && open.last().first >= level) open.removeLast()
            val leveled = ((open.lastOrNull()?.second ?: 1) + 1).coerceAtMost(6)
            open.addLast(level to leveled)
            heading.tagName("h$leveled")
        }
    }

    private fun unwrapInlinesHoldingBlocks(root: Element) {
        // From the children down: select and selectFirst match the element itself too.
        fun blocksIn(e: Element, also: String = "") = e.children().flatMap { it.select(BLOCKS + also) }
        for (kept in root.select(KEPT)) {
            // A table's rows, or a fieldset's legend, would be left behind it otherwise.
            for (block in blocksIn(kept, ", caption, legend, summary, tbody, td, tfoot, th, thead, tr")) {
                // A pre's own line break already parts it from what follows.
                val next = block.nextSibling()
                if (next != null && !(next is TextNode && next.wholeText.startsWith("\n"))) block.after(Element("br"))
                unwrapKeepingId(block)
            }
        }
        root.select(INLINE).filter { blocksIn(it).isNotEmpty() }.forEach(::unwrapKeepingId)
    }

    /**
     * Unwraps [e], its id going to the block inside it, which a footnote link's popup shows: not
     * its first child, often the number linking back.
     */
    private fun unwrapKeepingId(e: Element) {
        val id = e.id()
        if (id.isNotEmpty()) {
            val first = e.children().firstOrNull { it.`is`(BLOCKS) } ?: e.children().firstOrNull()
            if (first != null && !first.hasAttr("id")) first.id(id) else e.before(Element("span").id(id))
        }
        e.unwrap()
    }

    // "--" inside a comment is not well-formed XML, and comments have no value in a book.
    private fun removeComments(root: Element) {
        root.forEachNode { if (it is Comment) it.remove() }
    }

    // Classes go too: the book's stylesheet styles the edition's own parts by class, and an
    // article's own class="kicker" or "byline" would take their look.
    private fun cleanAttributes(root: Element) {
        for (element in root.select("*")) {
            val names = element.attributes().map { it.key }
            for (name in names) {
                val drop = !(PLAIN_NAME.matches(name) || name == "xml:lang") ||
                    name.startsWith("on") || name == "style" || name == "class" || name == "name" || name == "xmlns" ||
                    name in ID_REFERENCE_ATTRIBUTES
                if (drop) element.removeAttr(name)
            }
        }
    }

    /**
     * Footnotes a Kindle can show in its popup, which shows only the element a marker points at.
     * A footnote often keeps its id on its own number, the link back to the text (Substack's do), so
     * the popup said just "5": the id moves to the block the number starts. A footnote is told by
     * its two links pointing at each other. Markers side by side ("34") get a comma between them,
     * so each is a word of its own to tap.
     */
    private fun fixFootnotes(root: Element) {
        val byId = root.select("[id]").associateBy { it.id() }
        fun target(link: Element): Element? = link.attr("href").trim().takeIf { it.startsWith("#") }?.let { byId[it.substring(1)] }
        val order = root.select("*").withIndex().associate { (i, el) -> el to i }
        val paired = root.select("a[href][id]").filter { link -> target(link)?.let { it.tagName() == "a" && target(it) === link } == true }
        val pairedSet = paired.toSet()
        // The number is the later of the two links, and alone in its block: a block holding several
        // footnotes would show them all.
        val (numbers, markers) = paired.partition { link ->
            val parent = link.parent()
            parent != null && parent !== root && !parent.hasAttr("id") &&
                order.getValue(link) > order.getValue(target(link)!!) &&
                parent.childNodes().firstOrNull { it !is TextNode || !it.isBlank } === link &&
                parent.text().length > link.text().length &&
                parent.select("a").none { it !== link && it in pairedSet }
        }
        for (number in numbers) {
            number.parent()!!.id(number.id())
            number.removeAttr("id")
        }
        val markerSet = markers.toSet()
        for (marker in markers) {
            if (marker.nextSibling() in markerSet) marker.after(", ")
        }
    }

    /** Returns the body's original ids mapped to their new names. Duplicates lose their id. */
    private fun prefixIds(root: Element, idPrefix: String): Map<String, String> {
        val ids = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        for (element in root.select("[id]")) {
            val old = element.id()
            val new = idPrefix + ID_UNSAFE.replace(old, "-")
            if (old.isEmpty() || old in ids || !used.add(new)) {
                element.removeAttr("id")
            } else {
                ids[old] = new
                element.id(new)
            }
        }
        return ids
    }

    private fun fixLinks(root: Element, ids: Map<String, String>) {
        for (link in root.select("a")) {
            val href = link.attr("href").trim()
            val fixed = when {
                href.startsWith("#") -> ids[href.substring(1)]?.let { "#$it" }
                else -> externalHref(href)
            }
            if (fixed == null) {
                if (link.id().isEmpty()) link.unwrap() else link.removeAttr("href")
            } else {
                link.attr("href", fixed)
            }
        }
    }

    private fun fixImages(root: Element, imageHrefs: Set<String>) {
        for (img in root.select("img")) {
            val src = img.attr("src")
            if (src !in imageHrefs) {
                val figure = img.closest("figure")
                val link = img.parent()?.takeIf { it.tagName() == "a" }
                img.remove()
                if (link != null && link.childrenSize() == 0 && link.text().isBlank()) link.remove()
                if (figure != null && figure.select("img").isEmpty()) figure.remove()
                continue
            }
            val alt = img.attr("alt")
            val id = img.id()
            img.clearAttributes()
            img.attr("src", src).attr("alt", alt)
            if (id.isNotEmpty()) img.id(id)
        }
    }

    /**
     * Links a screen reader would have no name for (Ace's link-name). One holding only pictures
     * without alt text is kept, since it often cites the source (a tweet behind its screenshot, a
     * book behind its cover), and named after the site it goes to. One with nothing in it at all
     * (an icon whose svg was removed) shows nothing, so it goes; its id stays as a target.
     */
    private fun nameUnnamedLinks(root: Element) {
        for (link in root.select("a[href]")) {
            if (link.text().isNotBlank() || link.attr("aria-label").isNotBlank()) continue
            val pictures = link.select("img")
            when {
                pictures.isEmpty() -> if (link.id().isEmpty()) link.unwrap() else link.removeAttr("href")
                pictures.none { it.attr("alt").isNotBlank() } ->
                    link.attr("aria-label", siteName(link.attr("href"))?.let { "Linked picture, $it" } ?: "Linked picture")
            }
        }
    }

    // Character references like &#1; decode to characters XML can't hold, so this runs after parsing.
    private fun stripInvalidCharacters(root: Element) {
        root.forEachNode { node ->
            if (node is TextNode) {
                val text = node.wholeText
                val clean = stripInvalidXmlChars(text)
                if (clean != text) node.text(clean)
            }
        }
        for (element in root.select("*")) {
            for (attribute in element.attributes()) {
                val clean = stripInvalidXmlChars(attribute.value)
                if (clean != attribute.value) attribute.setValue(clean)
            }
        }
    }
}
