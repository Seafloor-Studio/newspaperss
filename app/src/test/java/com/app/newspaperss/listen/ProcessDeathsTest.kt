package com.app.newspaperss.listen

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

/** Reading back what Android says ended the last process. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ProcessDeathsTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun ended(reason: Int, status: Int = 0, rss: Long = 0, pss: Long = 0) {
        val info = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(app.packageName)
            .setReason(reason)
            .setStatus(status)
            .setRss(rss)
            .setPss(pss)
            .setTimestamp(TIME)
            .build()
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(info)
    }

    @Test
    fun withNoRecordItSaysNothing() {
        assertNull(ProcessDeaths.last(app))
    }

    /**
     * Nothing to seed: ApplicationExitInfo doesn't exist before Android 11, which is why the
     * record can't be read there. Robolectric won't even build one in an SDK 29 sandbox.
     */
    @Test
    @Config(sdk = [29])
    fun beforeAndroidElevenThereIsNoRecordToRead() {
        assertNull(ProcessDeaths.last(app))
    }

    @Test
    fun runningOutOfMemoryIsAndroidsDoingNotTheApps() {
        ended(ApplicationExitInfo.REASON_LOW_MEMORY, rss = 1_400_000, pss = 1_200_000)

        val exit = ProcessDeaths.last(app)!!
        assertFalse("a line can't be blamed for the phone running out of memory", exit.crash)
        assertTrue(exit.abnormal)
        assertEquals("the phone was out of memory, 1367 MB resident, 1171 MB proportional", exit.description)
        assertEquals(TIME, exit.atMs)
    }

    @Test
    fun aNativeCrashIsTheAppsOwn() {
        ended(ApplicationExitInfo.REASON_CRASH_NATIVE)

        val exit = ProcessDeaths.last(app)!!
        assertTrue(exit.crash)
        assertTrue(exit.abnormal)
        assertEquals("native crash", exit.name)
    }

    @Test
    fun usingTooMuchOfThePhoneIsAndroidsDoing() {
        ended(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)

        val exit = ProcessDeaths.last(app)!!
        assertFalse(exit.crash)
        assertTrue(exit.abnormal)
    }

    @Test
    fun theReaderClosingItSaysNothingWorthLogging() {
        ended(ApplicationExitInfo.REASON_USER_REQUESTED)

        val exit = ProcessDeaths.last(app)!!
        assertFalse(exit.crash)
        assertFalse(exit.abnormal)
    }

    /**
     * A signal is both kinds of ending: SIGKILL is Android stopping the process, and the rest are
     * the app's own native code failing. Taking every signal for a crash would blame a line for a
     * kill, which is the whole thing this exists to avoid.
     */
    @Test
    fun sigkillIsAStopAndOtherSignalsAreCrashes() {
        assertFalse(ProcessDeaths.crash(ApplicationExitInfo.REASON_SIGNALED, SIGKILL))
        assertTrue(ProcessDeaths.crash(ApplicationExitInfo.REASON_SIGNALED, SIGSEGV))
        assertEquals("stopped by Android (SIGKILL)", ProcessDeaths.name(ApplicationExitInfo.REASON_SIGNALED, SIGKILL))
        assertEquals("signal 11", ProcessDeaths.name(ApplicationExitInfo.REASON_SIGNALED, SIGSEGV))
    }

    @Test
    fun everyReasonAndroidHasIsNamed() {
        val reasons = listOf(
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
        )
        val names = reasons.map { ProcessDeaths.name(it, 0) }
        assertEquals(reasons.size, names.distinct().size)
        assertTrue(names.none { it.startsWith("reason ") })
        assertEquals("reason 99", ProcessDeaths.name(99, 0))
    }

    private companion object {
        const val TIME = 1_760_000_000_000L
        const val SIGKILL = 9
        const val SIGSEGV = 11
    }
}
