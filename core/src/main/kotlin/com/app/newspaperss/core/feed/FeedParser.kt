package com.app.newspaperss.core.feed

import com.app.newspaperss.core.epub.esc
import com.app.newspaperss.core.extract.HtmlCleaner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.net.URI

/** Parses RSS 2.0, RSS 1.0 (RDF), Atom and JSON Feed. */
object FeedParser {
    private const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val ATOM_NS = "http://www.w3.org/2005/Atom"
    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"
    private const val RSS1_NS = "http://purl.org/rss/1.0/"
    private const val MEDIA_NS = "http://search.yahoo.com/mrss/"
    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    // media:content, itunes:summary and the like share local names with the
    // elements read here, so only RSS/Atom's own namespaces count.
    private val FEED_NAMESPACES = setOf("", RSS1_NS, ATOM_NS)
    // A reference to another entity (&name;), not a character reference (&#160;).
    private val ENTITY_REF = Regex("&[A-Za-z_:]")
    private const val MAX_DEPTH = 512
    // A sync stores every item a feed lists, so a 10 MB feed of tiny ones would be hundreds of
    // thousands of rows (321,114 in one measured). Real feeds list tens; full-history ones hundreds.
    internal const val MAX_ITEMS = 1000
    private const val TOO_LARGE = "This feed is too large to read."

    /**
     * @param body the response body.
     * @param feedUrl where it was fetched from; relative links resolve against it.
     * @param truncated [body] is only the start of the feed (see [com.app.newspaperss.core.net.HttpClient.getFeed]):
     *   the items read before the cut are the feed. Not for a JSON Feed, which can't be read in part.
     */
    fun parse(body: String, feedUrl: String, truncated: Boolean = false): Feed {
        val text = body.trimStart('﻿', ' ', '\t', '\r', '\n')
        if (truncated && text.startsWith("{")) throw FeedTooLargeException(TOO_LARGE)
        val feed = if (text.startsWith("{")) parseJson(text, feedUrl) else parseXml(text, feedUrl, truncated)
        return feed.copy(items = ownAddresses(feed.items))
    }
    /**
     * Gives each item of a microblog its own address: some link every entry to the one page that
     * holds them all (sive.rs/d), with the entry's own page only in its id (sive.rs/d/1291). That
     * page would be fetched as each entry's article, and the entries taken for one story. Only an
     * id on the same site, and only where items share a link.
     */
    private fun ownAddresses(items: List<FeedItem>): List<FeedItem> {
        val shared = items.groupingBy { it.url }.eachCount().filterValues { it > 1 }.keys
        if (shared.isEmpty()) return items
        return items.map { item ->
            val id = item.guid
            val sameSite = runCatching { host(URI(id)) == host(URI(item.url)) }.getOrDefault(false)
            if (item.url in shared && id != item.url && id.startsWith("http") && sameSite) item.copy(url = id) else item
        }
    }

    private fun host(uri: URI) = uri.host?.lowercase()?.removePrefix("www.")


    /** True if [body] looks like a feed rather than an HTML page. */
    fun looksLikeFeed(body: String): Boolean {
        val head = body.trimStart('﻿', ' ', '\t', '\r', '\n').take(2048)
        if (head.startsWith("{")) return head.contains("jsonfeed.org")
        return Regex("<(rss|feed|rdf:RDF)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(head)
    }

    private fun parseXml(text: String, feedUrl: String, truncated: Boolean): Feed {
        // Entities that expand into other entities are refused before parsing: nested, they can
        // grow to gigabytes ("billion laughs"). Plain ones (an old CMS declaring &nbsp;) are read.
        // Neither Android's parser nor kxml2 expands a declared entity as FeedParser sets them up
        // (FeedParserEntityTest holds Android's to it), so this is the second line, not the first.
        if (declaresNestedEntities(text)) {
            throw FeedParseException("Not a readable feed: it declares entities made of other entities.")
        }
        val parser = newParser()
        parser.setInput(StringReader(text))
        try {
            return XmlFeedReader(parser, feedUrl, truncated).read()
        } catch (e: XmlPullParserException) {
            throw FeedParseException("Not a readable feed: ${e.message}", e)
        }
    }

    /**
     * The [MAX_ITEMS] newest of [items] by date, in the feed's order: whichever end of the feed its
     * newest are at. Undated ones count as older than any dated one, and keep the feed's order.
     */
    private fun newest(items: List<FeedItem>): List<FeedItem> {
        if (items.size <= MAX_ITEMS) return items
        val kept = items.indices.sortedWith(compareByDescending<Int, Instant?>(nullsFirst()) { items[it].published }).take(MAX_ITEMS).toSortedSet()
        return kept.map { items[it] }
    }

    /** The XML parser feeds are read with, the platform's: Android's own, or kxml2 on the JVM. */
    fun newParser(): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // Feeds often use HTML entities like &nbsp; that plain XML rejects;
        // kxml's relaxed mode (also the Android parser) tolerates them.
        try {
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: XmlPullParserException) {
        }
        return parser
    }

    /**
     * Whether the document type declaration before the root element declares a parameter entity,
     * or an entity whose value refers to another. One pass over the prolog, skipping comments and
     * processing instructions as a parser would, so neither a long one nor an `<rss>` inside one
     * hides what follows, and a prolog of any length costs no more than reading it.
     */
    internal fun declaresNestedEntities(text: String): Boolean {
        var i = 0
        while (true) {
            val lt = text.indexOf('<', i)
            if (lt < 0) return false
            i = when {
                text.startsWith("<!--", lt) -> text.indexOf("-->", lt + 4).takeIf { it >= 0 }?.plus(3) ?: return false
                text.startsWith("<?", lt) -> text.indexOf("?>", lt + 2).takeIf { it >= 0 }?.plus(2) ?: return false
                text.regionMatches(lt, "<!DOCTYPE", 0, 9, ignoreCase = true) -> return internalSubsetNests(text, lt + 9)
                // The root element: a document type declaration can only come before it.
                else -> return false
            }
        }
    }

    /** From just after `<!DOCTYPE`: whether its internal subset, if any, declares nesting entities. */
    private fun internalSubsetNests(text: String, from: Int): Boolean {
        var i = skipToOutsideQuotes(text, from, "[>") ?: return false
        if (text[i] == '>') return false
        i++
        while (true) {
            val next = skipToOutsideQuotes(text, i, "<]") ?: return false
            if (text[next] == ']') return false
            i = when {
                text.startsWith("<!--", next) -> text.indexOf("-->", next + 4).takeIf { it >= 0 }?.plus(3) ?: return false
                text.startsWith("<?", next) -> text.indexOf("?>", next + 2).takeIf { it >= 0 }?.plus(2) ?: return false
                text.regionMatches(next, "<!ENTITY", 0, 8, ignoreCase = true) -> {
                    var j = next + 8
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j < text.length && text[j] == '%') return true
                    // The first quoted value: the entity's text, or an external one's address.
                    val end = skipToOutsideQuotes(text, j, "\"'>") ?: return false
                    if (text[end] != '>') {
                        val close = text.indexOf(text[end], end + 1).takeIf { it >= 0 } ?: return false
                        val value = text.substring(end + 1, close)
                        if (ENTITY_REF.containsMatchIn(value) || '%' in value) return true
                        (skipToOutsideQuotes(text, close + 1, ">") ?: return false) + 1
                    } else end + 1
                }
                // Another declaration (<!ELEMENT, <!ATTLIST): to its end.
                else -> (skipToOutsideQuotes(text, next + 1, ">") ?: return false) + 1
            }
        }
    }

    /** The index of the first of [stops] at or after [from] that isn't inside a quoted value, or null. */
    private fun skipToOutsideQuotes(text: String, from: Int, stops: String): Int? {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c in stops) return i
            if (c == '"' || c == '\'') i = text.indexOf(c, i + 1).takeIf { it >= 0 } ?: return null
            i++
        }
        return null
    }

    private class TooDeep : Exception()

    private class XmlFeedReader(private val p: XmlPullParser, private val feedUrl: String, private val truncated: Boolean) {
        private var feedTitle: String? = null
        private var siteUrl: String? = null
        private var podcast = false
        private val items = mutableListOf<FeedItem>()
        private var sawRoot = false

        fun read(): Feed {
            var stoppedAtCut = false
            try {
                readAll()
            } catch (e: XmlPullParserException) {
                // A cut inside a tag ("<descri") is an error; the items added before it are whole.
                if (!truncated || items.isEmpty()) throw e
                stoppedAtCut = true
            } catch (_: TooDeep) {
                // As past a cut: the items before it are whole. Android's lenient parser closes only
                // one element per stray end tag, so a long feed of unescaped HTML gets here too.
                if (items.isEmpty()) throw FeedParseException("Not a readable feed: it nests elements more than $MAX_DEPTH deep.")
                stoppedAtCut = true
            }
            if (!sawRoot) throw FeedParseException("Empty document")
            if (!truncated) return Feed(feedTitle, siteUrl, newest(items), podcast)
            // Otherwise the parser took the cut for the document's end, closing what it left open
            // (kxml on the JVM) or not (Android's): either way the last item read may be only part
            // of one, so it goes. A whole one lost from a feed this long costs nothing.
            val whole = if (stoppedAtCut) items.toList() else items.dropLast(1)
            if (whole.isEmpty()) throw FeedTooLargeException(TOO_LARGE)
            // Oldest first, the start of the feed is its oldest posts: new ones would never come.
            val first = whole.first().published
            val last = whole.last().published
            if (first != null && last != null && first < last) throw FeedTooLargeException("$TOO_LARGE It lists its oldest posts first, so its new ones are past what can be read.")
            return Feed(feedTitle, siteUrl, newest(whole), podcast)
        }

        private fun readAll() {
            while (next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                val name = p.name
                when {
                    !sawRoot -> {
                        if (name != "rss" && name != "feed" && name != "RDF") {
                            throw FeedParseException("Not a feed: root element <$name>")
                        }
                        sawRoot = true
                    }
                    name == "item" || name == "entry" -> readItem(name)?.let {
                        items.add(it)
                        if (items.size >= 2 * MAX_ITEMS) newest(items).let { kept -> items.clear(); items.addAll(kept) }
                    }
                    p.namespace == ITUNES_NS && (name == "category" || name == "type") -> podcast = true
                    p.namespace !in FEED_NAMESPACES -> {}
                    name == "title" && feedTitle == null && p.depth <= 3 -> feedTitle = cleanTitle(readText())
                    name == "link" && siteUrl == null && p.depth <= 3 -> readLink()?.let { siteUrl = it }
                }
            }
        }

        private fun readItem(tag: String): FeedItem? {
            val depth = p.depth
            var title: String? = null
            var link: String? = null
            var guid: String? = null
            var guidIsLink = false
            var content: String? = null
            var summary: String? = null
            var author: String? = null
            var published: String? = null
            var updated: String? = null
            val enclosures = mutableListOf<Enclosure>()
            var duration: Int? = null
            while (!(next() == XmlPullParser.END_TAG && p.depth == depth && p.name == tag)) {
                if (p.eventType == XmlPullParser.END_DOCUMENT) break
                if (p.eventType != XmlPullParser.START_TAG || p.depth != depth + 1) continue
                val ns = p.namespace
                if (ns == MEDIA_NS || ns == ITUNES_NS) {
                    when {
                        ns == MEDIA_NS && p.name == "content" -> enclosure("url", "fileSize")?.let(enclosures::add)
                        ns == ITUNES_NS && p.name == "duration" -> duration = FeedDurations.seconds(readText())
                    }
                    if (p.eventType == XmlPullParser.START_TAG) skip()
                    continue
                }
                if (ns !in FEED_NAMESPACES && ns != CONTENT_NS && ns != DC_NS) {
                    skip()
                    continue
                }
                when (p.name) {
                    "title" -> if (ns in FEED_NAMESPACES) title = readText() else skip()
                    "link" -> if (p.getAttributeValue(null, "rel") == "enclosure") {
                        enclosure("href", "length")?.let(enclosures::add)
                        skip()
                    } else {
                        readLink()?.let { if (link == null) link = it }
                    }
                    "enclosure" -> {
                        enclosure("url", "length")?.let(enclosures::add)
                        skip()
                    }
                    "guid" -> {
                        guidIsLink = p.getAttributeValue(null, "isPermaLink") != "false"
                        guid = readText()
                    }
                    "id" -> guid = readText()
                    "encoded" -> if (ns == CONTENT_NS) content = readText() else skip()
                    "content" -> if (ns in FEED_NAMESPACES) content = readAtomContent() else skip()
                    "description", "summary" -> if (ns in FEED_NAMESPACES) summary = readAtomContent() else skip()
                    "creator" -> if (ns == DC_NS) author = readText() else skip()
                    "author" -> if (ns in FEED_NAMESPACES) author = readAuthor() else skip()
                    "pubDate", "published", "issued" -> published = readText()
                    "date" -> if (ns == DC_NS) published = readText() else skip()
                    "updated", "modified" -> updated = readText()
                    else -> skip()
                }
            }
            val url = link?.takeIf { it.isNotBlank() }
                ?: guid?.takeIf { guidIsLink && it.startsWith("http") }
                ?: return null
            val absolute = resolve(url)
            return FeedItem(
                guid = guid?.takeIf { it.isNotBlank() } ?: absolute,
                url = absolute,
                title = cleanTitle(title).ifBlank { absolute },
                contentHtml = (content?.takeIf { it.isNotBlank() } ?: summary?.takeIf { it.isNotBlank() }),
                author = author?.trim()?.takeIf { it.isNotEmpty() },
                published = FeedDates.parse(published) ?: FeedDates.parse(updated),
                enclosures = enclosures,
                durationSeconds = duration,
            )
        }

        /** The attachment the tag at the cursor names, read from its attributes; the tag is left unread. */
        private fun enclosure(urlAttribute: String, lengthAttribute: String): Enclosure? {
            val url = p.getAttributeValue(null, urlAttribute)?.trim()?.takeIf { it.isNotEmpty() }?.let(::resolve)?.takeIf(::isWebAddress) ?: return null
            val type = p.getAttributeValue(null, "type")?.trim()
                ?: p.getAttributeValue(null, "medium")?.trim()?.let { "$it/*" }
                ?: ""
            return Enclosure(url, type.lowercase(), p.getAttributeValue(null, lengthAttribute)?.trim()?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
        }

        /** RSS <link>text</link>, or Atom <link rel="alternate" href=...>; other rels give null. */
        private fun readLink(): String? {
            val href = p.getAttributeValue(null, "href")
            if (href != null) {
                val rel = p.getAttributeValue(null, "rel") ?: "alternate"
                skip()
                return if (rel == "alternate") href.trim() else null
            }
            return readText().trim().takeIf { it.isNotEmpty() }
        }

        private fun readAuthor(): String? {
            // RSS <author> is text (often an email); Atom's has a <name> child.
            val depth = p.depth
            var name: String? = null
            val text = StringBuilder()
            while (!(next() == XmlPullParser.END_TAG && p.depth == depth)) {
                when (p.eventType) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(p.text)
                    XmlPullParser.START_TAG -> if (p.name == "name") name = readText() else skip()
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return name ?: text.toString().trim().takeIf { it.isNotEmpty() }
        }

        /** Atom type="xhtml" content is inline markup, not text; serialize it back to HTML. */
        private fun readAtomContent(): String {
            if (p.getAttributeValue(null, "type") != "xhtml") return readText()
            val depth = p.depth
            val out = StringBuilder()
            while (!(next() == XmlPullParser.END_TAG && p.depth == depth)) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        // The wrapping <div xmlns=xhtml> is required by the spec, not content.
                        if (p.depth == depth + 1 && p.name == "div" && p.namespace == XHTML_NS) continue
                        out.append('<').append(p.name)
                        for (i in 0 until p.attributeCount) {
                            out.append(' ').append(p.getAttributeName(i)).append("=\"")
                                .append(esc(p.getAttributeValue(i))).append('"')
                        }
                        out.append('>')
                    }
                    XmlPullParser.END_TAG ->
                        if (!(p.depth == depth + 1 && p.name == "div" && p.namespace == XHTML_NS)) {
                            out.append("</").append(p.name).append('>')
                        }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> out.append(esc(p.text))
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return out.toString()
        }

        private fun readText(): String {
            val depth = p.depth
            val out = StringBuilder()
            while (!(next() == XmlPullParser.END_TAG && p.depth == depth)) {
                when (p.eventType) {
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> out.append(p.text)
                    XmlPullParser.END_DOCUMENT -> break
                }
            }
            return out.toString()
        }

        // The parser's work at each start tag grows with the depth, so a 10 MB feed nested a
        // million deep would take hours; nothing real nests past a few dozen.
        private fun next(): Int {
            val event = p.next()
            if (event == XmlPullParser.START_TAG && p.depth > MAX_DEPTH) throw TooDeep()
            return event
        }

        private fun skip() {
            val depth = p.depth
            while (!(next() == XmlPullParser.END_TAG && p.depth == depth)) {
                if (p.eventType == XmlPullParser.END_DOCUMENT) break
            }
        }

        private fun resolve(url: String) = resolveUrl(feedUrl, url)
    }

    private fun parseJson(text: String, feedUrl: String): Feed {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            throw FeedParseException("Not a readable JSON Feed: ${e.message}", e)
        } catch (e: StackOverflowError) {
            // The parser recurses once per level of nested arrays, so a few KB of [ overflows it.
            // Left as an Error, it gets past every catch (e: Exception) between here and the
            // edition, and stops every edition while the feed stays subscribed.
            throw FeedParseException("Not a readable JSON Feed: nested too deeply", e)
        } ?: throw FeedParseException("Not a JSON Feed")
        if (root.str("version")?.contains("jsonfeed.org") != true) throw FeedParseException("Not a JSON Feed")
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val url = (o.str("url") ?: o.str("external_url"))?.let { resolveUrl(feedUrl, it) } ?: return@mapNotNull null
            val author = (o["authors"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("name") }
                ?: (o["author"] as? JsonObject)?.str("name")
            val attachments = (o["attachments"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject) }
            FeedItem(
                guid = o.str("id") ?: url,
                url = url,
                title = cleanTitle(o.str("title")).ifBlank { url },
                contentHtml = o.str("content_html") ?: o.str("content_text")?.let(HtmlCleaner::textToHtml) ?: o.str("summary"),
                author = author,
                published = FeedDates.parse(o.str("date_published")) ?: FeedDates.parse(o.str("date_modified")),
                enclosures = attachments.mapNotNull { a ->
                    a.str("url")?.let { resolveUrl(feedUrl, it) }?.takeIf(::isWebAddress)
                        ?.let { Enclosure(it, a.str("mime_type").orEmpty().lowercase(), a.str("size_in_bytes")?.toLongOrNull()?.coerceAtLeast(0) ?: 0) }
                },
                durationSeconds = attachments.firstNotNullOfOrNull { it.str("duration_in_seconds")?.toDoubleOrNull()?.toInt()?.takeIf { s -> s > 0 } },
            )
        }
        return Feed(root.str("title"), root.str("home_page_url"), newest(items))
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }


    /** Titles may be entity-encoded HTML ("Q&amp;A", "<em>New</em>"); reduce to plain text. */
    internal fun cleanTitle(raw: String?): String {
        val t = raw?.trim() ?: return ""
        val plain = if (t.contains('<') || t.contains('&')) Jsoup.parse(t).text() else t
        return plain.replace(Regex("\\s+"), " ").trim()
    }

}

/** An http or https address: an attachment anywhere else (file:, content:, javascript:) is no one's episode. */
fun isWebAddress(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/**
 * [url] made absolute against [base]. A link java.net.URI refuses (a `|` or a brace in its query)
 * goes through the stricter cleaner, which percent-encodes it; one even that can't read is kept as
 * it came.
 */
internal fun resolveUrl(base: String, url: String): String = try {
    val baseUri = URI(base)
    // Against "https://host" (no path) Android's URI glues the reference onto the host name.
    val from = if (baseUri.rawPath.isNullOrEmpty() && !baseUri.isOpaque) baseUri.resolve("/") else baseUri
    from.resolve(url.trim().replace(" ", "%20")).toString()
} catch (_: Exception) {
    HtmlCleaner.absoluteUrl(url, base, HtmlCleaner.LINK_SCHEMES) ?: url.trim()
}
