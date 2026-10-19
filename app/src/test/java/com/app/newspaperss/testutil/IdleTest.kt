package com.app.newspaperss.testutil

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A wait that runs out says what was going on, so a test that times out only when the machine is
 * busy can be told from one that is stuck. See [idleUntil].
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class IdleTest {
    @Test
    fun aWaitThatRunsOutSaysHowLongItWaitedAndWhatTheThreadsWereDoing() {
        val failure = runCatching { idleUntil(timeoutMs = 50) { false } }.exceptionOrNull()!!
        val said = failure.message.orEmpty()

        assertTrue(said, said.startsWith("Condition not met within 50 ms (waited "))
        // The real clock, so a run far past the deadline shows the JVM was starved.
        assertTrue(said, Regex("waited (\\d+) ms").find(said)!!.groupValues[1].toInt() >= 50)
        assertTrue(said, said.contains("Main looper idle: "))
        // This thread is running it, so there is always at least one to name.
        assertTrue(said, said.contains("Threads running or blocked"))
    }

    @Test
    fun aWaitThatPassesReturnsAtOnceWhateverTheDeadline() {
        idleUntil(timeoutMs = 1) { true }
    }
}
