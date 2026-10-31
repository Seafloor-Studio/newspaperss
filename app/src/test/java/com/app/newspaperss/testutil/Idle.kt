package com.app.newspaperss.testutil

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.yield
import org.robolectric.Shadows.shadowOf

/**
 * Waits for work that finishes on another thread and then resumes on the main
 * looper (DataStore, Room). Compose's waitUntil doesn't run those resumptions
 * under Robolectric, so this idles the looper itself between checks.
 *
 * [timeoutMs] only guards against hanging: a passing condition returns at once, so a generous
 * deadline costs nothing and a tight one fails correct code on a loaded runner.
 */
fun idleUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) = drainUntil(timeoutMs, condition)

/**
 * [idleUntil] in a Compose test: idles Compose too before each check. Under the v2 test rule a
 * composition's effects run on a test dispatcher that only moves when Compose is idled, so
 * draining the looper alone leaves a click's coroutine, or a state the screen must have drawn,
 * waiting for good.
 */
fun ComposeTestRule.idleUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) =
    drainUntil(timeoutMs) { waitForIdle(); condition() }

private fun drainUntil(timeoutMs: Long, condition: () -> Boolean) {
    val started = System.currentTimeMillis()
    val deadline = started + timeoutMs
    while (true) {
        val hadWork = hadWorkWaiting()
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { ranOut(timeoutMs, started, hadWork) }
        Thread.sleep(10)
    }
}

/**
 * [idleUntil] inside `runTest`: also yields between checks, so the coroutines the test launched get
 * to run, as well as work on other threads and the main looper.
 */
suspend fun idleUntilYielding(timeoutMs: Long = 30_000, condition: () -> Boolean) {
    val started = System.currentTimeMillis()
    val deadline = started + timeoutMs
    while (true) {
        val hadWork = hadWorkWaiting()
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { ranOut(timeoutMs, started, hadWork) }
        Thread.sleep(10)
        yield()
    }
}

/**
 * What was going on when a wait ran out. Without this a timeout only says that it happened, which
 * can't tell a deadline missed because the machine was busy from a test that is genuinely stuck.
 *
 * Wall clock, not the deadline: a wait that ends on time while the condition never came true is a
 * race, not a slow machine. [hadWork] is read before the looper is drained, since afterwards it is
 * idle by construction.
 */
private fun ranOut(timeoutMs: Long, startedAt: Long, hadWork: Boolean): String {
    val waited = System.currentTimeMillis() - startedAt
    val threads = runCatching { interestingThreads() }.getOrDefault(emptyList())
    return buildString {
        append("Condition not met within $timeoutMs ms (waited $waited ms). ")
        append(if (hadWork) "The main looper still had work waiting each time it was drained." else "The main looper had nothing waiting: whatever the wait is for was never posted to it.")
        if (threads.isEmpty()) append(" No thread to name.") else {
            append("\nThreads, and what they were in:\n")
            threads.forEach(::append)
        }
    }
}

/** Whether the main looper has work queued, read before it's drained. */
private fun hadWorkWaiting(): Boolean = runCatching { !shadowOf(Looper.getMainLooper()).isIdle }.getOrDefault(false)

/** One thread as a dump saw it: enough to choose and print it, and something a test can make up. */
internal data class ThreadLook(val name: String, val state: Thread.State, val stack: List<StackTraceElement>)

/**
 * The threads worth naming in a failure message, with the top of each stack. Not only the running
 * and blocked ones: a coroutine worker that lost its wakeup is parked in WAITING, and that is the
 * thread a stuck test most needs named. So anything in the app's own code, the libraries it waits
 * on, or Robolectric, whatever its state, and anything running or blocked whoever owns it.
 *
 * Ours come first. The cap is arbitrary, and a run on a busy machine has plenty of the JVM's and
 * the build's own threads; ordering by name alone let those fill the message and cut the thread
 * the message exists to name.
 *
 * Separate from the dump itself so the choosing can be tested on threads a test makes up, rather
 * than on whichever ones a machine happens to be running.
 */
internal fun chooseThreads(threads: List<ThreadLook>, cap: Int = 14): List<String> = threads
    .mapNotNull { look ->
        val ours = look.stack.any { frame -> OURS.any(frame.className::startsWith) }
        val busy = look.state == Thread.State.RUNNABLE || look.state == Thread.State.BLOCKED
        if (ours || busy) (if (ours) 0 else 1) to look else null
    }
    .sortedWith(compareBy({ it.first }, { it.second.name }))
    .take(cap)
    .map { (_, look) ->
        val frames = look.stack.take(8).joinToString("\n") { "      at $it" }
        "  ${look.name} (${look.state})\n$frames\n"
    }

private fun interestingThreads(): List<String> =
    chooseThreads(Thread.getAllStackTraces().map { (thread, stack) -> ThreadLook(thread.name, thread.state, stack.toList()) })

private val OURS = listOf("com.app.newspaperss", "androidx.", "kotlinx.coroutines", "org.robolectric")
