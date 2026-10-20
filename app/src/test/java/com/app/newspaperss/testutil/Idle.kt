package com.app.newspaperss.testutil

import android.os.Looper
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
fun idleUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
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

/**
 * The threads worth naming in a failure message, with the top of each stack. Not only the running
 * and blocked ones: a coroutine worker that lost its wakeup is parked in WAITING, and that is the
 * thread a stuck test most needs named. So anything in the app's own code, the libraries it waits
 * on, or Robolectric, whatever its state, and anything running or blocked whoever owns it.
 */
private fun interestingThreads(): List<String> = Thread.getAllStackTraces()
    .entries
    .filter { (thread, stack) ->
        thread.state == Thread.State.RUNNABLE || thread.state == Thread.State.BLOCKED ||
            stack.any { frame -> OURS.any(frame.className::startsWith) }
    }
    .sortedBy { it.key.name }
    .take(14)
    .map { (thread, stack) ->
        val frames = stack.take(8).joinToString("\n") { "      at $it" }
        "  ${thread.name} (${thread.state})\n$frames\n"
    }

private val OURS = listOf("com.app.newspaperss", "androidx.", "kotlinx.coroutines", "org.robolectric")
