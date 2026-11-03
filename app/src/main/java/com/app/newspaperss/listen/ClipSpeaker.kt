package com.app.newspaperss.listen

import java.io.File

/**
 * Listen's voice, with the natural voice's clips ([PodcastStore.clip]) for what's said around a
 * recording: its heading, the prelude and the announcement ([Readings.spokenAround]). Those aren't
 * lines read from a page, so the podcast has no audio for them, and the phone's voice would say
 * them in the middle of an edition heard in the natural voice.
 *
 * Anything without a clip goes to [voice] just as it was asked, queued or not, so the lines of an
 * edition play as they did. A clip waits for what's before it to be said, and what's asked after
 * it waits for the clip; one that won't play is said by [voice] instead.
 *
 * @param clip the clip of a text for an edition, or null: none made, or the natural voice not in use.
 */
class ClipSpeaker(
    private val voice: Speaker,
    private val audio: PodcastAudio,
    private val clip: (editionId: Long, text: String) -> File?,
) : Speaker {
    override var listener: Speaker.Listener? = null

    private class Item(val id: String, val text: String, val language: String?, val rate: Float, val clip: File?)

    /** Asked for behind a clip, in order. */
    private val queue = ArrayDeque<Item>()

    /** Handed to [voice] and not over yet: a clip waits for them. */
    private val pending = mutableSetOf<String>()

    private var playing: Item? = null

    init {
        voice.listener = object : Speaker.Listener {
            override fun onStart(id: String) {
                listener?.onStart(id)
            }

            override fun onDone(id: String) {
                pending -= id
                listener?.onDone(id)
                next()
            }

            override fun onError(id: String) {
                pending -= id
                listener?.onError(id)
                next()
            }
        }
        audio.listener = object : PodcastAudio.Listener {
            override fun onPosition(index: Int, ms: Long) {}

            override fun onEnded() {
                val item = playing ?: return
                playing = null
                listener?.onDone(item.id)
                next()
            }

            override fun onError(index: Int, code: String) {
                val item = playing ?: return
                playing = null
                // Its start was reported already: the voice says it, and its end is the voice's.
                pending += item.id
                voice.speak(item.id, item.text, item.language, item.rate, flush = false)
            }
        }
    }

    override fun speak(id: String, text: String, language: String?, rate: Float, flush: Boolean): Boolean {
        if (flush) {
            stopClip()
            queue.clear()
            pending.clear()
        }
        val clip = clipFor(id, text)
        if (clip == null && playing == null && queue.isEmpty()) {
            pending += id
            return voice.speak(id, text, language, rate, flush)
        }
        if (flush) voice.stop()
        queue.addLast(Item(id, text, language, rate, clip))
        next()
        return true
    }

    private fun next() {
        while (playing == null && queue.isNotEmpty()) {
            val item = queue.first()
            if (item.clip == null) {
                queue.removeFirst()
                pending += item.id
                voice.speak(item.id, item.text, item.language, item.rate, flush = false)
                continue
            }
            if (pending.isNotEmpty()) return
            queue.removeFirst()
            playing = item
            listener?.onStart(item.id)
            audio.play(listOf(item.clip), 0, 0L, item.rate)
        }
    }

    /** A line read from a page is the voice's own; what's said around a recording carries one's id after its kind. */
    private fun clipFor(id: String, text: String): File? {
        if (LineId.parse(id) != null) return null
        val line = LineId.parse(id.substringAfter(':')) ?: return null
        return clip(line.editionId, text)
    }

    private fun stopClip() {
        if (playing == null) return
        playing = null
        audio.stop()
    }

    override fun stop() {
        stopClip()
        queue.clear()
        pending.clear()
        voice.stop()
    }

    override fun pause() {
        stopClip()
        queue.clear()
        pending.clear()
        voice.pause()
    }

    override fun release() {
        stopClip()
        audio.release()
        voice.release()
    }
}
