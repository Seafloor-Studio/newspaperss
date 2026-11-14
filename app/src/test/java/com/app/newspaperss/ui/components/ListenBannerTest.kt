package com.app.newspaperss.ui.components

import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.data.EpisodePair
import com.app.newspaperss.listen.Outcome
import com.app.newspaperss.listen.Outcome.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The banner above a post: what it is, and what Listen decided to do with it. */
class ListenBannerTest {
    private val titles = mapOf(2 to "Rail Talk: night trains", 5 to "Episode 48 notes")

    /** A title as a banner quotes it, kept to its own direction. */
    private fun q(title: String) = "\u2068$title\u2069"

    private fun banner(kind: EpisodeKind?, outcome: Outcome?, pair: EpisodePair? = null) = listenBanner(kind, pair, outcome, titles::get)

    private fun text(kind: EpisodeKind?, outcome: Outcome?, pair: EpisodePair? = null) = banner(kind, outcome, pair)?.text

    @Test
    fun episodeNotesSayWhereTheirEpisodePlaysOrWhyItDoesnt() {
        assertEquals("Episode notes. The episode itself plays after the edition.", text(EpisodeKind.NOTES, Outcome.AfterTheEdition))
        assertEquals("Episode notes. No episode audio found.", text(EpisodeKind.NOTES, Outcome.Read(Why.NO_AUDIO)))
        assertEquals(
            "Episode notes. Its transcript, “${q("Rail Talk: night trains")}”, plays the episode in its place.",
            text(EpisodeKind.NOTES, Outcome.PlaysWith(2)),
        )
        assertEquals(
            "Episode notes. Its episode already plays after the edition, with “${q("Episode 48 notes")}”.",
            text(EpisodeKind.NOTES, Outcome.PlaysWith(5, atTheEnd = true)),
        )
    }

    /** Switched off, a banner says where to switch it on, and that part goes there. */
    @Test
    fun aRecordingSwitchedOffSaysWhereToSwitchItOn() {
        val notes = banner(EpisodeKind.NOTES, Outcome.Read(Why.KIND_OFF))!!
        assertEquals("Episode notes. To play the episode itself after the edition, turn on Episode notes in Settings › Listening.", notes.text)
        assertEquals(BannerLink.LISTENING_SETTINGS, notes.link)
        assertEquals("Settings › Listening", notes.linked)

        assertEquals(
            "Transcript of “${q("Rail Talk: night trains")}”. To play the episode itself in its place, turn on Transcripts in Settings › Listening.",
            text(EpisodeKind.TRANSCRIPT, Outcome.Read(Why.KIND_OFF), EpisodePair("g", "Rail Talk: night trains", EpisodeKind.NOTES)),
        )
        assertEquals(
            "Has a recorded reading. To play it in its place, turn on Narrated articles in Settings › Listening.",
            text(EpisodeKind.ARTICLE, Outcome.Read(Why.KIND_OFF)),
        )
        assertEquals(
            "Has an audio version, “${q("October 15")}”. To play it in this post's place, turn on Narrated articles in Settings › Listening.",
            text(EpisodeKind.ARTICLE, Outcome.Read(Why.KIND_OFF), EpisodePair("g", "October 15", EpisodeKind.AUDIO_VERSION)),
        )

        val source = banner(EpisodeKind.TRANSCRIPT, Outcome.Read(Why.SOURCE_OFF))!!
        assertEquals("Transcript of an episode. You've turned off episode audio for this source.", source.text)
        assertEquals(BannerLink.SOURCE, source.link)
        assertEquals("this source", source.linked)
        assertEquals(BannerLink.SOURCE, banner(EpisodeKind.NOTES, Outcome.Read(Why.SOURCE_OFF))?.link)
    }

    /** A transcript the voice reads says why: the banner never promises an episode that won't play. */
    @Test
    fun aTranscriptReadByTheVoiceSaysWhy() {
        val head = "Transcript of an episode."
        assertEquals("$head Listen plays the episode in its place.", text(EpisodeKind.TRANSCRIPT, Outcome.Plays(5_220)))
        assertEquals("$head No episode audio found, so Listen reads it.", text(EpisodeKind.TRANSCRIPT, Outcome.Read(Why.NO_AUDIO)))
        assertEquals("$head Its audio is a 3-min preview, so Listen reads it.", text(EpisodeKind.TRANSCRIPT, Outcome.Read(Why.PREVIEW, 170)))
        assertEquals("$head Its episode's length isn't known, so Listen reads it.", text(EpisodeKind.TRANSCRIPT, Outcome.Read(Why.LENGTH_UNKNOWN)))
        assertEquals("$head Its audio doesn't match its length, so Listen reads it.", text(EpisodeKind.TRANSCRIPT, Outcome.Read(Why.LENGTH_MISMATCH)))
        assertEquals("$head Its episode plays with “${q("Rail Talk: night trains")}”.", text(EpisodeKind.TRANSCRIPT, Outcome.PlaysWith(2)))
    }

    @Test
    fun anArticleSaysSoOnlyWhenItHasARecordingOrAPair() {
        assertEquals("Has a recorded reading. Listen plays it in its place.", text(EpisodeKind.ARTICLE, Outcome.Plays(640)))
        assertNull("its own audio isn't a reading of it", text(EpisodeKind.ARTICLE, null))
        assertEquals(
            "Its transcript is a separate post, “${q("Rail Talk")}”.",
            text(EpisodeKind.ARTICLE, null, EpisodePair("g", "Rail Talk", EpisodeKind.TRANSCRIPT)),
        )
        // A post never judged is left to what the detector says.
        assertNull(text(null, null))
    }

    /** An audio version plays with its post, or on its own page when its post isn't in the edition. */
    @Test
    fun anAudioVersionSaysWhereItsRecordingPlays() {
        val pair = EpisodePair("g", "October 15", EpisodeKind.ARTICLE)
        val head = "Audio version of “${q("October 15")}”"
        assertEquals("$head. Listen plays it with that post.", text(EpisodeKind.AUDIO_VERSION, Outcome.PlaysWith(2), pair))
        assertEquals("$head. Listen plays it here.", text(EpisodeKind.AUDIO_VERSION, Outcome.Plays(720), pair))
        assertEquals("$head. To play it, turn on Narrated articles in Settings › Listening.", text(EpisodeKind.AUDIO_VERSION, Outcome.Read(Why.KIND_OFF), pair))
        assertEquals("$head, but no audio file found.", text(EpisodeKind.AUDIO_VERSION, Outcome.Read(Why.NO_AUDIO), pair))
        assertEquals("Audio version of another post. Listen plays it here.", text(EpisodeKind.AUDIO_VERSION, Outcome.Plays(720)))
    }
}
