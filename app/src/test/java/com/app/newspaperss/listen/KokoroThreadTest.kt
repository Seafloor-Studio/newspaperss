package com.app.newspaperss.listen

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Kokoro's native work giving way to whatever else the phone is doing. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class KokoroThreadTest {
    @Test
    fun kokoroRunsAtLowPriorityOffTheCallersThread() {
        val caller = Thread.currentThread()
        val (thread, priority) = KokoroThread.run { Thread.currentThread() to Process.getThreadPriority(Process.myTid()) }

        assertNotEquals(caller, thread)
        assertTrue(priority > Process.THREAD_PRIORITY_DEFAULT)
        // Not in the background group, which can starve it.
        assertTrue(priority < Process.THREAD_PRIORITY_BACKGROUND)
    }

    @Test
    fun aFailureInKokoroReachesTheCallerAsItself() {
        val thrown = runCatching { KokoroThread.run { throw IllegalStateException("bad text") } }.exceptionOrNull()

        assertEquals(IllegalStateException::class.java, thrown?.javaClass)
        assertEquals("bad text", thrown?.message)
    }
}
