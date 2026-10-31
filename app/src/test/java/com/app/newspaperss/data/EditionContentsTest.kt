package com.app.newspaperss.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * An edition's contents say where each article came from, so the way back to its page is there
 * from the paper (see [EditionContent.source]).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class EditionContentsTest {
    @get:Rule val closeDb = closeAfter { db.close() }

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    private suspend fun edition(): Long =
        db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = Instant.parse("2026-10-12T06:00:00Z"), status = EditionStatus.DELIVERED))

    private suspend fun put(editionId: Long, position: Int, articleId: Long?, byline: String) =
        db.editions().insertArticles(listOf(EditionArticleEntity(editionId = editionId, articleId = articleId, position = position, title = "A story", sourceTitle = byline, minutes = 4.0)))

    private suspend fun contents(editionId: Long) = db.editions().observeContents(editionId).first()

    /** The article page says when a transcript's audio is only a preview: it needs both posts' lengths. */
    @Test
    fun aTranscriptsContentsCarryItsAudiosLengthAndItsNotesPosts() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Show"))
        val transcript = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = "t", url = "https://example.com/t", title = "Transcript", episodeKind = EpisodeKind.TRANSCRIPT, audioUrl = "https://cdn.example/t.mp3", audioSeconds = 300, episodePair = "n"),
        )
        db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "n", url = "https://example.com/n", title = "Episode 12", episodeKind = EpisodeKind.NOTES, audioUrl = "https://cdn.example/n.mp3", audioSeconds = 5_400))
        val id = edition()
        put(id, 0, transcript, "The Example Show")

        val c = contents(id).single()
        assertEquals(true to true, c.hasAudio to c.pairHasAudio)
        assertEquals(300, c.audioSeconds)
        assertEquals(5_400, c.pairAudioSeconds)
        assertEquals(EpisodePair("n", "Episode 12", EpisodeKind.NOTES), c.pair)

        // An article without audio, paired with nothing.
        val plain = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "p", url = "https://example.com/p", title = "Plain"))
        val other = edition()
        put(other, 0, plain, "The Example Show")
        assertEquals(false to false, contents(other).single().let { it.hasAudio to it.pairHasAudio })
    }

    @Test
    fun aFeedsArticlePointsAtTheFeedsOwnPage() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "a", url = "https://example.com/a", title = "A story"))
        val id = edition()
        put(id, 0, article, "Example News")

        assertEquals(ArticleSource(source, SourceKind.FEED, null, "Example News", paused = false), contents(id).single().source)
    }

    @Test
    fun attRssArticlePointsAtItsFeedWithinTheAccountNotTheAccount() = runTest {
        val account = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = SourceRepository.TTRSS_TITLE))
        val article = db.articles().insertIgnoring(
            ArticleEntity(sourceId = account, guid = "ttrss:7", url = "https://quanta.example/knots", title = "A story", originId = "7", originTitle = "Quanta Magazine"),
        )
        val id = edition()
        put(id, 0, article, "Quanta Magazine")

        // The feed, by the key its page is addressed by, and named for itself: an account has no page.
        assertEquals(ArticleSource(account, SourceKind.TTRSS, "7", "Quanta Magazine", paused = false), contents(id).single().source)
    }

    @Test
    fun aLinkPostPointsAtThePickerNotTheSiteItPickedOut() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://longreads.example/feed", title = "Longreads"))
        val article = db.articles().insertIgnoring(
            ArticleEntity(sourceId = source, guid = "p", url = "https://equator.example/story", title = "A story", viaUrl = "https://longreads.example/pick"),
        )
        val id = edition()
        // Its byline credits both; the page to go to is the one that picked it.
        put(id, 0, article, "Equator via Longreads")

        assertEquals("Longreads", contents(id).single().source!!.title)
    }

    @Test
    fun anArticleWhoseSourceIsGoneHasNoPageToGoTo() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "a", url = "https://example.com/a", title = "A story"))
        val id = edition()
        put(id, 0, article, "Example News")
        db.sources().delete(db.sources().byId(source)!!)

        val content = contents(id).single()
        assertNull("its article went with its source", content.entry.articleId)
        assertNull(content.source)
        assertEquals("the edition still names where it came from", "Example News", content.entry.sourceTitle)
    }

    @Test
    fun attRssArticleThatDoesntNameItsFeedHasNoPageToGoTo() = runTest {
        val account = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = SourceRepository.TTRSS_TITLE))
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = account, guid = "t", url = "https://a.example/1", title = "A story"))
        val id = edition()
        put(id, 0, article, SourceRepository.TTRSS_TITLE)

        assertNull("an account has no page of its own, so there is nowhere to go", contents(id).single().source)
    }

    @Test
    fun attRssFeedIsNamedAsTheAccountListsItNowNotAsTheArticleCarriesIt() = runTest {
        val account = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = SourceRepository.TTRSS_TITLE))
        // Renamed in tt-rss since the article was fetched: the page it opens has the new name on it.
        db.sources().savePublication(PublicationEntity(account, "7", title = "Quanta"))
        val article = db.articles().insertIgnoring(
            ArticleEntity(sourceId = account, guid = "ttrss:7", url = "https://quanta.example/knots", title = "A story", originId = "7", originTitle = "Quanta Magazine"),
        )
        val id = edition()
        put(id, 0, article, "Quanta Magazine")

        assertEquals("Quanta", contents(id).single().source!!.title)
    }

    @Test
    fun attRssArticleWithNoNameForItsFeedIsNotOfferedTheAccountsOwnName() = runTest {
        val account = db.sources().insert(SourceEntity(kind = SourceKind.TTRSS, url = "https://rss.example/api/", title = SourceRepository.TTRSS_TITLE))
        // tt-rss's headlines carry feed_title as a nullable field, so a feed id can arrive unnamed.
        val article = db.articles().insertIgnoring(
            ArticleEntity(sourceId = account, guid = "ttrss:7", url = "https://a.example/1", title = "A story", originId = "7"),
        )
        val id = edition()
        put(id, 0, article, SourceRepository.TTRSS_TITLE)

        assertNull("Tiny Tiny RSS is the account, and an account has no page", contents(id).single().source)
    }

    @Test
    fun aSavedLinkPointsAtTheReadingList() = runTest {
        val list = db.sources().insert(SourceEntity(kind = SourceKind.READING_LIST, url = "newspaperss://reading-list", title = "Reading list"))
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = list, guid = "s", url = "https://example.com/saved", title = "A story"))
        val id = edition()
        put(id, 0, article, "Reading list")

        assertEquals(SourceKind.READING_LIST, contents(id).single().source!!.kind)
    }

    @Test
    fun aPausedSourceSaysSoSoAMovedFeedCanBeTold() = runTest {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News", paused = true))
        val article = db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "a", url = "https://example.com/a", title = "A story"))
        val id = edition()
        put(id, 0, article, "Example News")

        assertEquals(true, contents(id).single().source!!.paused)
    }
}
