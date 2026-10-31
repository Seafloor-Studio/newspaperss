package com.app.newspaperss.ui.components

import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EpisodePair
import com.app.newspaperss.settings.Recordings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The line at the top of an episode post's page: what it is, and what Listen does with it. */
class EpisodeNoteTest {
    /** A post of [minutes]' reading, as the edition has it. */
    private fun content(
        kind: EpisodeKind?,
        minutes: Double = 3.0,
        audio: Int? = null,
        hasAudio: Boolean = audio != null,
        pair: EpisodePair? = null,
        pairAudio: Int? = null,
        pairHasAudio: Boolean = pairAudio != null,
    ) = EditionContent(
        EditionArticleEntity(editionId = 1, articleId = 1, position = 0, title = "T", sourceTitle = "S", minutes = minutes),
        state = null, episodeKind = kind, pair = pair, hasAudio = hasAudio, pairHasAudio = pairHasAudio, audioSeconds = audio, pairAudioSeconds = pairAudio,
    )

    private fun note(c: EditionContent, recordings: Recordings = Recordings()) = episodeJudgement(c, recordings)?.let(::episodeNote)

    @Test
    fun episodeNotesSayTheirEpisodePlaysAfterTheEditionOnlyWhenItWill() {
        val plays = "Episode notes. Listen reads them, then plays the episode after the edition."
        val reads = "Episode notes. Listen reads them."
        assertEquals(plays, note(content(EpisodeKind.NOTES, audio = 3_600)))
        assertEquals("a length isn't needed", plays, note(content(EpisodeKind.NOTES, hasAudio = true)))
        assertEquals("an embedded video, no audio of its own", reads, note(content(EpisodeKind.NOTES)))
        assertEquals(reads, note(content(EpisodeKind.NOTES, audio = 3_600), Recordings(atTheEnd = false)))
        assertEquals(reads, note(content(EpisodeKind.NOTES, audio = 3_600), Recordings(on = false)))
    }

    @Test
    fun readingsSayWhereTheyPlay() {
        val letter = EpisodePair("l", "A letter", EpisodeKind.ARTICLE)
        assertEquals(
            "Audio version of “A letter”. Listen plays it with that post, when that post is in the edition.",
            note(content(EpisodeKind.AUDIO_VERSION, audio = 600, pair = letter)),
        )
        assertEquals("Audio version of “A letter”. Listen reads it.", note(content(EpisodeKind.AUDIO_VERSION, audio = 600, pair = letter), Recordings(readings = false)))
        val reading = EpisodePair("a", "A letter (audio)", EpisodeKind.AUDIO_VERSION)
        assertEquals("Has an audio version, “A letter (audio)”. Listen plays it in this post's place.", note(content(EpisodeKind.ARTICLE, pair = reading, pairAudio = 600)))
        assertEquals("Has an audio version, “A letter (audio)”.", note(content(EpisodeKind.ARTICLE, pair = reading)))
        assertNull("an ordinary article says nothing", note(content(EpisodeKind.ARTICLE)))
        assertNull("nor one never judged", note(content(null)))
    }

    /**
     * A transcript of about 99 minutes read aloud (66.5 minutes' reading). Its audio plays unless
     * every recording it has is far too short for it: then it's a preview, and the page says so.
     */
    @Test
    fun aTranscriptWhoseOnlyAudioIsAPreviewSaysSo() {
        val transcript = 66.5
        val plays = "Transcript of an episode. Listen plays the episode in its place when it can."
        assertEquals("Transcript of an episode. Its audio is a 5-min preview, so Listen reads it.", note(content(EpisodeKind.TRANSCRIPT, transcript, audio = 300)))
        val notes = EpisodePair("n", "Episode 12", EpisodeKind.NOTES)
        assertEquals(
            "its notes post's episode is the whole thing",
            "Transcript of “Episode 12”. Listen plays the episode in its place when it can.",
            note(content(EpisodeKind.TRANSCRIPT, transcript, audio = 300, pair = notes, pairAudio = 5_400)),
        )
        assertEquals(
            "both previews: the longer one",
            "Transcript of “Episode 12”. Its audio is a 10-min preview, so Listen reads it.",
            note(content(EpisodeKind.TRANSCRIPT, transcript, audio = 300, pair = notes, pairAudio = 600)),
        )
        // 40 minutes against 99 is under half, which Listen turns away, but over a third: the page
        // doesn't claim a preview it isn't sure of.
        assertEquals(plays, note(content(EpisodeKind.TRANSCRIPT, transcript, audio = 2_400)))
        assertEquals("no length, no claim", plays, note(content(EpisodeKind.TRANSCRIPT, transcript, hasAudio = true)))
        assertEquals("Transcript of an episode. Listen reads it.", note(content(EpisodeKind.TRANSCRIPT, transcript, audio = 300), Recordings(episodes = false)))
        // A long title is shortened, so what Listen does stays in the lines the page shows.
        val long = EpisodePair("n", "Episode 212: The ferry planner who mapped every crossing on the Bosphorus", EpisodeKind.NOTES)
        assertEquals(
            "Transcript of “Episode 212: The ferry planner who mapped…”. Listen plays the episode in its place when it can.",
            note(content(EpisodeKind.TRANSCRIPT, transcript, pair = long, pairHasAudio = true)),
        )
    }

    /** A video-only episode's transcript: nothing to play, and the page says so rather than "when it can". */
    @Test
    fun aTranscriptWithNoAudioAnywhereSaysSo() {
        val none = "Transcript of an episode. No episode audio found, so Listen reads it."
        assertEquals(none, note(content(EpisodeKind.TRANSCRIPT)))
        val notes = EpisodePair("n", "Episode 12", EpisodeKind.NOTES)
        assertEquals("Transcript of “Episode 12”. No episode audio found, so Listen reads it.", note(content(EpisodeKind.TRANSCRIPT, pair = notes)))
        assertEquals(
            "its notes post's audio is the episode",
            "Transcript of “Episode 12”. Listen plays the episode in its place when it can.",
            note(content(EpisodeKind.TRANSCRIPT, pair = notes, pairHasAudio = true)),
        )
        // Listen takes the episode only from a notes post: another pair's audio isn't it.
        val reading = EpisodePair("a", "A reading", EpisodeKind.AUDIO_VERSION)
        assertEquals("Transcript of “A reading”. No episode audio found, so Listen reads it.", note(content(EpisodeKind.TRANSCRIPT, pair = reading, pairHasAudio = true)))
    }
}
