package com.app.newspaperss.listen

import com.app.newspaperss.core.episodes.AudioLength
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds how long a post's audio plays, from the first bytes of its file, when its feed didn't say
 * ([ArticleEntity.audioSeconds]). Listen needs it for an article with audio of its own, which it
 * plays in the article's place only when the audio is about as long as the text read aloud, and
 * for the reading paired with an article, which it announces by its length.
 */
class AudioLengths(private val db: AppDatabase, private val http: HttpClient) {
    /** [measure]s an edition's articles once it's made, for at most [MEASURE_FOR]: they're for Listen, which reads an article whose length isn't known. */
    suspend fun measureEdition(editionId: Long) {
        val ids = db.editions().articleIds(editionId)
        withTimeoutOrNull(MEASURE_FOR) { measure(ids) }
    }

    /** Measures the audio of [articleIds] that Listen may play, and of the readings they pair with. */
    suspend fun measure(articleIds: Collection<Long>) = coroutineScope {
        val articles = db.articles().byIds(articleIds).filter { it.episodeKind == EpisodeKind.ARTICLE }
        val readings = articles.mapNotNull { a -> a.episodePair?.let { db.articles().byGuid(a.sourceId, it) } }
            .filter { it.episodeKind == EpisodeKind.AUDIO_VERSION }
        val unknown = (articles.filter { it.episodePair == null } + readings)
            .filter { it.audioUrl != null && it.audioSeconds == null && isMp3(it) }
            .distinctBy { it.id }
        // A few at a time: they're small requests, but an edition can hold twenty articles.
        val limit = Semaphore(4)
        unknown.map { a ->
            async { limit.withPermit { secondsOf(a.audioUrl!!)?.let { db.articles().setAudioSeconds(a.id, it) } } }
        }.awaitAll()
    }

    /** Only MP3s can be timed from their first bytes; an AAC file would be read for frames it hasn't got. */
    private fun isMp3(a: ArticleEntity): Boolean {
        val type = a.audioType?.lowercase()
        return if (type.isNullOrBlank()) a.audioUrl!!.substringBefore('?').lowercase().endsWith(".mp3") else type == "audio/mpeg" || type == "audio/mp3"
    }

    /** The audio at [url]'s length, or null if it isn't an MP3 or can't be read. */
    internal suspend fun secondsOf(url: String): Int? = try {
        val head = http.getRange(url, 0, AudioLength.HEAD)
        val total = head.total
        if (!head.isSuccessful || total == null) {
            null
        } else {
            // Podcast hosts' tags carry cover art and can run to hundreds of kilobytes: the audio is
            // read where the tag ends. A server that ignored the first range will ignore this one.
            val tag = AudioLength.tagSize(head.body)
            val frames = if (tag == 0L) head.body else http.getRange(url, tag, AudioLength.HEAD).takeIf { it.isSuccessful && it.from == tag }?.body
            // A day at most: a server's size or a frame count can be anything.
            frames?.let { AudioLength.seconds(it, total - tag)?.takeIf { s -> s > 0 && s <= DAY }?.roundToInt() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val DAY = 24 * 60 * 60.0
        const val MEASURE_FOR = 30_000L
    }
}
