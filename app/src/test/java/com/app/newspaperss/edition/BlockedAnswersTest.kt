package com.app.newspaperss.edition

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The last few answers from sites that turned the app away, kept across a restart. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class BlockedAnswersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theLatestAnswerForAnAddressIsKeptAndTheOldestGo() {
        val file = tmp.root.resolve("blocked.json")
        val answers = BlockedAnswers(file, keep = 2)
        assertNull("none yet", answers.find("https://a.example/1"))
        answers.record("https://a.example/1", "HTTP 403")
        answers.record("https://a.example/1", "HTTP 429")
        assertEquals("a newer answer replaces the older", "HTTP 429", answers.find("https://a.example/1"))

        answers.record("https://a.example/2", "HTTP 403")
        answers.record("https://a.example/3", "HTTP 403")
        assertNull("only the last two are kept", answers.find("https://a.example/1"))
        assertEquals("kept across a restart", "HTTP 403", BlockedAnswers(file, keep = 2).find("https://a.example/3"))
    }

    @Test
    fun aFileThatWontReadIsNothingToTell() {
        val file = tmp.root.resolve("blocked.json").apply { writeText("{not json") }
        assertNull(BlockedAnswers(file).find("https://a.example/1"))
        BlockedAnswers(file).record("https://a.example/1", "HTTP 403")
        assertEquals("HTTP 403", BlockedAnswers(file).find("https://a.example/1"))
    }
}
