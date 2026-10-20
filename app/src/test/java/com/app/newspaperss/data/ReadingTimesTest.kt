package com.app.newspaperss.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.DbRule
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ReadingTimesTest {
    @get:Rule val dbRule = DbRule()
    private val db = dbRule.db
    private val http = FakeHttp()
    private val list = ReadingListRepository(db)
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("reading-times-test", Context.MODE_PRIVATE)
    private val times = ReadingTimes(db, ReadingListTitles(db, http), prefs)
    private val fetched = mutableListOf<String>()

    init {
        http.beforeResponse = { fetched += it }
    }

    private fun article(words: Int) =
        "<html><head><title>A page</title></head><body><article><p>${"word ".repeat(words)}</p></article></body></html>"

    private suspend fun words(url: String) = db.articles().allForSource(list.sourceId()).single { it.url == url }.pageWords

    /** A Pocket export's links come with titles, so nothing looks their pages up when they arrive. */
    private suspend fun importTitled(vararg urls: String) {
        list.import("title,url,time_added,tags,status\n" + urls.joinToString("") { "Saved,$it,1712345678,,unread\n" })
    }

    @Test
    fun aPageThatWontLoadDoesntHoldUpTheLinksAfterIt() = runTest {
        val stuck = (1..ReadingTimes.BATCH).map { "https://a.example/down/$it" }
        importTitled(*stuck.toTypedArray(), "https://b.example/fine")
        http.unreachable += stuck
        http.page("https://b.example/fine", article(476))

        assertEquals(ReadingTimes.BATCH, times.measureNext())
        assertNull(words("https://b.example/fine"))
        assertEquals(1, times.measureNext())
        assertTrue("about two minutes' worth", (words("https://b.example/fine") ?: 0) in 400..500)
    }

    @Test
    fun aPageThatKeepsFailingIsTriedThreeTimesThenLeftButNewLinksStillAre() = runTest {
        importTitled("https://a.example/missing")
        repeat(ReadingTimes.MAX_PASSES * 2) { times.measureNext() }
        assertEquals(List(ReadingTimes.MAX_PASSES) { "https://a.example/missing" }, fetched)

        // Its time can still come from the next save of it, or an import.
        importTitled("https://b.example/later")
        http.page("https://b.example/later", article(476))
        times.measureNext()
        assertTrue((words("https://b.example/later") ?: 0) > 0)
        assertNull(words("https://a.example/missing"))
    }

    @Test
    fun aRunStoppedPartWayGoesOnFromTheLinkItWasOn() = runTest {
        importTitled("https://a.example/dead", "https://a.example/hangs", "https://b.example/fine")
        http.page("https://b.example/fine", article(476))
        var stopped = false
        // WorkManager stops a worker after ten minutes; a few hanging pages in a batch can take that.
        http.beforeResponse = {
            fetched += it
            if (it.endsWith("hangs") && !stopped) { stopped = true; throw CancellationException("stopped") }
        }

        assertTrue(runCatching { times.measureNext() }.exceptionOrNull() is CancellationException)
        times.measureNext()

        assertEquals(listOf("https://a.example/dead", "https://a.example/hangs", "https://a.example/hangs", "https://b.example/fine"), fetched)
        assertTrue((words("https://b.example/fine") ?: 0) > 0)
    }

    @Test
    fun aLinkSavedLongAfterTheBacklogStillGetsThreeTries() = runTest {
        importTitled("https://a.example/old")
        http.page("https://a.example/old", article(476))
        repeat(5) { times.measureNext() }

        importTitled("https://b.example/down-today")
        repeat(ReadingTimes.MAX_PASSES * 2) { times.measureNext() }
        assertEquals(ReadingTimes.MAX_PASSES, fetched.count { it == "https://b.example/down-today" })
    }

    @Test
    fun onlyLinksStillWaitingOnTheReadingListAreFetched() = runTest {
        importTitled("https://a.example/waiting", "https://a.example/pdf")
        // Archived in Pocket: arrives read.
        list.import("title,url,time_added,tags,status\nRead,https://a.example/read,1600000000,,archive\n")
        val pdf = db.articles().allForSource(list.sourceId()).single { it.url.endsWith("pdf") }
        db.articles().setUnreadable(pdf.id, "not a web page")
        // A feed's articles keep no reading time.
        val feed = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "Example News"))
        db.articles().insertNew(listOf(ArticleEntity(sourceId = feed, guid = "g", url = "https://example.com/post", title = "Post", discoveredAt = Instant.EPOCH)))
        http.page("https://a.example/waiting", article(476))

        assertEquals(1, times.measureNext())
        assertEquals(listOf("https://a.example/waiting"), fetched)
    }
}
