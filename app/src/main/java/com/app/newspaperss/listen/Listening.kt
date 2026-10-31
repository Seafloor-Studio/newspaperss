package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.settings.PodcastVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** An edition left part way through: offered before starting a newer one. */
data class Unfinished(val editionId: Long, val title: String, val minutesLeft: Double)

/**
 * Listen, as the screens offer it: where each edition was left, starting one, and what the
 * article view says about the voice playing.
 */
class Listening(
    val player: ListenPlayer,
    private val progress: ListenProgress,
    private val editions: EditionRepository,
    /** Podcasts made ahead, shown under Listen while Kokoro is in use; null without them. */
    val podcasts: Podcasts? = null,
    /** The natural voice while an article plays in it; null in the phone's. */
    val podcastVoice: StateFlow<PodcastVoice?> = MutableStateFlow(null),
    /** Why an article of an edition with a podcast plays in the phone's voice. */
    val instead: StateFlow<PodcastSpeaker.Instead?> = MutableStateFlow(null),
    /** The article with a recording, while it's the one playing; null when there's none. */
    val reading: StateFlow<ReadingSpeaker.Now?> = MutableStateFlow(null),
    /** Turns the article back to the voice (true), or to its recording again. */
    private val voiceInstead: (Boolean) -> Unit = {},
    /** Binds the service that keeps it playing with the screen off. */
    private val connect: () -> Unit,
) {
    fun useVoiceInstead(instead: Boolean) = voiceInstead(instead)

    /** Where [editionId] was left, unless it was heard to the end or never started. */
    fun saved(editionId: Long): ListenPosition? = progress.get(editionId)?.takeUnless { progress.finished(editionId) }

    /**
     * The most recently heard edition older than [editionId] that was left part way, if its book
     * is still here: yesterday's paper, to finish before today's.
     */
    suspend fun earlierUnfinished(editionId: Long): Unfinished? {
        for (id in progress.unfinished()) {
            if (id >= editionId) continue
            val edition = editions.byId(id) ?: continue
            if (edition.status == EditionStatus.DELETED || editions.fileOf(edition) == null) continue
            val at = progress.get(id) ?: continue
            val contents = editions.observeContents(id).first().sortedBy { it.entry.position }
            val left = contents.drop(at.page).sumOf { ListenTime.fromReading(it.entry.minutes) }
            return Unfinished(id, edition.title, left)
        }
        return null
    }

    /** Plays or pauses the edition open in the player; playing binds the service first, as [start] does. */
    fun toggle() {
        if (player.state.value.playing) {
            player.pause()
        } else {
            connect()
            player.play()
        }
    }

    /** Plays [editionId] from [from], or from where it was left. */
    fun start(editionId: Long, from: ListenPosition? = null) {
        connect()
        player.start(editionId, from)
    }
}
