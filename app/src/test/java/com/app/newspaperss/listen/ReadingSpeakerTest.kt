package com.app.newspaperss.listen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.FakeSpeaker
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Listen playing an article's recording in its place, after the voice reads its heading. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingSpeakerTest {
    private val voice = FakeSpeaker()

    /** A recording that plays nothing: the test says how far in it is. */
    private val audio = object : ReadingAudio {
        override var listener: ReadingAudio.Listener? = null
        val played = mutableListOf<Triple<String, Long, Float>>()
        var stopped = 0
        override fun play(url: String, fromMs: Long, speed: Float) { played += Triple(url, fromMs, speed) }
        override fun stop() { stopped++ }
        override fun release() {}
    }

    private val choices = object : ReadingChoices {
        val instead = mutableSetOf<Pair<Long, Int>>()
        override fun voiceInstead(editionId: Long, page: Int) = (editionId to page) in instead
        override fun setVoiceInstead(editionId: Long, page: Int, instead: Boolean) {
            if (instead) this.instead += editionId to page else this.instead -= editionId to page
        }
    }

    /** Page 0 of edition 7: a heading of two lines, then four lines of body, a quarter of the 100-second recording each. */
    private val reading = Reading("https://cdn.example/reading.mp3", 100, own = false, bodyLine = 2, starts = listOf(0.0, 0.25, 0.5, 0.75))
    private val logged = mutableListOf<String>()
    private val restarted = mutableListOf<LineId>()
    private val speaker = ReadingSpeaker(voice, audio, { e, p -> reading.takeIf { e == 7L && p == 0 } }, choices, logged::add).also {
        it.restart = { line -> restarted += line }
    }
    private val heard = mutableListOf<String>()

    private fun listen() {
        speaker.listener = object : Speaker.Listener {
            override fun onStart(id: String) { heard += "start $id" }
            override fun onDone(id: String) { heard += "done $id" }
            override fun onError(id: String) { heard += "error $id" }
        }
    }

    private fun id(line: Int, page: Int = 0) = LineId(1, 7, page, line, 6).toString()

    @Test
    fun theVoiceReadsTheHeadingSaysWhatsComingAndTheRecordingPlaysTheRest() {
        listen()
        speaker.speak(id(0), "The Example Letter.", null, 1.2f, flush = true)
        speaker.speak(id(1), "October 15.", null, 1.2f, flush = false)
        voice.sayNext()
        // The player queues the body's first line as the heading's last starts.
        speaker.speak(id(2), "Dear friends,", null, 1.2f, flush = false)
        voice.sayNext()

        assertEquals("Now its recorded reading, 2 minutes.", voice.said.last().text)
        assertTrue("nothing plays over the announcement", audio.played.isEmpty())
        voice.sayNext()
        assertEquals(listOf(Triple(reading.url, 0L, 1.2f)), audio.played)
        assertEquals(listOf("The Example Letter.", "October 15.", "Now its recorded reading, 2 minutes."), voice.said.map { it.text })

        // Lines are reported as the recording reaches their share of it, and its end ends the page.
        audio.listener!!.onPosition(0, 100_000)
        audio.listener!!.onPosition(30_000, 100_000)
        audio.listener!!.onEnded()
        assertEquals(
            listOf("start ${id(0)}", "done ${id(0)}", "start ${id(1)}", "done ${id(1)}", "start ${id(2)}", "start ${id(3)}", "start ${id(4)}", "start ${id(5)}", "done ${id(5)}"),
            heard,
        )
        assertEquals(ReadingSpeaker.Now(7, 0, reading, voiceInstead = false), speaker.now.value)
    }

    @Test
    fun howFarARecordingGotIsLoggedWhenItStopsOrEnds() {
        listen()
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)
        audio.listener!!.onPosition(30_000, 100_000)
        speaker.stop()
        assertEquals("Reading: edition 7 page 0 stopped at 0:30 of 1:40 (30%)", logged.last())

        speaker.speak(id(4), "It took a week.", null, 1f, flush = true)
        audio.listener!!.onPosition(90_000, 100_000)
        audio.listener!!.onEnded()
        assertEquals("Reading: edition 7 page 0 played to the end at 1:40 of 1:40 (100%)", logged.last())
        val count = logged.size
        speaker.stop()
        assertEquals("nothing playing, nothing to log", count, logged.size)
    }

    @Test
    fun aRecordingWhoseLengthWasntKnownLogsWhereItSoughtTo() {
        val untimed = reading.copy(seconds = null)
        val speaker = ReadingSpeaker(voice, audio, { _, _ -> untimed }, choices, logged::add)
        speaker.speak(id(4), "It took a week.", null, 1f, flush = true)
        // The player learns the length on its first tick, and the recording seeks to the line's share.
        audio.listener!!.onPosition(0, 200_000)
        assertEquals(100_000L, audio.played.last().second)
        speaker.stop()
        assertEquals("Reading: edition 7 page 0 stopped at 1:40 of 3:20 (50%)", logged.last())
    }

    /**
     * An episode after the edition: a heading of two lines, then a mark every 30 seconds of its two
     * minutes. Played again from a mark (after a pause, a call, a speed change), it plays on from
     * there, not from the top; turned back to the voice it can't be, as there's nothing to read;
     * and one that won't play is said, and passed over.
     */
    @Test
    fun anEpisodeAfterTheEditionPlaysOnFromItsMarksAndIsPassedOverWhenItWontPlay() {
        val episode = Reading("https://cdn.example/12.mp3", 120, own = true, bodyLine = 2, starts = listOf(0.0, 0.25, 0.5, 0.75), episode = true, atTheEnd = true)
        val skipped = mutableListOf<LineId>()
        choices.setVoiceInstead(7, 4, instead = true)
        val speaker = ReadingSpeaker(voice, audio, { e, p -> episode.takeIf { e == 7L && p == 4 } }, choices, logged::add).also {
            it.skip = { line -> skipped += line }
            it.restart = { line -> restarted += line }
        }
        speaker.speak(id(2, page = 4), "0:00", null, 1f, flush = true)
        voice.sayNext()
        assertEquals("a choice kept for the page doesn't silence it", "Now the episode itself, 2 minutes.", voice.said.last().text)
        assertEquals(Triple(episode.url, 0L, 1f), audio.played.last())

        // Paused at 1:10, then played: on from the mark it reached, a minute in.
        audio.listener!!.onPosition(70_000, 120_000)
        speaker.stop()
        speaker.speak(id(4, page = 4), "1:00", null, 1f, flush = true)
        assertEquals(Triple(episode.url, 60_000L, 1f), audio.played.last())

        audio.listener!!.onError("offline")
        assertEquals("The episode won't play right now.", voice.said.last().text)
        assertTrue("not until it's said", skipped.isEmpty())
        voice.sayNext()
        assertEquals(listOf(LineId(1, 7, 4, 4, 6)), skipped)
        assertTrue("the voice doesn't read its marks instead", restarted.isEmpty())
        assertTrue(logged.toString(), logged.any { "failed (offline); an episode after the edition" in it })
    }

    @Test
    fun startingAtTheBodysFirstLineAnnouncesTheRecordingFirst() {
        listen()
        speaker.speak(id(2), "Dear friends,", null, 1f, flush = true)
        assertEquals("Now its recorded reading, 2 minutes.", voice.said.single().text)
        assertTrue(audio.played.isEmpty())
        voice.sayNext()
        assertEquals(listOf(Triple(reading.url, 0L, 1f)), audio.played)
    }

    @Test
    fun aTranscriptsEpisodeIsAnnouncedAsTheEpisodeInHoursAndMinutes() {
        fun announced(seconds: Int?): String {
            val voice = FakeSpeaker()
            val episode = reading.copy(url = "https://cdn.example/episode.mp3", seconds = seconds, own = true, episode = true)
            ReadingSpeaker(voice, audio, { e, p -> episode.takeIf { e == 7L && p == 0 } }, choices).speak(id(2), "Hello.", null, 1f, flush = true)
            return voice.said.single().text
        }
        assertEquals("Now the episode itself, 1 hour 27 minutes.", announced(5_220))
        assertEquals("Now the episode itself, 2 hours.", announced(7_190))
        assertEquals("Now the episode itself, 59 minutes.", announced(3_540))
        assertEquals("Now the episode itself.", announced(null))
    }

    @Test
    fun aTapPartWayPlaysTheRecordingFromThereWithoutAnAnnouncement() {
        listen()
        speaker.speak(id(4), "It took a week.", null, 1f, flush = true)
        assertEquals(listOf(Triple(reading.url, 50_000L, 1f)), audio.played)
        assertTrue(voice.said.isEmpty())
        audio.listener!!.onPosition(50_000, 0)
        assertEquals(listOf("start ${id(4)}"), heard)
    }

    @Test
    fun anArticleTurnedBackToTheVoiceIsReadByItAndTheSwitchIsLogged() {
        listen()
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)
        speaker.setVoiceInstead(7, 0, instead = true, line = 3)
        // The player plays on from the line, as a tap does.
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)

        assertEquals("Line three.", voice.said.single().text)
        assertEquals(1, audio.played.size)
        assertTrue(audio.stopped > 0)
        assertEquals(true, speaker.now.value?.voiceInstead)
        assertTrue(logged.last(), "switched to the voice at line 3" in logged.last() && reading.url in logged.last())

        speaker.setVoiceInstead(7, 0, instead = false, line = 3)
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)
        assertEquals(2, audio.played.size)
        assertTrue(logged.toString(), logged.any { "back to its recording at line 3" in it })
    }

    @Test
    fun aRecordingThatWontPlayHandsBackToTheVoiceFromWhereItGot() {
        listen()
        speaker.speak(id(2), "Dear friends,", null, 1f, flush = true)
        voice.sayNext()
        audio.listener!!.onPosition(30_000, 100_000)
        audio.listener!!.onError("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")

        assertEquals(listOf(LineId(1, 7, 0, 3, 6)), restarted)
        assertEquals("the switch offers it again", true, speaker.now.value?.voiceInstead)
        assertTrue(logged.last(), "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" in logged.last())
        // Played on from there: the voice, not the same failure again.
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)
        assertEquals("Line three.", voice.said.last().text)
        assertEquals(1, audio.played.size)

        // Asked for again: the connection may be back.
        speaker.setVoiceInstead(7, 0, instead = false, line = 3)
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)
        assertEquals(2, audio.played.size)
    }

    @Test
    fun aRecordingOfUnknownLengthSeeksOnceThePlayerKnowsIt() {
        val untimed = reading.copy(seconds = null)
        val speaker = ReadingSpeaker(voice, audio, { _, _ -> untimed }, choices)
        speaker.speak(id(4), "It took a week.", null, 1f, flush = true)
        assertEquals(listOf(Triple(reading.url, 0L, 1f)), audio.played)
        audio.listener!!.onPosition(200, 80_000)
        assertEquals(Triple(reading.url, 40_000L, 1f), audio.played.last())

        // Known from then on: a later tap goes straight there.
        speaker.speak(id(5), "The end.", null, 1f, flush = true)
        assertEquals(Triple(reading.url, 60_000L, 1f), audio.played.last())
    }

    @Test
    fun theRecordingIsAnnouncedOnceAnArticle() {
        listen()
        speaker.speak(id(2), "Dear friends,", null, 1f, flush = true)
        voice.sayNext()
        // Paused and played again at the same line.
        speaker.speak(id(2), "Dear friends,", null, 1f, flush = true)
        assertEquals(1, voice.said.size)
        assertEquals(2, audio.played.size)
    }

    @Test
    fun anArticleWithoutARecordingIsTheVoicesAsBefore() {
        listen()
        speaker.speak(id(2, page = 1), "Another article.", null, 1f, flush = true)
        speaker.speak("sample", "A sample of the voice.", null, 1f, flush = true)
        assertEquals(listOf("Another article.", "A sample of the voice."), voice.said.map { it.text })
        assertTrue(audio.played.isEmpty())
        assertNull(speaker.now.value)
    }

    @Test
    fun withKokorosPodcastTheHeadingIsSpokenAndTheRecordingStillTakesOver() {
        val folder = kotlin.io.path.createTempDirectory().toFile()
        val store = PodcastStore(folder)
        store.want(7, com.app.newspaperss.settings.PodcastVoice.EMMA)
        val scratch = store.scratch(7, 0, 0).apply { writeText("audio") }
        store.keep(7, 0, 0, scratch, listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0))
        store.complete(7, 0)
        val kokoro = object : PodcastAudio {
            override var listener: PodcastAudio.Listener? = null
            val played = mutableListOf<String>()
            override fun play(files: List<java.io.File>, index: Int, fromMs: Long, speed: Float) { played += files[index].name }
            override fun stop() {}
            override fun release() {}
        }
        val phone = FakeSpeaker()
        val speaker = ReadingSpeaker(PodcastSpeaker(phone, store, kokoro) { true }, audio, { e, p -> reading.takeIf { e == 7L && p == 0 } }, choices)
        speaker.listener = object : Speaker.Listener {
            override fun onStart(id: String) { heard += "start $id" }
            override fun onDone(id: String) { heard += "done $id" }
            override fun onError(id: String) { heard += "error $id" }
        }

        speaker.speak(id(0), "The Example Letter.", null, 1f, flush = true)
        speaker.speak(id(1), "October 15.", null, 1f, flush = false)
        phone.sayNext()
        speaker.speak(id(2), "Dear friends,", null, 1f, flush = false)
        phone.sayNext()
        phone.sayNext()

        assertTrue("the podcast doesn't play the article over the recording", kokoro.played.isEmpty())
        assertEquals(listOf(Triple(reading.url, 0L, 1f)), audio.played)
        assertEquals(listOf("start ${id(0)}", "done ${id(0)}", "start ${id(1)}", "done ${id(1)}"), heard)
    }

    @Test
    fun aHeadingLineTheVoiceCantSayDoesntHoldUpTheRecording() {
        listen()
        speaker.speak(id(0), "The Example Letter.", null, 1f, flush = true)
        speaker.speak(id(1), "October 15.", null, 1f, flush = false)
        voice.sayNext()
        speaker.speak(id(2), "Dear friends,", null, 1f, flush = false)
        voice.fail()

        assertEquals("Now its recorded reading, 2 minutes.", voice.said.last().text)
        voice.sayNext()
        assertEquals(1, audio.played.size)
    }

    @Test
    fun aRecordingHeardToItsEndCanBePlayedFromPartWayAgain() {
        listen()
        speaker.speak(id(3), "Line three.", null, 1f, flush = true)
        audio.listener!!.onPosition(30_000, 120_000)
        audio.listener!!.onEnded()
        speaker.speak(id(4), "It took a week.", null, 1f, flush = true)
        // Half way through, by the length the player found.
        assertEquals(Triple(reading.url, 60_000L, 1f), audio.played.last())
    }
}
