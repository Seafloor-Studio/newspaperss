package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingListTitlesTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val untitled = mutableListOf<Long>()
    private val list = ReadingListRepository(db, onUntitled = { untitled += it })
    private val titles = ReadingListTitles(db, http)

    private fun page(title: String) =
        "<html><head><title>$title</title><meta property=\"og:site_name\" content=\"Example Weekly\"></head><body><p>Text</p></body></html>"

    private suspend fun titleOf(url: String) = db.articles().allForSource(list.sourceId()).single { it.url == url }.title

    @Test
    fun aLinkSavedWithoutATitleGetsThePagesTitle() = runTest {
        http.page("https://a.example/tides", page("How tides work | Example Weekly"))
        list.save("https://a.example/tides")
        assertTrue(titles.fetch(untitled))
        assertEquals("How tides work", titleOf("https://a.example/tides"))
    }

    @Test
    fun aBotCheckPagesTitleIsNeverTaken() = runTest {
        // It would stick as the headline: the edition prefers a stored title to the page's.
        http.page("https://lemonde.example/story", "<html><head><title>Client Challenge</title></head><body><script src=\"/_fs-ch-1/x.js\"></script></body></html>")
        list.save("https://lemonde.example/story")
        titles.fetch(untitled)
        assertEquals("", titleOf("https://lemonde.example/story"))
    }

    @Test
    fun everySavedLinkIsMeasuredForItsReadingTime() = runTest {
        val words = "word ".repeat(476)
        http.page("https://a.example/long", "<html><head><title>A long read</title></head><body><article><p>$words</p></article></body></html>")
        list.save("https://a.example/long", "Given a title already")
        assertTrue(titles.fetch(untitled))
        val saved = db.articles().allForSource(list.sourceId()).single()
        assertEquals("Given a title already", saved.title)
        assertTrue("about two minutes' worth", (saved.pageWords ?: 0) in 400..500)
    }

    @Test
    fun aTitleTheReaderGaveIsKept() = runTest {
        http.page("https://a.example/tides", page("How tides work"))
        list.save("https://a.example/tides", "Tides, for Sam")
        val id = db.articles().candidates().single().id
        assertTrue(titles.fetch(listOf(id)))
        assertEquals("Tides, for Sam", titleOf("https://a.example/tides"))
    }

    @Test
    fun anArticleAnEditionTakesWhileThePageLoadsKeepsItsTitle() = runTest {
        http.page("https://a.example/tides", page("How tides work"))
        list.save("https://a.example/tides")
        // The edition shows the title it extracted itself; renaming the article now would disagree with it.
        http.beforeResponse = { db.articles().setState(untitled, ArticleState.IN_EDITION) }
        titles.fetch(untitled)
        assertEquals("", titleOf("https://a.example/tides"))
    }

    @Test
    fun anUnreachablePageIsWorthRetryingAMissingOneIsNot() = runTest {
        http.unreachable += "https://a.example/offline"
        list.save("https://a.example/offline")
        list.save("https://a.example/gone")
        http.page("https://a.example/later", page("Worth the wait"))
        list.save("https://a.example/later")

        assertFalse(titles.fetch(untitled))
        assertEquals("", titleOf("https://a.example/offline"))
        assertEquals("", titleOf("https://a.example/gone"))
        // One unreachable page doesn't stop the rest.
        assertEquals("Worth the wait", titleOf("https://a.example/later"))

        assertTrue(titles.fetch(untitled - untitled.first()))
    }

    @Test
    fun aSavedLinkThatIsNoWebPageIsMarkedUnreadableAtOnce() = runTest {
        http.page("https://a.example/report.pdf", "%PDF-1.7")
        http.contentTypes["https://a.example/report.pdf"] = "application/pdf"
        list.save("https://a.example/report.pdf")
        http.page("https://a.example/down", "", code = 503)
        list.save("https://a.example/down")

        titles.fetch(untitled)

        val saved = db.articles().allForSource(list.sourceId()).associateBy { it.url }
        assertEquals("not a web page", saved.getValue("https://a.example/report.pdf").unreadable)
        assertEquals("a server having a bad day may answer next time", null, saved.getValue("https://a.example/down").unreadable)
        assertEquals("editions leave it out", listOf("https://a.example/down"), db.articles().candidates().map { it.url })
        assertEquals("nor counted as waiting", 1, list.observeWaiting().first())
    }

    @Test
    fun aVideoTooLargeToDownloadAndAGonePageAreMarkedToo() = runTest {
        http.tooLarge["https://a.example/talk.mp4"] = "video/mp4"
        list.save("https://a.example/talk.mp4")
        http.page("https://a.example/removed", "", code = 410)
        list.save("https://a.example/removed")

        assertTrue("nothing to try again later", titles.fetch(untitled))

        val saved = db.articles().allForSource(list.sourceId()).associateBy { it.url }
        assertEquals("not a web page", saved.getValue("https://a.example/talk.mp4").unreadable)
        assertEquals("error 410", saved.getValue("https://a.example/removed").unreadable)
    }

    @Test
    fun savingItAgainLooksAgainAndAPageThatNowReadsIsBackInThePaper() = runTest {
        http.page("https://a.example/report", "%PDF-1.7")
        http.contentTypes["https://a.example/report"] = "application/pdf"
        list.save("https://a.example/report")
        titles.fetch(untitled)
        untitled.clear()

        // Fixed by the site since: an article after all.
        http.contentTypes.remove("https://a.example/report")
        http.page("https://a.example/report", page("The report"))
        assertFalse("it is on the list already", list.save("https://a.example/report"))
        titles.fetch(untitled)

        val saved = db.articles().allForSource(list.sourceId()).single()
        assertEquals(null, saved.unreadable)
        assertEquals(listOf("https://a.example/report"), db.articles().candidates().map { it.url })
    }

    @Test
    fun aCuratedListsPickIsNeverMarked() = runTest {
        val curated = db.sources().insert(SourceEntity(kind = SourceKind.LIST, url = "https://aldaily.example/", title = "A list"))
        val pick = db.articles().insertIgnoring(ArticleEntity(sourceId = curated, guid = "p", url = "https://a.example/paper.pdf", title = ""))
        http.page("https://a.example/paper.pdf", "%PDF-1.7")
        http.contentTypes["https://a.example/paper.pdf"] = "application/pdf"

        titles.fetch(listOf(pick))

        assertEquals(null, db.articles().byId(pick)!!.unreadable)
    }

    @Test
    fun aStarredLinkReadBeforeIsMarkedByAnEditionButOneInAnEditionIsNot() = runTest {
        list.save("https://a.example/once")
        list.save("https://a.example/in-paper")
        val saved = db.articles().allForSource(list.sourceId()).associateBy { it.url }
        val again = saved.getValue("https://a.example/once").id
        val inPaper = saved.getValue("https://a.example/in-paper").id
        db.articles().setState(listOf(again), ArticleState.DELIVERED)
        assertTrue(db.articles().setStarred(again, true, java.time.Instant.EPOCH))
        db.articles().setState(listOf(inPaper), ArticleState.IN_EDITION)

        db.articles().setUnreadable(again, "error 410")
        db.articles().setUnreadable(inPaper, "error 410")

        assertEquals("error 410", db.articles().byId(again)!!.unreadable)
        assertEquals("read after all", null, db.articles().byId(inPaper)!!.unreadable)
        assertEquals("no longer planned, starred or not", emptyList<String>(), db.articles().candidates().map { it.url })
    }
}
