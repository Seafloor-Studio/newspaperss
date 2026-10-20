package com.app.newspaperss.testutil

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
        // Read before the looper is drained, so it can say something other than "idle".
        assertTrue(said, said.contains("The main looper had nothing waiting") || said.contains("still had work waiting"))
        // This thread is running it, so there is always at least one to name.
        assertTrue(said, said.contains("Threads, and what they were in"))
    }

    /**
     * The thread a stuck test needs named is parked in WAITING, in our own code, and a busy
     * machine runs plenty of the JVM's own threads that would crowd it out of a capped message.
     * Made up rather than started, so this says the same thing on every machine.
     */
    @Test
    fun ourOwnThreadIsNamedThoughItIsParkedAndTheJvmsAreMany() {
        val ourFrame = StackTraceElement("com.app.newspaperss.ui.SettingsViewModel", "settings", "SettingsViewModel.kt", 42)
        val jvmFrame = StackTraceElement("java.lang.ref.Reference", "waitForReferencePendingList", "Reference.java", 253)
        val jvm = (1..20).map { ThreadLook("JVM thread $it", Thread.State.RUNNABLE, listOf(jvmFrame)) }
        val ours = ThreadLook("a-parked-worker", Thread.State.WAITING, listOf(jvmFrame, ourFrame))

        val chosen = chooseThreads(jvm + ours)

        assertTrue(chosen.toString(), chosen.any { it.startsWith("  a-parked-worker (WAITING)") })
        assertTrue("ours comes first, so a cap can't cut it", chosen.first().startsWith("  a-parked-worker"))
        assertEquals("the cap still holds", 14, chosen.size)
    }

    @Test
    fun aThreadDoingNothingOfOursIsLeftOut() {
        val idle = ThreadLook("Finalizer", Thread.State.WAITING, listOf(StackTraceElement("java.lang.Object", "wait", "Object.java", 1)))

        assertTrue(chooseThreads(listOf(idle)).isEmpty())
    }

    @Test
    fun aWaitThatPassesReturnsAtOnceWhateverTheDeadline() {
        idleUntil(timeoutMs = 1) { true }
    }
}
