package com.app.newspaperss.listen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class AudioLengthsTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val lengths = AudioLengths(db, http)
    // Three seconds behind a 40 KB tag, as a podcast host's cover art makes one.
    private val mp3 = javaClass.getResourceAsStream("/audio/id3-40k-cbr-3s.mp3")!!.readBytes()

    private suspend fun post(source: Long, guid: String, kind: EpisodeKind?, audio: String? = null, pair: String? = null, seconds: Int? = null) =
        db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = guid, url = "https://a.example/$guid", title = guid, audioUrl = audio, episodeKind = kind, episodePair = pair, audioSeconds = seconds),
        )

    @Test
    fun anEditionsArticlesAndTheReadingsTheyPairWithAreTimed() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        listOf("own", "reading", "notes", "known").forEach { http.files["https://cdn.example/$it.mp3"] = "audio/mpeg" to mp3 }
        val own = post(source, "own", EpisodeKind.ARTICLE, audio = "https://cdn.example/own.mp3")
        val letter = post(source, "letter", EpisodeKind.ARTICLE, pair = "reading")
        val reading = post(source, "reading", EpisodeKind.AUDIO_VERSION, audio = "https://cdn.example/reading.mp3", pair = "letter")
        val notes = post(source, "notes", EpisodeKind.NOTES, audio = "https://cdn.example/notes.mp3")
        val known = post(source, "known", EpisodeKind.ARTICLE, audio = "https://cdn.example/known.mp3", seconds = 600)

        // The reading isn't in the edition itself: it's found through the letter it reads.
        lengths.measure(listOf(own, letter, notes, known))

        assertEquals(3, db.articles().byId(own)!!.audioSeconds)
        assertEquals(3, db.articles().byId(reading)!!.audioSeconds)
        assertNull("an episode's notes don't play in place", db.articles().byId(notes)!!.audioSeconds)
        assertEquals("already known, not fetched", 600, db.articles().byId(known)!!.audioSeconds)
        assertEquals(setOf("https://cdn.example/own.mp3", "https://cdn.example/reading.mp3"), http.ranges.map { it.first }.toSet())
        // The first bytes, then from where the tag ends.
        assertEquals(listOf(0L, 40_010L), http.ranges.filter { it.first.endsWith("own.mp3") }.map { it.second })
    }

    @Test
    fun aTranscriptsEpisodeIsTimedItsOwnOrItsNotesPosts() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        listOf("transcript", "notes", "lone-notes").forEach { http.files["https://cdn.example/$it.mp3"] = "audio/mpeg" to mp3 }
        val transcript = post(source, "transcript", EpisodeKind.TRANSCRIPT, audio = "https://cdn.example/transcript.mp3")
        val paired = post(source, "paired", EpisodeKind.TRANSCRIPT, pair = "notes")
        val notes = post(source, "notes", EpisodeKind.NOTES, audio = "https://cdn.example/notes.mp3", pair = "paired")
        val lone = post(source, "lone-notes", EpisodeKind.NOTES, audio = "https://cdn.example/lone-notes.mp3")

        lengths.measure(listOf(transcript, paired, lone))

        assertEquals(3, db.articles().byId(transcript)!!.audioSeconds)
        assertEquals("found through the transcript it's paired with", 3, db.articles().byId(notes)!!.audioSeconds)
        assertNull("notes on their own play at the end, untimed", db.articles().byId(lone)!!.audioSeconds)
    }

    // runBlocking, not runTest: the timeout must be real time, or it passes while Room works on its own thread.
    @Test
    fun aMadeEditionsArticlesAreTimed() = kotlinx.coroutines.runBlocking {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        http.files["https://cdn.example/own.mp3"] = "audio/mpeg" to mp3
        val own = post(source, "own", EpisodeKind.ARTICLE, audio = "https://cdn.example/own.mp3")
        val edition = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition"))
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = own, position = 0, title = "own", sourceTitle = "A", minutes = 1.0)))

        lengths.measureEdition(edition)

        assertEquals(3, db.articles().byId(own)!!.audioSeconds)
    }

    @Test
    fun onlyMp3sAreTimed() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        http.files["https://cdn.example/a.m4a"] = "audio/mp4" to mp3
        db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = "aac", url = "https://a.example/aac", title = "aac", audioUrl = "https://cdn.example/a.m4a", audioType = "audio/x-m4a", episodeKind = EpisodeKind.ARTICLE),
        ).let { lengths.measure(listOf(it)) }
        assertEquals(emptyList<Any>(), http.ranges)
    }

    @Test
    fun aFileThatCantBeReadStaysUnknown() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://a.example/feed", title = "A"))
        http.files["https://cdn.example/ignores.mp3"] = "audio/mpeg" to mp3
        http.ignoresRanges += "https://cdn.example/ignores.mp3"
        http.files["https://cdn.example/page.mp3"] = "text/html" to "<html>Not found</html>".toByteArray()
        http.files["https://cdn.example/huge.mp3"] = "audio/mpeg" to mp3
        http.claimedSizes["https://cdn.example/huge.mp3"] = 999_999_999_999
        val ids = listOf(
            post(source, "huge", EpisodeKind.ARTICLE, audio = "https://cdn.example/huge.mp3"),
            post(source, "ignores", EpisodeKind.ARTICLE, audio = "https://cdn.example/ignores.mp3"),
            post(source, "page", EpisodeKind.ARTICLE, audio = "https://cdn.example/page.mp3"),
            post(source, "gone", EpisodeKind.ARTICLE, audio = "https://cdn.example/gone.mp3"),
        )
        http.unreachable += "https://cdn.example/gone.mp3"

        lengths.measure(ids)

        ids.forEach { assertNull(db.articles().byId(it)!!.audioSeconds) }
    }
}
