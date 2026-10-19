package com.app.newspaperss.listen

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/** Plays one recording from the web, for [ReadingSpeaker]. */
interface ReadingAudio {
    var listener: Listener?

    /** Plays [url] from [fromMs] at [speed] (1.0 normal), stopping whatever was playing. */
    fun play(url: String, fromMs: Long, speed: Float)

    fun stop()

    fun release()

    /** Called on the main thread. */
    interface Listener {
        /** Every few tens of milliseconds while playing: how far in, and how long it is (0 until known). */
        fun onPosition(ms: Long, durationMs: Long)

        /** Heard to its end. */
        fun onEnded()

        /** It can't be played (no connection, gone, not audio); [code] says why, for the log. */
        fun onError(code: String)
    }
}

/**
 * [ReadingAudio] through a Media3 player of its own, streamed: a reading is a few megabytes, and
 * the edition's Listen may never reach it. Kept from play to play, as [Media3Audio] is.
 */
class StreamAudio(private val newPlayer: () -> Player) : ReadingAudio {
    override var listener: ReadingAudio.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var made: Player? = null
    private var playing = false

    /** Bumped by each play and stop, so news posted for an earlier one is dropped. */
    private var plays = 0

    private val tick = object : Runnable {
        override fun run() {
            val p = made?.takeIf { playing } ?: return
            val play = plays
            if (p.isPlaying) listener?.onPosition(p.currentPosition, p.duration.coerceAtLeast(0))
            if (plays == play) main.postDelayed(this, Media3Audio.TICK_MS)
        }
    }

    /** Posted rather than told at once: the speaker may stop the player in answer. */
    private fun later(call: ReadingAudio.Listener.() -> Unit) {
        val play = plays
        main.post { if (plays == play) listener?.call() }
    }

    private fun player(): Player = made ?: newPlayer().also { p ->
        made = p
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playing && playbackState == Player.STATE_ENDED) later { onEnded() }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (playing) later { onError(error.errorCodeName) }
            }
        })
    }

    override fun play(url: String, fromMs: Long, speed: Float) {
        stop()
        val p = player()
        playing = true
        p.setMediaItem(MediaItem.fromUri(url), fromMs)
        p.playbackParameters = PlaybackParameters(speed)
        p.prepare()
        p.play()
        main.post(tick)
    }

    override fun stop() {
        plays++
        main.removeCallbacks(tick)
        if (!playing) return
        playing = false
        made?.stop()
    }

    override fun release() {
        stop()
        made?.release()
        made = null
    }
}
