package com.app.newspaperss.core.episodes

import com.app.newspaperss.core.feed.Enclosure
import com.app.newspaperss.core.feed.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cases the corpus can't hold: hostile posts, and the few posts tt-rss sends at a time. */
class EpisodeDetectorTest {
    private fun item(guid: String, title: String, html: String, audio: String? = null) =
        FeedItem(guid, "https://a.example/$guid", title, html, null, null, listOfNotNull(audio?.let { Enclosure(it, "audio/mpeg") }))

    @Test
    fun aPostOfDeeplyNestedBoldIsMeasuredWithoutCopyingItsTextForEachTag() {
        // 600 tags deep around 400,000 characters: copying the text once per tag would need
        // hundreds of megabytes, which a sync must never ask for.
        val html = "<p>" + "<b>".repeat(600) + "word ".repeat(80_000) + "</b>".repeat(600) + "</p>"
        val verdicts = EpisodeDetector.classify(EpisodeFeed.of(listOf(item("1", "A post", html))))
        assertEquals(EpisodeKind.ARTICLE, verdicts.getValue("1").kind)
    }

    @Test
    fun oneShortPostWithABlogsOwnMp3IsNotAPodcastsNotes() {
        // All that tt-rss sent of a blog: one unread post, with a song attached.
        val verdicts = EpisodeDetector.classify(EpisodeFeed.of(listOf(item("1", "A new song", "<p>Listen to this one.</p>", "https://blog.example/song.mp3"))))
        assertEquals(EpisodeKind.ARTICLE, verdicts.getValue("1").kind)
    }

    @Test
    fun aStoryCarryingItsEpisodesAudioStaysAnArticleBesideJustTheEpisode() {
        val episode = "https://traffic.libsyn.com/magazine/72.mp3"
        val verdicts = EpisodeDetector.classify(
            EpisodeFeed.of(
                listOf(
                    item("1", "Magazine Podcast 72", "<p>This episode reads a story.</p>", episode),
                    item("2", "Butterfly Effect", "<p>The opening lines of the story.</p>", episode),
                ),
            ),
        )
        assertEquals(EpisodeKind.NOTES, verdicts.getValue("1").kind)
        assertEquals(EpisodeKind.ARTICLE, verdicts.getValue("2").kind)
    }

    @Test
    fun timestampsInNestedListsAreCountedOnce() {
        val nested = "<ul><li>Topics<ul>" + (1..6).joinToString("") { "<li>(0$it:00) Part $it</li>" } + "</ul></li></ul>"
        val post = EpisodePost("1", "Episode", "https://a.example/1", nested, emptyList())
        assertEquals(6, EpisodeDetector.signals(post).timestamps)
    }
}
