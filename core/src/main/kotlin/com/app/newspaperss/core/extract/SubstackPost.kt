package com.app.newspaperss.core.extract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import java.net.URI

/**
 * A Substack post's own text, from its page's data. tt-rss strips the `data-attrs` that Substack's
 * embeds (a Note, a gallery, an @-mention) carry their content in, leaving an empty `<div>` where
 * a Note was and the sentence introducing it hanging. The page's rendered HTML has only a
 * placeholder there too, but the page ships the whole post as the feed has it, in a JSON script
 * its own code reads.
 */
internal object SubstackPost {
    /**
     * Feed text from a Substack post whose embeds were stripped: a Substack address or Substack's
     * image host (a custom domain), no `data-attrs` left anywhere, and an empty block after a
     * paragraph or heading, where a Note or gallery goes. Not one after a picture: that's its
     * stripped zoom button.
     */
    fun stripped(feedHtml: String, url: String): Boolean {
        if (DATA_ATTRS in feedHtml || !EMPTY_DIV.containsMatchIn(feedHtml)) return false
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase().orEmpty()
        return host == "substack.com" || host.endsWith(".substack.com") || CDN in feedHtml
    }

    /** The post's HTML from a Substack page's data, or null if the page doesn't carry it. */
    fun html(page: String): String? {
        // Searched before parsing: Substack's pages run to hundreds of kilobytes.
        if (PRELOADS !in page) return null
        val json = Jsoup.parse(page).selectFirst("script#$PRELOADS")?.data() ?: return null
        val data = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val post = data["post"] as? JsonObject ?: return null
        return (post["body_html"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private const val DATA_ATTRS = "data-attrs"
    private const val PRELOADS = "preloads-json"
    private const val CDN = "substackcdn.com/"
    private val EMPTY_DIV = Regex("""</(p|h[1-6])>\s*<div>\s*</div>""")
}
