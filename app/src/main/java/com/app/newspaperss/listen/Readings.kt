package com.app.newspaperss.listen

import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.episodes.AudioLength
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.EditionContent
import kotlinx.coroutines.CancellationException

/**
 * A recording that plays in an article's place instead of Listen's voice: its audio version (a
 * reading posted beside it), its own audio when that's about as long as its text read aloud, or,
 * for a transcript, its episode.
 *
 * @param bodyLine the page's first line after its heading: the voice reads the source, title and
 *   author, then hands over to the recording.
 * @param starts each line's share of the way through the recording, from [bodyLine] on, by how
 *   long the voice would take over it: where a line is reached, and where a tap on one plays from.
 * @param seconds how long the recording plays, if known.
 * @param own the post's own audio, not its audio version's or its notes post's.
 * @param episode the episode a transcript records, rather than a reading of an article.
 */
data class Reading(val url: String, val seconds: Int?, val own: Boolean, val bodyLine: Int, val starts: List<Double>, val episode: Boolean = false) {
    /** The share of the recording before [line]. */
    fun shareAt(line: Int): Double = starts.getOrElse(line - bodyLine) { if (line < bodyLine) 0.0 else 1.0 }
}

/**
 * Which pages of the edition Listen has open play a recording (see [Reading]). Worked out when
 * the edition is opened, so the voice can be chosen at once as each article starts.
 */
class Readings(private val db: AppDatabase, private val log: (String) -> Unit = {}) {
    /** The edition Listen has open, and its pages' recordings: only one is open at a time. */
    @Volatile private var loaded: Pair<Long, Map<Int, Reading>>? = null

    /** The recording that plays for [page] of [editionId], if it has one. */
    fun at(editionId: Long, page: Int): Reading? = loaded?.takeIf { it.first == editionId }?.second?.get(page)

    /**
     * Finds the recordings for an edition's [contents], in book order, reading the pages that may
     * have one with [script]. Logs what it found, and why each transcript the voice reads has no
     * episode to play: how often each reason comes up, on a reader's own feeds.
     */
    suspend fun load(editionId: Long, contents: List<EditionContent>, script: suspend (page: Int) -> ListenScript?) {
        val found = mutableMapOf<Int, Reading>()
        val notes = mutableListOf<String>()
        val read = mutableListOf<String>()
        contents.forEachIndexed { page, content ->
            val article = content.entry.articleId?.let { db.articles().byId(it) } ?: return@forEachIndexed
            val reading = try {
                when (article.episodeKind) {
                    EpisodeKind.ARTICLE -> readingOf(article, page, script)
                    EpisodeKind.TRANSCRIPT -> episodeOf(article, page, script).also { (_, why) -> why?.let { read += "page $page (transcript: $it)" } }.first
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A page that won't read costs its own recording, not the edition's.
                read += "page $page (its page wouldn't read)"
                null
            } ?: return@forEachIndexed
            // Each recording plays once: a second post with the same one is read by the voice.
            val first = found.entries.firstOrNull { it.value.url == reading.url }
            if (first != null) {
                read += "page $page (its recording plays on page ${first.key})"
                return@forEachIndexed
            }
            found[page] = reading
            notes += "page $page (${describe(reading)})"
        }
        loaded = editionId to found
        log(
            "Readings: edition $editionId, recorded readings: ${notes.joinToString().ifEmpty { "none" }}" +
                if (read.isEmpty()) "" else "; read by the voice instead: ${read.joinToString()}",
        )
    }

    private suspend fun readingOf(article: ArticleEntity, page: Int, script: suspend (page: Int) -> ListenScript?): Reading? {
        val pair = article.episodePair?.let { db.articles().byGuid(article.sourceId, it) }
        // As AudioLengths decides what to time: a paired article plays its pair's audio or none.
        val audio = when {
            article.episodePair != null -> pair?.takeIf { it.episodeKind == EpisodeKind.AUDIO_VERSION && it.audioUrl != null }
            article.audioUrl != null && article.audioSeconds != null -> article
            else -> null
        } ?: return null
        val lines = script(page)?.let(::bodyOf) ?: return null
        // An article's own audio is a reading of it only if it's about as long: otherwise
        // it's an episode the article is about, a song, a preview.
        if (audio === article && !AudioLength.isReading(article.audioSeconds!!, lines.second.sumOf { ReadingTime.words(it.spoken) })) return null
        return Reading(audio.audioUrl!!, audio.audioSeconds, own = audio === article, bodyLine = lines.first, starts = shares(lines.second))
    }

    /**
     * A transcript's episode, its own audio or else its notes post's, the first long enough not to
     * be a preview and short enough to be what was transcribed; else why neither.
     */
    private suspend fun episodeOf(article: ArticleEntity, page: Int, script: suspend (page: Int) -> ListenScript?): Pair<Reading?, String?> {
        val pair = article.episodePair?.let { db.articles().byGuid(article.sourceId, it) }?.takeIf { it.episodeKind == EpisodeKind.NOTES }
        val candidates = listOfNotNull(article, pair).filter { it.audioUrl != null }
        if (candidates.isEmpty()) return null to "no audio"
        val lines = script(page)?.let(::bodyOf) ?: return null to "its page wouldn't read"
        val words = lines.second.sumOf { ReadingTime.words(it.spoken) }
        val why = candidates.map { audio ->
            val from = if (audio === article) "its own audio" else "its notes post's"
            // Without a length a preview can't be told from the episode.
            val seconds = audio.audioSeconds ?: return@map "$from, length unknown"
            if (!AudioLength.isEpisodeOf(seconds, words)) return@map "$from, ${minutes(seconds)} against ${minutes(AudioLength.readSeconds(words))} read aloud"
            return Reading(audio.audioUrl!!, seconds, own = audio === article, bodyLine = lines.first, starts = shares(lines.second), episode = true) to null
        }
        return null to why.joinToString("; ")
    }

    private fun describe(r: Reading): String = when {
        r.episode -> "its episode, ${if (r.own) "its own audio" else "its notes post's"}, ${minutes(r.seconds)}"
        r.own -> "its own audio, ${minutes(r.seconds)}"
        else -> "its audio version, ${minutes(r.seconds)}"
    }

    private fun minutes(seconds: Int?) = seconds?.let { "${(it + 30) / 60} min" } ?: "length unknown"

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
