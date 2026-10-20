package com.app.newspaperss.data

/**
 * The most a feed's text fields may put in a row. Android reads a row through a 2 MB window, and
 * one row over it fails every query that loads it: a source's page, and every edition's choice of
 * articles. A hostile or broken feed (a megabyte title, a `data:` link) shouldn't be able to do
 * that. Real titles, names and links are far under these.
 */
object RowLimits {
    const val MAX_TITLE = 1_000
    const val MAX_AUTHOR = 300
    /** A link this long is a picture inlined as a `data:` link, or junk: cut, it would lead somewhere else. */
    const val MAX_LINK = 4_000

    /** [text] cut to [max] whole characters, an emoji never in half, with "…" to show it was cut. */
    fun cut(text: String, max: Int): String =
        if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max - 1)).trimEnd() + "…"

    fun link(url: String?): String? = url?.takeIf { it.length <= MAX_LINK }

    /**
     * [article] as it can be stored, or null when one of its links or its guid is too long to keep:
     * a link can't be shortened, and a cut guid could match another post's.
     */
    fun storable(article: ArticleEntity): ArticleEntity? {
        val links = listOfNotNull(article.url, article.guid, article.viaUrl, article.audioUrl)
        if (links.any { it.length > MAX_LINK }) return null
        return article.copy(
            title = cut(article.title, MAX_TITLE),
            author = article.author?.let { cut(it, MAX_AUTHOR) },
            originTitle = article.originTitle?.let { cut(it, MAX_TITLE) },
            audioType = article.audioType?.let { cut(it, MAX_AUTHOR) },
            // Another post's guid: one too long to keep was never stored.
            episodePair = link(article.episodePair),
        )
    }
}
