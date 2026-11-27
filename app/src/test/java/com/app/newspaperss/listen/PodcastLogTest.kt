package com.app.newspaperss.listen

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = START

    private fun log(dir: File = tmp.newFolder(), keep: Int = 400, retainDays: Int = 14) =
        PodcastLog(dir, keep = keep, retainDays = retainDays, now = { clock })

    /**
     * The day files. Named, not listed: which dates these are depends on the time zone the test
     * runs in, so the assertions are about how many days there are and which entries survive.
     */
    private fun days(dir: File) = dir.listFiles().orEmpty().map { it.name }.filter { DAY.matches(it) }.sorted()

    @Test
    fun itKeepsTheNewestEntriesAndDropsTheOldest() {
        val log = log(keep = 5)
        repeat(30) { log.add("entry $it") }
        val kept = log.read()
        assertEquals(5, kept.size)
        assertTrue(kept.last().endsWith("entry 29"))
        assertTrue(kept.first().endsWith("entry 25"))
    }

    /**
     * Lines written before addresses were cut held a private feed's key in full. Reading and
     * sharing the log both leave it, and any sign-in, out; the rest of each line stays.
     */
    @Test
    fun keysAlreadyInTheLogDontLeaveThePhone() {
        val secret = "9f86d081-0000-4000-8000-00000000abcd"
        val log = log()
        log.add("Reading: edition 7 page 2 from line 2: https://api.example.com/feed/podcast/42/ep.mp3?token=$secret at 0s, speed 1.0")
        log.add("Reading: https://user:pa?ss@cdn.example.org/a.mp3#t=5 failed (offline); the voice from line 3")

        val shared = log.export()!!.readText()
        for (text in listOf(shared, log.read().joinToString("\n"))) {
            assertFalse(text, secret in text || "user" in text || "pa?ss" in text || "#t=5" in text)
            assertTrue(text, "https://api.example.com/feed/podcast/42/ep.mp3 at 0s, speed 1.0" in text)
            assertTrue(text, "https://cdn.example.org/a.mp3 failed (offline)" in text)
        }
    }

    @Test
    fun aLogThatCantBeWrittenDoesntStopTheMaking() {
        // A file where the folder should be: every write fails, as on a full disk.
        val log = PodcastLog(tmp.newFile())
        log.add("made a piece")
        assertEquals(emptyList<String>(), log.read())
        assertNull(log.export())
    }

    @Test
    fun eachDayGetsItsOwnFileAndTheyReadAsOneRun() {
        val dir = tmp.newFolder()
        val log = log(dir)
        log.add("first day")
        clock += DAY_MS
        log.add("second day")

        assertEquals(2, days(dir).size)
        assertTrue(log.read()[0].endsWith("first day"))
        assertTrue(log.read()[1].endsWith("second day"))
    }

    @Test
    fun manyEntriesInOneDayStayInOneFile() {
        val dir = tmp.newFolder()
        val log = log(dir)
        repeat(50) { log.add("entry $it") }

        assertEquals(1, days(dir).size)
        assertEquals(50, log.read().size)
    }

    @Test
    fun daysOlderThanTheFortnightGo() {
        val dir = tmp.newFolder()
        val log = log(dir, retainDays = 14)
        log.add("the first day")
        clock += 13 * DAY_MS
        log.add("thirteen days later")
        assertEquals(2, days(dir).size)

        clock += 2 * DAY_MS
        log.add("fifteen days later")

        assertEquals("the first day's file should have gone", 2, days(dir).size)
        assertFalse(log.read().any { it.endsWith("the first day") })
        assertTrue(log.read().any { it.endsWith("thirteen days later") })
        assertTrue(log.read().any { it.endsWith("fifteen days later") })
    }

    /**
     * The fold happens on the next write. Until then the old file is all there is, and the screen
     * and the share have to show it: this folder is private storage, so a reader who can't see it
     * there can't reach it at all.
     */
    @Test
    fun theOldFileIsReadableBeforeAnythingNewIsWritten() {
        val dir = tmp.newFolder()
        val old = tmp.newFile("podcast-log.txt").apply { writeText("10-18 04:55:42  Run started\n10-18 06:41:40  Kept page 5\n") }
        val log = PodcastLog(dir, legacy = old, now = { clock })

        // Nothing written since the update: no day file exists yet.
        assertEquals(0, days(dir).size)
        assertEquals(2, log.read().size)
        assertTrue(log.read()[0].endsWith("Run started"))
        assertEquals(2, log.export()!!.readLines().filter { it.isNotEmpty() }.size)
    }

    @Test
    fun theOldFileIsntCountedTwiceOnceFolded() {
        val dir = tmp.newFolder()
        val old = tmp.newFile("podcast-log.txt").apply { writeText("10-18 04:55:42  Run started\n") }
        val log = PodcastLog(dir, legacy = old, now = { clock })

        log.add("a new entry")

        assertEquals(2, log.read().size)
        assertTrue(log.read()[0].endsWith("Run started"))
        assertTrue(log.read()[1].endsWith("a new entry"))
    }

    @Test
    fun theOneFileItUsedToKeepIsFoldedInAndRemoved() {
        val dir = tmp.newFolder()
        val old = tmp.newFile("podcast-log.txt").apply { writeText("10-18 04:55:42  Run started\n") }
        val log = PodcastLog(dir, legacy = old, now = { clock })

        log.add("a new entry")

        assertFalse("the old file should be gone", old.exists())
        assertEquals(1, days(dir).size)
        assertTrue(log.read()[0].endsWith("Run started"))
        assertTrue(log.read()[1].endsWith("a new entry"))
    }

    @Test
    fun exportGathersEveryDayAndIsntTakenForADayItself() {
        val dir = tmp.newFolder()
        val log = log(dir, keep = 1)
        log.add("first day")
        clock += DAY_MS
        log.add("second day")

        val file = log.export()!!
        assertEquals("audio-log.txt", file.name)
        val sent = file.readLines().filter { it.isNotEmpty() }
        // The screen shows one entry; the file carries both days.
        assertEquals(1, log.read().size)
        assertEquals(2, sent.size)
        assertTrue(sent[0].endsWith("first day"))
        assertTrue(sent[1].endsWith("second day"))
        // Written beside the days, and not read back as one of them however often it's made.
        assertEquals(2, days(dir).size)
        log.export()
        assertEquals(2, log.export()!!.readLines().filter { it.isNotEmpty() }.size)
    }

    @Test
    fun withNothingLoggedThereIsNothingToShare() {
        assertNull(log().export())
    }

    private companion object {
        const val START = 1_792_000_000_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val DAY = Regex("""\d{4}-\d{2}-\d{2}\.txt""")
    }
}
