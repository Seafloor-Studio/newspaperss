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
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { ranOut(timeoutMs, started) }
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
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { ranOut(timeoutMs, started) }
        Thread.sleep(10)
        yield()
    }
}

/**
 * What was going on when a wait ran out. Without this a timeout only says that it happened, which
 * can't tell a deadline missed because the machine was busy from a test that is genuinely stuck:
 * the first leaves threads running or parked and the looper drained, the second leaves a thread
 * blocked, or the looper holding work that never ran.
 *
 * Wall clock, not the deadline: a run far past it is a sign the whole JVM was starved.
 */
private fun ranOut(timeoutMs: Long, startedAt: Long): String {
    val waited = System.currentTimeMillis() - startedAt
    val looper = runCatching { shadowOf(Looper.getMainLooper()).isIdle }.getOrNull()
    val threads = runCatching { busyThreads() }.getOrDefault(emptyList())
    return buildString {
        append("Condition not met within $timeoutMs ms (waited $waited ms). ")
        append("Main looper idle: ${looper ?: "couldn't tell"}.")
        if (threads.isEmpty()) append(" No thread was running or blocked.") else {
            append(" Threads running or blocked, and what they were in:\n")
            threads.forEach(::append)
        }
    }
}

/** Every live thread that was running or blocked, with the top of its stack. Bounded: this ends up in a failure message. */
private fun busyThreads(): List<String> = Thread.getAllStackTraces()
    .filterKeys { it.state == Thread.State.RUNNABLE || it.state == Thread.State.BLOCKED }
    .entries
    .sortedBy { it.key.name }
    .take(12)
    .map { (thread, stack) ->
        val frames = stack.take(8).joinToString("\n") { "      at $it" }
        "  ${thread.name} (${thread.state})\n$frames\n"
    }
