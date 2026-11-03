package com.app.newspaperss.listen

import com.app.newspaperss.testutil.FakeSpeaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What's said around a recording, played from the natural voice's clips where there are any. */
class ClipSpeakerTest {
    private val voice = FakeSpeaker()
    private val played = mutableListOf<String>()
    private var stopped = 0
    private val audio = object : PodcastAudio {
        override var listener: PodcastAudio.Listener? = null
        override fun play(files: List<File>, index: Int, fromMs: Long, speed: Float) { played += files.single().name }
        override fun stop() { stopped++ }
        override fun release() {}
    }
    private val clips = mutableMapOf<String, File>()
    private val speaker = ClipSpeaker(voice, audio) { editionId, text -> clips[text]?.takeIf { editionId == 7L } }
    private val heard = mutableListOf<String>()

    init {
        speaker.listener = object : Speaker.Listener {
            override fun onStart(id: String) { heard += "start $id" }
            override fun onDone(id: String) { heard += "done $id" }
            override fun onError(id: String) { heard += "error $id" }
        }
    }

    private fun line(n: Int) = LineId(1, 7, 4, n, 9).toString()

    /** With no clips, the voice gets every line as it was asked, queued or not. */
    @Test
    fun withoutClipsItsTheVoiceAsBefore() {
        speaker.speak(line(0), "One.", "en", 1.2f, flush = true)
        speaker.speak("heading:${line(1)}", "Two.", "en", 1.2f, flush = false)
        assertEquals(listOf("One.", "Two."), voice.queue.map { it.text })
        voice.sayNext()
        voice.sayNext()
        assertEquals(listOf("start ${line(0)}", "done ${line(0)}", "start heading:${line(1)}", "done heading:${line(1)}"), heard)
        assertTrue(played.isEmpty())
    }

    /** A clip waits for what's before it, and what's after it waits for the clip: the order is kept. */
    @Test
    fun aClipPlaysInItsTurn() {
        clips["Rail Talk."] = File("rail.m4a")
        speaker.speak("prelude:${line(0)}", "After the edition, an episode.", null, 1f, flush = true)
        speaker.speak("heading:${line(0)}", "Rail Talk.", null, 1f, flush = false)
        speaker.speak("heading:${line(1)}", "Night trains.", null, 1f, flush = false)
        assertEquals("the clip waits for the voice", listOf("After the edition, an episode."), voice.queue.map { it.text })
        assertTrue(played.isEmpty())

        voice.sayNext()
        assertEquals(listOf("rail.m4a"), played)
        assertTrue("what follows waits for the clip", voice.queue.isEmpty())
        audio.listener!!.onEnded()
        assertEquals(listOf("Night trains."), voice.queue.map { it.text })
        voice.sayNext()
        assertEquals(
            listOf(
                "start prelude:${line(0)}", "done prelude:${line(0)}",
                "start heading:${line(0)}", "done heading:${line(0)}",
                "start heading:${line(1)}", "done heading:${line(1)}",
            ),
            heard,
        )
    }

    /** One that won't play is said by the voice instead. */
    @Test
    fun aClipThatWontPlayIsSaidInstead() {
        clips["Now the episode itself."] = File("now.m4a")
        speaker.speak("reading:${line(2)}", "Now the episode itself.", null, 1f, flush = true)
        audio.listener!!.onError(0, "decoder")
        assertEquals(listOf("Now the episode itself."), voice.queue.map { it.text })
        voice.sayNext()
        assertEquals(listOf("start reading:${line(2)}", "start reading:${line(2)}", "done reading:${line(2)}"), heard)
    }

    /** A line read from a page is never a clip, whatever it says; a new place stops a clip playing. */
    @Test
    fun aPagesOwnLineIsTheVoicesAndANewPlaceStopsAClip() {
        clips["Rail Talk."] = File("rail.m4a")
        speaker.speak(line(0), "Rail Talk.", null, 1f, flush = true)
        assertEquals(listOf("Rail Talk."), voice.queue.map { it.text })

        speaker.speak("heading:${line(0)}", "Rail Talk.", null, 1f, flush = true)
        assertEquals(listOf("rail.m4a"), played)
        speaker.speak(line(3), "0:00", null, 1f, flush = true)
        assertEquals(1, stopped)
        assertEquals(listOf("0:00"), voice.queue.map { it.text })
        audio.listener!!.onEnded()
        assertTrue("a stopped clip's end is no one's", "done heading:${line(0)}" !in heard)
    }
}
