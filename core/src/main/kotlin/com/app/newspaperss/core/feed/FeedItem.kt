package com.app.newspaperss.core.feed

import java.time.Instant

/** @param podcast the feed says it's a podcast, with Apple Podcasts' channel tags. */
data class Feed(
    val title: String?,
    val siteUrl: String?,
    val items: List<FeedItem>,
    val podcast: Boolean = false,
)

/**
 * One entry from a feed.
 *
 * @param guid the feed's own id for the entry, or its url when it has none.
 * @param contentHtml the fullest body the feed offers (content:encoded, Atom
 *   content, JSON Feed content_html), or its summary if that's all there is.
 * @param enclosures files attached to the entry: a podcast's audio, a video, a picture.
 * @param durationSeconds how long its audio runs, when the feed says (itunes:duration).
 */
data class FeedItem(
    val guid: String,
    val url: String,
    val title: String,
    val contentHtml: String?,
    val author: String?,
    val published: Instant?,
    val enclosures: List<Enclosure> = emptyList(),
    val durationSeconds: Int? = null,
)

/**
 * A file attached to a feed entry.
 *
 * @param length its size in bytes as the feed gives it; 0 when it doesn't (Substack never does).
 */
data class Enclosure(val url: String, val type: String, val length: Long = 0)

open class FeedParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A feed over the size limit that can't be read in part; its message is fit to show the reader. */
class FeedTooLargeException(message: String) : FeedParseException(message)
