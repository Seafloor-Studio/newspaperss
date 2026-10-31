package com.app.newspaperss.listen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.settings.Recordings
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Which articles of an edition play a recording in their place. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingsTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val logged = mutableListOf<String>()
    private val readings = Readings(db, { Recordings() }, logged::add)

    /** A page with a title and a byline, then [words] words of body in sentences of ten. */
    private fun page(words: Int) = ListenScript(
        listOf(
            Block.Text(Kind.TITLE, listOf("A title")),
            Block.Text(Kind.BYLINE, listOf("By Someone")),
            Block.Text(Kind.PARAGRAPH, List(words / 10) { (1..10).joinToString(" ") { "word" } + "." }),
        ),
    )

    @Test
    fun anArticleWithAReadingOrAnOwnAudioAsLongAsItsTextPlaysIt() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        suspend fun post(guid: String, kind: EpisodeKind, audio: String? = null, seconds: Int? = null, pair: String? = null) = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, episodeKind = kind, audioUrl = audio, audioSeconds = seconds, episodePair = pair),
        )
        // 1,600 words: ten minutes at Listen's 160 a minute.
        val ids = listOf(
            post("letter", EpisodeKind.ARTICLE, pair = "reading"),
            post("narrated", EpisodeKind.ARTICLE, audio = "https://cdn.example/narrated.mp3", seconds = 640),
            post("about-an-episode", EpisodeKind.ARTICLE, audio = "https://cdn.example/episode.mp3", seconds = 3_600),
            post("untimed", EpisodeKind.ARTICLE, audio = "https://cdn.example/untimed.mp3"),
            post("notes", EpisodeKind.NOTES, audio = "https://cdn.example/notes.mp3", seconds = 640),
            post("pair-gone", EpisodeKind.ARTICLE, audio = "https://cdn.example/own.mp3", seconds = 640, pair = "deleted"),
        )
        post("reading", EpisodeKind.AUDIO_VERSION, audio = "https://cdn.example/reading.mp3", seconds = 610, pair = "letter")
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "$i", sourceTitle = "A", minutes = 7.0), state = null) }

        readings.load(3, contents) { page(1_600) }

        assertEquals(Reading("https://cdn.example/reading.mp3", 610, own = false, bodyLine = 2, starts = readings.at(3, 0)!!.starts), readings.at(3, 0))
        assertEquals(Reading("https://cdn.example/narrated.mp3", 640, own = true, bodyLine = 2, starts = readings.at(3, 1)!!.starts), readings.at(3, 1))
        assertNull("an hour's episode isn't a reading of ten minutes' text", readings.at(3, 2))
        assertNull("without its length it can't be told from an episode", readings.at(3, 3))
        assertNull("notes play their episode at the end, not in place", readings.at(3, 4))
        assertNull("an article whose pair is gone has no recording, not its own audio instead", readings.at(3, 5))
        // A body of equal sentences: each a 160th of the way further.
        val starts = readings.at(3, 0)!!.starts
        assertEquals(160, starts.size)
        assertEquals(0.5, readings.at(3, 0)!!.shareAt(2 + 80), 0.001)

        // Another edition opened: only one is kept.
        readings.load(4, emptyList()) { null }
        assertNull(readings.at(3, 0))
    }

    @Test
    fun aTranscriptPlaysItsEpisodeWhenItsLengthFitsAndOnlyOnce() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        suspend fun post(guid: String, kind: EpisodeKind, audio: String? = null, seconds: Int? = null, pair: String? = null) = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, episodeKind = kind, audioUrl = audio, audioSeconds = seconds, episodePair = pair),
        )
        val ids = listOf(
            post("narrated", EpisodeKind.ARTICLE, audio = "https://cdn.example/narrated.mp3", seconds = 640),
            post("transcript", EpisodeKind.TRANSCRIPT, audio = "https://cdn.example/episode.mp3", seconds = 5_220),
            post("paired-transcript", EpisodeKind.TRANSCRIPT, pair = "notes"),
            post("silent-transcript", EpisodeKind.TRANSCRIPT),
            post("preview-transcript", EpisodeKind.TRANSCRIPT, audio = "https://cdn.example/preview.mp3", seconds = 300),
            post("untimed-transcript", EpisodeKind.TRANSCRIPT, audio = "https://cdn.example/untimed.mp3"),
            post("again-transcript", EpisodeKind.TRANSCRIPT, pair = "notes"),
            post("trailer-transcript", EpisodeKind.TRANSCRIPT, audio = "https://cdn.example/trailer.mp3", seconds = 120, pair = "full-notes"),
        )
        post("notes", EpisodeKind.NOTES, audio = "https://cdn.example/notes.mp3", seconds = 6_000, pair = "paired-transcript")
        post("full-notes", EpisodeKind.NOTES, audio = "https://cdn.example/full.mp3", seconds = 5_400, pair = "trailer-transcript")
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "$i", sourceTitle = "A", minutes = 7.0), state = null) }

        // The article 1,600 words, ten minutes read aloud; each transcript 15,840, 99 minutes.
        readings.load(3, contents) { page(if (it == 0) 1_600 else 15_840) }

        assertEquals(Reading("https://cdn.example/episode.mp3", 5_220, own = true, bodyLine = 2, starts = readings.at(3, 1)!!.starts, episode = true), readings.at(3, 1))
        assertEquals(Reading("https://cdn.example/notes.mp3", 6_000, own = false, bodyLine = 2, starts = readings.at(3, 2)!!.starts, episode = true), readings.at(3, 2))
        assertEquals(1_584, readings.at(3, 1)!!.starts.size)
        assertNull("no audio", readings.at(3, 3))
        assertNull("five minutes is a preview of 99 minutes' talk", readings.at(3, 4))
        assertNull("without a length a preview can't be ruled out", readings.at(3, 5))
        assertNull("the notes post's episode already plays on page 2", readings.at(3, 6))
        assertEquals("its own audio is a trailer: its notes post's episode instead", "https://cdn.example/full.mp3", readings.at(3, 7)?.url)
        assertEquals(
            "Readings: edition 3, recorded readings: page 0 (its own audio, 11 min), page 1 (its episode, its own audio, 87 min), " +
                "page 2 (its episode, its notes post's, 100 min), page 7 (its episode, its notes post's, 90 min); read by the voice instead: " +
                "page 3 (transcript: no audio), page 4 (transcript: its own audio, 5 min against 99 min read aloud), " +
                "page 5 (transcript: its own audio, length unknown), page 6 (its recording plays on page 2); episodes at the end: none",
            logged.single(),
        )

        readings.load(4, emptyList()) { null }
        assertEquals("Readings: edition 4, recorded readings: none; episodes at the end: none", logged.last())
    }

    @Test
    fun aPageThatWontReadLeavesTheOthersTheirRecordings() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        val ids = listOf("transcript", "narrated", "unreadable").map { guid ->
            db.articles().insertIgnoring(
                ArticleEntity(
                    sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid,
                    episodeKind = if (guid == "transcript") EpisodeKind.TRANSCRIPT else EpisodeKind.ARTICLE,
                    audioUrl = "https://cdn.example/$guid.mp3", audioSeconds = 640,
                ),
            )
        }
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "$i", sourceTitle = "A", minutes = 7.0), state = null) }

        readings.load(3, contents) { if (it != 1) error("unreadable") else page(1_600) }

        assertNull(readings.at(3, 0))
        assertEquals("https://cdn.example/narrated.mp3", readings.at(3, 1)?.url)
        assertNull(readings.at(3, 2))
        assertEquals(
            "Readings: edition 3, recorded readings: page 1 (its own audio, 11 min); read by the voice instead: page 0 (its page wouldn't read), page 2 (its page wouldn't read); episodes at the end: none",
            logged.single(),
        )
    }

    /** A source, or one tt-rss feed, whose recordings are off is read by the voice; the rest play as before. */
    @Test
    fun aSourceWithItsRecordingsOffIsReadByTheVoice() = runTest {
        val off = db.sources().insert(SourceEntity(url = "https://off.example/feed", title = "Off"))
        val on = db.sources().insert(SourceEntity(url = "https://on.example/feed", title = "On"))
        val account = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/", title = "tt-rss"))
        db.sources().savePublication(PublicationEntity(off, PublicationEntity.OWN, recordingsOff = true))
        db.sources().savePublication(PublicationEntity(account, "7", recordingsOff = true))
        suspend fun post(source: Long, guid: String, kind: EpisodeKind = EpisodeKind.ARTICLE, seconds: Int? = 640, origin: String? = null) = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, episodeKind = kind, audioUrl = seconds?.let { "https://cdn.example/$guid.mp3" }, audioSeconds = seconds, originId = origin),
        )
        val ids = listOf(
            post(off, "off-narrated"),
            post(off, "off-transcript", EpisodeKind.TRANSCRIPT, seconds = 5_220),
            post(on, "on-narrated"),
            post(account, "feed-off", origin = "7"),
            post(account, "feed-on", origin = "8"),
            // Nothing to play, so nothing switched off: the log counts only recordings the switch kept out.
            post(off, "off-plain", seconds = null),
        )
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "$i", sourceTitle = "A", minutes = 7.0), state = null) }

        readings.load(3, contents) { page(if (it == 1) 15_840 else 1_600) }

        assertNull(readings.at(3, 0))
        assertNull("its episode too", readings.at(3, 1))
        assertEquals("https://cdn.example/on-narrated.mp3", readings.at(3, 2)?.url)
        assertNull("one tt-rss feed switched off", readings.at(3, 3))
        assertEquals("the account's other feeds still play", "https://cdn.example/feed-on.mp3", readings.at(3, 4)?.url)
        assertEquals(
            "Readings: edition 3, recorded readings: page 2 (its own audio, 11 min), page 4 (its own audio, 11 min); read by the voice instead: " +
                "page 0 (its source's recordings are switched off), page 1 (its source's recordings are switched off), page 3 (its source's recordings are switched off); episodes at the end: none",
            logged.single(),
        )
    }

    /**
     * Notes posts' episodes play after the closing page, in book order: each once, none a
     * transcript already plays in its place, none without audio, none when switched off.
     */
    @Test
    fun notesPostsEpisodesPlayAfterTheClosingPageEachOnce() = runTest {
        var choices = Recordings()
        val readings = Readings(db, { choices }, logged::add)
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        suspend fun post(guid: String, kind: EpisodeKind, audio: String? = null, seconds: Int? = null, pair: String? = null) = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, episodeKind = kind, audioUrl = audio, audioSeconds = seconds, episodePair = pair),
        )
        val ids = listOf(
            post("first-notes", EpisodeKind.NOTES, audio = "https://cdn.example/1.mp3", seconds = 2_280),
            post("transcript", EpisodeKind.TRANSCRIPT, pair = "transcribed-notes"),
            post("transcribed-notes", EpisodeKind.NOTES, audio = "https://cdn.example/2.mp3", seconds = 5_940, pair = "transcript"),
            post("silent-notes", EpisodeKind.NOTES),
            post("untimed-notes", EpisodeKind.NOTES, audio = "https://cdn.example/3.mp3"),
            post("again-notes", EpisodeKind.NOTES, audio = "https://cdn.example/1.mp3", seconds = 2_280),
        )
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "Title $i", sourceTitle = "A", minutes = 2.0), state = null) }
        // The transcript 15,840 words: 99 minutes, its episode's length.
        val load: suspend () -> Unit = { readings.load(3, contents) { page(if (it == 1) 15_840 else 100) } }

        load()
        assertEquals("https://cdn.example/2.mp3", readings.at(3, 1)?.url)
        val ending = readings.ending(3)
        assertEquals(listOf(0, 4), ending.map { it.notesPage })
        assertEquals(listOf("https://cdn.example/1.mp3", "https://cdn.example/3.mp3"), ending.map { it.reading.url })
        // Their pages follow the closing page, which is page 6.
        assertNull(readings.at(3, 6))
        assertEquals(ending[0].reading, readings.at(3, 7))
        assertEquals(ending[1].reading, readings.at(3, 8))
        assertNull(readings.at(3, 9))
        // Its page: the source and the title, then a mark every 30 seconds, which the episode plays over.
        val lines = ending[0].script.lines.map { it.spoken }
        assertEquals(listOf("A", "Title 0", "0:00", "0:30", "1:00"), lines.take(5))
        assertEquals("37:30", lines.last())
        assertEquals(2 + 76, lines.size)
        assertEquals(2, ending[0].reading.bodyLine)
        assertEquals(0.5, ending[0].reading.shareAt(2 + 38), 0.001)
        assertTrue(ending[0].reading.episode && ending[0].reading.atTheEnd)
        // Without a length, one line.
        assertEquals(listOf("A", "Title 4", "The episode."), ending[1].script.lines.map { it.spoken })
        assertEquals(2, ending[1].reading.bodyLine)
        // Past the hour, marks say the hours.
        assertEquals("1:00:30", EndEpisode.script("T", null, 3_660).lines.last().spoken)
        assertEquals(
            "Readings: edition 3, recorded readings: page 1 (its episode, its notes post's, 99 min); episodes at the end: page 0's, 38 min, page 4's, length unknown",
            logged.last(),
        )

        choices = Recordings(atTheEnd = false)
        load()
        assertEquals(emptyList<EndEpisode>(), readings.ending(3))
        assertNull(readings.at(3, 7))
        assertTrue(logged.last(), logged.last().endsWith("; episodes at the end: switched off"))
    }

    @Test
    fun theKindsSwitchedOffInSettingsAreReadByTheVoiceFromTheNextEditionOpened() = runTest {
        var choices = Recordings(readings = false)
        val readings = Readings(db, { choices }, logged::add)
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        val ids = listOf("narrated" to EpisodeKind.ARTICLE, "transcript" to EpisodeKind.TRANSCRIPT).map { (guid, kind) ->
            db.articles().insertIgnoring(
                ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, episodeKind = kind, audioUrl = "https://cdn.example/$guid.mp3", audioSeconds = 640),
            )
        }
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "$i", sourceTitle = "A", minutes = 7.0), state = null) }
        val load: suspend () -> Unit = { readings.load(3, contents) { page(1_600) } }

        load()
        assertNull("readings are off", readings.at(3, 0))
        assertEquals("https://cdn.example/transcript.mp3", readings.at(3, 1)?.url)
        assertEquals("Readings: edition 3, recorded readings: page 1 (its episode, its own audio, 11 min) (readings are switched off); episodes at the end: none", logged.last())

        // A change waits for the edition to be opened again.
        choices = Recordings(episodes = false)
        assertEquals("https://cdn.example/transcript.mp3", readings.at(3, 1)?.url)
        load()
        assertEquals("https://cdn.example/narrated.mp3", readings.at(3, 0)?.url)
        assertNull("episodes are off", readings.at(3, 1))
        assertEquals("Readings: edition 3, recorded readings: page 0 (its own audio, 11 min); read by the voice instead: page 1 (transcript: episodes are switched off); episodes at the end: none", logged.last())

        // Off, the kinds chosen under it don't count.
        choices = Recordings(on = false)
        load()
        assertNull(readings.at(3, 0))
        assertNull(readings.at(3, 1))
    }
}
