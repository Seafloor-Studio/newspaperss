package com.app.newspaperss.listen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val readings = Readings(db, logged::add)

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
    fun whatPlaysIsLoggedWithTheTranscriptsThatCouldPlayTheirEpisode() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        suspend fun post(guid: String, kind: EpisodeKind, audio: String? = null, seconds: Int? = null, pair: String? = null) = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, episodeKind = kind, audioUrl = audio, audioSeconds = seconds, episodePair = pair),
        )
        val ids = listOf(
            post("narrated", EpisodeKind.ARTICLE, audio = "https://cdn.example/narrated.mp3", seconds = 640),
            post("transcript", EpisodeKind.TRANSCRIPT, audio = "https://cdn.example/episode.mp3", seconds = 5_220),
            post("paired-transcript", EpisodeKind.TRANSCRIPT, pair = "notes"),
            post("silent-transcript", EpisodeKind.TRANSCRIPT),
        )
        post("notes", EpisodeKind.NOTES, audio = "https://cdn.example/notes.mp3", pair = "paired-transcript")
        val contents = ids.mapIndexed { i, id -> EditionContent(EditionArticleEntity(editionId = 3, articleId = id, position = i, title = "$i", sourceTitle = "A", minutes = 7.0), state = null) }

        // 1,600 words: ten minutes read aloud.
        readings.load(3, contents) { page(1_600) }

        assertNull("a transcript doesn't play its episode yet", readings.at(3, 1))
        assertEquals(
            "Readings: edition 3, recorded readings: page 0 (its own audio, 11 min); transcripts that could play their episode: " +
                "page 1 (its own audio, 87 min against 10 min read aloud), page 2 (its notes post's, length unknown against 10 min read aloud)",
            logged.single(),
        )

        readings.load(4, emptyList()) { null }
        assertEquals("Readings: edition 4, recorded readings: none", logged.last())
    }
}
