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
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { "Condition not met within $timeoutMs ms" }
        Thread.sleep(10)
    }
}

/**
 * [idleUntil] inside `runTest`: also yields between checks, so the coroutines the test launched get
 * to run, as well as work on other threads and the main looper.
 */
suspend fun idleUntilYielding(timeoutMs: Long = 30_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { "Condition not met within $timeoutMs ms" }
        Thread.sleep(10)
        yield()
    }
}
