package com.app.newspaperss.listen

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Which articles the reader has turned back to Listen's voice from their recording, by edition
 * and page. Kept, so the choice holds across restarts.
 */
interface ReadingChoices {
    fun voiceInstead(editionId: Long, page: Int): Boolean

    fun setVoiceInstead(editionId: Long, page: Int, instead: Boolean)
}

class StoredReadingChoices(context: Context) : ReadingChoices {
    private val prefs = context.getSharedPreferences("reading-choices", Context.MODE_PRIVATE)

    override fun voiceInstead(editionId: Long, page: Int) = prefs.getBoolean("$editionId:$page", false)

    override fun setVoiceInstead(editionId: Long, page: Int, instead: Boolean) {
        prefs.edit().apply { if (instead) putBoolean("$editionId:$page", true) else remove("$editionId:$page") }.apply()
    }

    /** Drops an edition's choices once its book is gone, when Listen can't play it again. */
    fun forget(editionId: Long) {
        val keys = prefs.all.keys.filter { it.startsWith("$editionId:") }
        if (keys.isNotEmpty()) prefs.edit().apply { keys.forEach(::remove) }.apply()
    }
}

/**
 * Listen's voice, with an article's recording in its place where it has one ([Reading]): the voice
 * reads the article's heading, says what's coming ("Now its recorded reading, 12 minutes.", or for
 * a transcript "Now the episode itself, 1 hour 27 minutes."), and
 * the recording plays the rest. The reader can turn an article back to the voice
 * ([setVoiceInstead]), and a recording that won't play hands back to the voice from where it got.
 *
 * Playing a recording, it reports each line's start as the recording reaches its share of the
 * article, by how long the voice would take over the lines before it, so the player tints, saves
 * and moves on as it does for the voice. It's only near: a reading isn't timed sentence by
 * sentence, and an author strays from the text.
 *
 */
class ReadingSpeaker(
    private val voice: Speaker,
    private val audio: ReadingAudio,
    private val readingAt: (editionId: Long, page: Int) -> Reading?,
    private val choices: ReadingChoices,
    private val log: (String) -> Unit = {},
) : Speaker {
    override var listener: Speaker.Listener? = null

    /** Plays on from a line, as a tap on it does: the voice takes over there when a recording fails. */
    var restart: (LineId) -> Unit = {}

    /** The article that has a recording, while it's the one playing: shown, with the switch. */
    data class Now(val editionId: Long, val page: Int, val reading: Reading, val voiceInstead: Boolean)

    private val _now = MutableStateFlow<Now?>(null)
    val now: StateFlow<Now?> = _now.asStateFlow()

    private var article: Pair<Long, Int>? = null
    /** The recording the current article plays, or null in the voice. */
    private var reading: Reading? = null
    private val failed = mutableSetOf<Pair<Long, Int>>()

    /** The body's first line, queued behind the heading: the handover starts once the heading's said. */
    private var waiting: LineId? = null
    /** The handover being said, for the line the recording starts from. */
    private var handingOver: LineId? = null
    private var rate = 1f
    /** Articles whose recording has been announced: once is enough. */
    private val announced = mutableSetOf<Pair<Long, Int>>()
    /** How long each recording is, as the player found: by address. */
    private val lengths = mutableMapOf<String, Long>()
    /** A share of the recording to seek to once its length is known: asked for before it was. */
    private var seekWhenKnown: Double? = null

    /** What's playing: the line it started from, and the last line reported. */
    private var playing: LineId? = null
    private var reported = -1
    /** How far into the recording it last was, for the log when it stops. */
    private var atMs = 0L

    init {
        voice.listener = object : Speaker.Listener {
            override fun onStart(id: String) {
                if (!id.startsWith(HANDOVER)) listener?.onStart(id.removePrefix(HEADING))
            }

            override fun onDone(id: String) {
                if (id.startsWith(HANDOVER)) return handedOver(id)
                val own = id.removePrefix(HEADING)
                listener?.onDone(own)
                headingSaid(own)
            }

            override fun onError(id: String) {
                // A handover the voice can't say is skipped: the recording plays anyway.
                if (id.startsWith(HANDOVER)) return handedOver(id)
                val own = id.removePrefix(HEADING)
                listener?.onError(own)
                // A heading line the voice couldn't say is past too: the recording mustn't wait for it.
                headingSaid(own)
            }
        }
        audio.listener = object : ReadingAudio.Listener {
            override fun onPosition(ms: Long, durationMs: Long) = reach(ms, durationMs)
            override fun onEnded() = ended()
            override fun onError(code: String) = failedToPlay(code)
        }
    }

    override fun speak(id: String, text: String, language: String?, rate: Float, flush: Boolean): Boolean {
        val line = LineId.parse(id)
        if (line == null) {
            // Not a line of an edition (Settings' sample): the voice, the recording stopped.
            stopRecording()
            return voice.speak(id, text, language, rate, flush)
        }
        val here = line.editionId to line.page
        if (here != article || flush) choose(here)
        if (flush) stopRecording()
        val reading = reading ?: return voice.speak(id, text, language, rate, flush)
        // The heading, as plain speech rather than a line of the edition: a podcast voice given
        // the article's line would play the whole article from its own audio.
        if (line.line < reading.bodyLine) return voice.speak("$HEADING$id", text, language, rate, flush)
        this.rate = rate
        if (!flush) {
            // The recording carries on through it, or the handover's on its way.
            if (playing == null && handingOver == null) waiting = line
            return true
        }
        voice.stop()
        // Announced once an article: not again on every pause and play.
        if (line.line == reading.bodyLine && here !in announced) handOver(line) else play(line)
        return true
    }

    /** Turns [page] of [editionId] back to the voice, or to its recording again; the player then plays on from [line]. */
    fun setVoiceInstead(editionId: Long, page: Int, instead: Boolean, line: Int) {
        choices.setVoiceInstead(editionId, page, instead)
        // Asked for again after it failed: worth another try, the connection may be back.
        if (!instead) failed -= editionId to page
        val reading = readingAt(editionId, page)
        log("Reading: edition $editionId page $page ${if (instead) "switched to the voice" else "back to its recording"} at line $line (${what(reading)}, ${reading?.url})")
        _now.value = _now.value?.takeIf { it.editionId == editionId && it.page == page }?.copy(voiceInstead = instead)
        article = null
    }

    private fun choose(here: Pair<Long, Int>) {
        article = here
        waiting = null
        handingOver = null
        val (editionId, page) = here
        val found = readingAt(editionId, page)
        // One that failed to play is the voice's too, until the reader asks for it again.
        val instead = found != null && (choices.voiceInstead(editionId, page) || here in failed)
        _now.value = found?.let { Now(editionId, page, it, instead) }
        reading = found?.takeIf { !instead }
    }

    /** The heading's last line is over, with the body queued behind it: time to hand over. */
    private fun headingSaid(id: String) {
        val line = LineId.parse(id) ?: return
        waiting?.takeIf { it.copy(line = it.line - 1) == line }?.let(::handOver)
    }

    private fun handOver(line: LineId) {
        val reading = reading ?: return
        waiting = null
        handingOver = line
        article?.let { announced += it }
        voice.speak("$HANDOVER$line", announce(reading), null, rate, flush = true)
    }

    private fun handedOver(id: String) {
        val line = handingOver?.takeIf { "$HANDOVER$it" == id } ?: return
        handingOver = null
        play(line)
    }

    private fun play(from: LineId) {
        val reading = reading ?: return
        stopRecording()
        playing = from
        reported = from.line - 1
        // By the length the player found, as reach() reaches lines by, else the feed's. Without
        // either, it starts from the beginning and seeks once the player knows the length.
        val total = lengths[reading.url] ?: reading.seconds?.let { it * 1000L }
        val share = reading.shareAt(from.line)
        val ms = total?.let { (share * it).toLong() } ?: 0L
        seekWhenKnown = share.takeIf { total == null && it > 0 }
        atMs = ms
        log("Reading: edition ${from.editionId} page ${from.page} from line ${from.line}: ${reading.url} at ${ms / 1000}s, speed ${"%.1f".format(Locale.ROOT, rate)}")
        audio.play(reading.url, ms, rate)
    }

    /** Reports every line the recording has reached, [ms] into it. */
    private fun reach(ms: Long, durationMs: Long) {
        val at = playing ?: return
        val reading = reading ?: return
        if (durationMs > 0) lengths[reading.url] = durationMs
        seekWhenKnown?.let { share ->
            if (durationMs <= 0) return
            seekWhenKnown = null
            atMs = (share * durationMs).toLong()
            audio.play(reading.url, atMs, rate)
            return
        }
        atMs = ms
        val total = durationMs.takeIf { it > 0 } ?: reading.seconds?.let { it * 1000L } ?: return
        val share = (ms + Media3Audio.TICK_MS).toDouble() / total
        val upTo = reading.bodyLine + reading.starts.indexOfLast { it <= share }
        while (reported < upTo.coerceAtMost(at.lines - 1)) {
            reported++
            if (reported >= at.line) listener?.onStart(at.copy(line = reported).toString())
        }
    }

    private fun ended() {
        val at = playing ?: return
        // The rest of the lines, however far the ticks got: not through reach(), which would
        // learn a length from it.
        while (reported < at.lines - 1) {
            reported++
            if (reported >= at.line) listener?.onStart(at.copy(line = reported).toString())
        }
        reading?.let { r -> (lengths[r.url] ?: r.seconds?.let { it * 1000L })?.let { atMs = it } }
        stopRecording("played to the end")
        listener?.onDone(at.copy(line = at.lines - 1).toString())
    }

    /** The recording won't play (no connection, gone): the voice reads on from where it got to, and keeps this article. */
    private fun failedToPlay(code: String) {
        val at = playing ?: return
        article?.let { failed += it }
        val line = maxOf(reported, at.line)
        log("Reading: ${reading?.url} failed ($code); the voice from line $line")
        stopRecording()
        reading = null
        _now.value = _now.value?.copy(voiceInstead = true)
        restart(at.copy(line = line))
    }

    /**
     * Stops the recording, logging how far it got when there's a [why]: whether readings are
     * listened to or left. Every pause, skip and tap stops it, so a stop followed by the same
     * page playing on is a pause.
     */
    private fun stopRecording(why: String? = null) {
        handingOver = null
        seekWhenKnown = null
        val at = playing ?: return
        playing = null
        audio.stop()
        if (why == null) return
        val total = reading?.let { r -> lengths[r.url] ?: r.seconds?.let { it * 1000L } }
        val of = total?.takeIf { it > 0 }?.let { " of ${clock(it)} (${(100 * atMs / it).coerceIn(0, 100)}%)" } ?: ""
        log("Reading: edition ${at.editionId} page ${at.page} $why at ${clock(atMs)}$of")
    }

    override fun stop() {
        waiting = null
        stopRecording("stopped")
        voice.stop()
    }

    override fun release() {
        stopRecording()
        audio.release()
        voice.release()
    }

    private companion object {
        /** Ids of the voice's announcement before a recording: not a line of the page. */
        const val HANDOVER = "reading:"

        /** Ids of the heading lines of an article with a recording, said as plain speech. */
        const val HEADING = "heading:"

        fun what(reading: Reading?) = when {
            reading?.episode == true -> "its episode"
            reading?.own == true -> "its own audio"
            else -> "its audio version"
        }

        fun clock(ms: Long) = (ms / 1000).let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) }

        fun announce(reading: Reading): String {
            val what = if (reading.episode) "Now the episode itself" else "Now its recorded reading"
            return reading.seconds?.let { "$what, ${spokenLength(it)}." } ?: "$what."
        }
    }
}
