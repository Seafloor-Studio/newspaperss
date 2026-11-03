package com.app.newspaperss.listen

import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.settings.Recordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * An episode Listen plays after the edition (a notes post's), for the edition's page.
 *
 * @param page its page in Listen, past the closing page.
 * @param heardSeconds how far into it Listen got, or null if it was never started (or was heard).
 * @param line where Listen plays it from: its own place, or its start once heard.
 */
data class EpisodeAfter(
    val page: Int,
    val title: String,
    val source: String?,
    val seconds: Int?,
    val heardSeconds: Int? = null,
    val finished: Boolean = false,
    val line: Int = 0,
)

/**
 * The episodes after an edition, found as Listen finds them, with how far each was heard
 * ([ListenProgress.episode]): read again whenever [moves] does, so the edition's page keeps up
 * with Listen.
 */
class EpisodesAfter(
    private val editions: EditionRepository,
    private val db: AppDatabase,
    private val progress: ListenProgress,
    private val choices: suspend () -> Recordings,
    private val moves: Flow<Any?>,
) {
    fun of(editionId: Long): Flow<List<EpisodeAfter>> {
        val found = flow {
            // Its own Readings: Listen's has the edition being heard, which may be another.
            val readings = Readings(db, choices)
            val episodes = ListenBook.open(editions, editionId, readings)?.use { book ->
                val first = book.pages.indexOfFirst { it.episode }
                readings.ending(editionId).mapIndexed { i, end -> first + i to end }
            }.orEmpty()
            emit(episodes)
        }.flowOn(Dispatchers.IO)
        return combine(found, moves.distinctUntilChanged()) { episodes, _ ->
            episodes.map { (page, end) ->
                val place = progress.episode(editionId, end.reading.url)?.takeUnless { it.finished }
                EpisodeAfter(
                    page, end.title, end.source, end.reading.seconds,
                    heardSeconds = place?.let { maxOf(0, it.line - end.reading.bodyLine) * EndEpisode.MARK_SECONDS },
                    finished = progress.episode(editionId, end.reading.url)?.finished == true,
                    line = place?.line ?: 0,
                )
            }
        }.distinctUntilChanged()
    }
}
