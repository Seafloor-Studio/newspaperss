package com.app.newspaperss.listen

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.episodes.AudioLength
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionContent

/**
 * A recording that plays in an article's place instead of Listen's voice: its audio version (a
 * reading posted beside it), or its own audio when that's about as long as its text read aloud.
 *
 * @param bodyLine the page's first line after its heading: the voice reads the source, title and
 *   author, then hands over to the recording.
 * @param starts each line's share of the way through the recording, from [bodyLine] on, by how
 *   long the voice would take over it: where a line is reached, and where a tap on one plays from.
 * @param seconds how long the recording plays, if known.
 */
data class Reading(val url: String, val seconds: Int?, val own: Boolean, val bodyLine: Int, val starts: List<Double>) {
    /** The share of the recording before [line]. */
    fun shareAt(line: Int): Double = starts.getOrElse(line - bodyLine) { if (line < bodyLine) 0.0 else 1.0 }
}

/**
 * Which pages of the edition Listen has open play a recording (see [Reading]). Worked out when
 * the edition is opened, so the voice can be chosen at once as each article starts.
 */
class Readings(private val db: AppDatabase) {
    /** The edition Listen has open, and its pages' recordings: only one is open at a time. */
    @Volatile private var loaded: Pair<Long, Map<Int, Reading>>? = null

    /** The recording that plays for [page] of [editionId], if it has one. */
    fun at(editionId: Long, page: Int): Reading? = loaded?.takeIf { it.first == editionId }?.second?.get(page)

    /**
     * Finds the recordings for an edition's [contents], in book order, reading the pages that may
     * have one with [script].
     */
    suspend fun load(editionId: Long, contents: List<EditionContent>, script: suspend (page: Int) -> ListenScript?) {
        val found = mutableMapOf<Int, Reading>()
        contents.forEachIndexed { page, content ->
            val article = content.entry.articleId?.let { db.articles().byId(it) } ?: return@forEachIndexed
            if (article.episodeKind != EpisodeKind.ARTICLE) return@forEachIndexed
            val pair = article.episodePair?.let { db.articles().byGuid(article.sourceId, it) }
            // As AudioLengths decides what to time: a paired article plays its pair's audio or none.
            val audio = when {
                article.episodePair != null -> pair?.takeIf { it.episodeKind == EpisodeKind.AUDIO_VERSION && it.audioUrl != null }
                article.audioUrl != null && article.audioSeconds != null -> article
                else -> null
            } ?: return@forEachIndexed
            val lines = script(page)?.let(::bodyOf) ?: return@forEachIndexed
            // An article's own audio is a reading of it only if it's about as long: otherwise
            // it's an episode the article is about, a song, a preview.
            if (audio === article && !AudioLength.isReading(article.audioSeconds!!, lines.second.sumOf { ReadingTime.words(it.spoken) })) return@forEachIndexed
            found[page] = Reading(audio.audioUrl!!, audio.audioSeconds, own = audio === article, bodyLine = lines.first, starts = shares(lines.second))
        }
        loaded = editionId to found
    }

    /** The first line after the heading, and the lines from there. */
    private fun bodyOf(script: ListenScript): Pair<Int, List<ListenScript.Line>>? {
        val heading = setOf(ListenScript.Kind.KICKER, ListenScript.Kind.TITLE, ListenScript.Kind.BYLINE)
        val first = script.lines.indexOfFirst { (script.blocks[it.block] as? ListenScript.Block.Text)?.kind !in heading }
        return if (first < 0) null else first to script.lines.drop(first)
    }

    private fun shares(lines: List<ListenScript.Line>): List<Double> {
        val seconds = lines.map { ListenTime.seconds(it.spoken) }
        val total = seconds.sum().takeIf { it > 0 } ?: return lines.indices.map { it.toDouble() / lines.size }
        return seconds.runningFold(0.0) { acc, s -> acc + s }.dropLast(1).map { it / total }
    }
}
