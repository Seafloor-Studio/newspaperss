package com.app.newspaperss.work

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.foreground.SystemForegroundService
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.testutil.TestApp
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private var shown: ForegroundInfo? = null
    private val allowed = ForegroundUpdater { _: Context, _: UUID, info: ForegroundInfo ->
        shown = info
        Futures.immediateFuture(null)
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        PodcastWorker.holdOffUntil = 0
        app.container.notifier.createChannels()
    }

    @After fun tearDown() {
        PodcastWorker.holdOffUntil = 0
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun queued() = WorkManager.getInstance(app).getWorkInfosForUniqueWork("podcast").get()
        .count { it.state == WorkInfo.State.ENQUEUED }

    private fun onScreen(yes: Boolean) = shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(
        listOf(
            ActivityManager.RunningAppProcessInfo(app.packageName, Process.myPid(), arrayOf(app.packageName)).apply {
                importance = if (yes) ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND else ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
            },
        ),
    )

    private fun unrestricted(yes: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, yes)

    private fun thermal(status: Int) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(status)

    private fun batterySaver(yes: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(yes)

    private suspend fun run(updater: ForegroundUpdater) =
        TestListenableWorkerBuilder<PodcastWorker>(app).setForegroundUpdater(updater).build().doWork()

    /**
     * Every heat Android can report is named, and every one it calls moderate or worse stops the
     * run. A number nobody has a name for still reads as something in the log.
     */
    @Test
    fun everyHeatWorseThanLightStopsTheRunAndIsNamed() = runTest {
        unrestricted(true)
        val hot = listOf(
            PowerManager.THERMAL_STATUS_MODERATE to "moderate",
            PowerManager.THERMAL_STATUS_SEVERE to "severe",
            PowerManager.THERMAL_STATUS_CRITICAL to "critical",
            PowerManager.THERMAL_STATUS_EMERGENCY to "emergency",
            PowerManager.THERMAL_STATUS_SHUTDOWN to "shutdown",
        )
        for ((status, name) in hot) {
            thermal(status)
            shown = null
            assertTrue(run(allowed) is ListenableWorker.Result.Success)
            assertNull("heat $name should have held the run back", shown)
            assertTrue(
                "the log should name heat $name",
                app.container.podcastLog.read().any { it.contains(name) },
            )
        }
    }

    /** A status Android grew since this was written still reads as something in the log. */
    @Test
    fun everyHeatAndroidCanReportIsNamed() {
        val statuses = listOf(
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        )
        val names = statuses.map { PodcastWorker.thermalName(it) }
        assertEquals(statuses.size, names.distinct().size)
        assertEquals("moderate", PodcastWorker.thermalName(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("status 99", PodcastWorker.thermalName(99))
    }

    /** The number alone sent a session guessing at reason 6; reason 4 is the one that means heat. */
    @Test
    fun androidsStopReasonsAreNamed() {
        val reasons = listOf(
            WorkInfo.STOP_REASON_CANCELLED_BY_APP,
            WorkInfo.STOP_REASON_PREEMPT,
            WorkInfo.STOP_REASON_TIMEOUT,
            WorkInfo.STOP_REASON_DEVICE_STATE,
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW,
            WorkInfo.STOP_REASON_QUOTA,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY,
            WorkInfo.STOP_REASON_USER,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING,
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED,
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT,
            WorkInfo.STOP_REASON_NOT_STOPPED,
            WorkInfo.STOP_REASON_UNKNOWN,
        )
        val names = reasons.map { PodcastWorker.stopReasonName(it) }
        // One arm per reason: a copy-paste that named two the same would read as the wrong stop.
        assertEquals(reasons.size, names.distinct().size)
        assertTrue(names.none { it == "unnamed" })
        assertEquals("unplugged", PodcastWorker.stopReasonName(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING))
        assertEquals("device state, heat most likely", PodcastWorker.stopReasonName(WorkInfo.STOP_REASON_DEVICE_STATE))
        assertEquals("unnamed", PodcastWorker.stopReasonName(-99))
    }

    @Test
    fun aHotPhoneIsLeftAloneAndTriedAgainLater() = runTest {
        unrestricted(true)
        thermal(PowerManager.THERMAL_STATUS_MODERATE)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)

        // Kokoro is never loaded: no foreground notification, and the work waits to cool off.
        assertNull(shown)
        assertEquals(1, queued())
    }

    @Test
    fun lightHeatIsNotTooHotToMakeSpeech() = runTest {
        unrestricted(true)
        thermal(PowerManager.THERMAL_STATUS_LIGHT)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertTrue(shown != null)
    }

    @Test
    fun batterySaverLeavesThePhoneAlone() = runTest {
        unrestricted(true)
        batterySaver(true)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertNull(shown)
        assertEquals(1, queued())
    }

    @Test
    fun unrestrictedItGoesForegroundQuietlyAsAKindTheManifestDeclares() = runTest {
        unrestricted(true)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)

        val info = shown!!
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(info.notification.channelId)
        assertEquals(Notifier.PODCAST, channel.id)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        // A kind the service doesn't declare crashes the app inside WorkManager's service.
        val declared = app.packageManager.getServiceInfo(ComponentName(app, SystemForegroundService::class.java), 0).foregroundServiceType
        assertTrue(info.foregroundServiceType != 0)
        assertEquals(info.foregroundServiceType, info.foregroundServiceType and declared)
    }

    @Test
    fun restrictedAndOffScreenItDoesntAskButStillMakesIt() = runTest {
        unrestricted(false)
        onScreen(false)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        // Asked and refused, WorkManager would ignore the phone being unplugged.
        assertNull(shown)
    }

    @Test
    fun restrictedButOnScreenItAsks() = runTest {
        unrestricted(false)
        onScreen(true)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertTrue(shown != null)
    }

    @Test
    fun refusedItEndsAndTheNextRunStaysInTheBackground() = runTest {
        unrestricted(true)
        // What Android 12 and later say when foreground work starts from the background.
        val refused = ForegroundUpdater { _: Context, _: UUID, _: ForegroundInfo ->
            Futures.immediateFailedFuture<Void>(IllegalStateException("startForegroundService() not allowed"))
                as ListenableFuture<Void>
        }
        // Ended, so WorkManager lets go of the half-foreground work, and queued to go on.
        assertTrue(run(refused) is ListenableWorker.Result.Success)
        assertEquals(1, queued())

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertNull(shown)
    }

    @Test
    fun makingStopsWhenItShouldntGoOnAndSaysSo() = runTest {
        var going = true
        var made = false
        val stopped = async {
            PodcastWorker.makeWhile(1_000, {
                delay(10_000)
                made = true
            }, {}) { going }
        }
        advanceTimeBy(2_500)
        going = false
        advanceTimeBy(1_000)
        assertTrue(stopped.await())
        assertFalse(made)
    }

    @Test
    fun makingThatFinishesIsntStopped() = runTest {
        assertFalse(PodcastWorker.makeWhile(1_000, { delay(2_500) }, {}) { true })
    }

    @Test
    fun stoppedFromOutsideItSaysSoBeforeTheSentenceEnds() = runTest {
        var told = false
        var sentenceDone = false
        val work = launch {
            PodcastWorker.makeWhile(1_000, {
                // Kokoro's native code finishes its sentence whatever the coroutine says.
                withContext(NonCancellable) { delay(10_000) }
                sentenceDone = true
            }, { told = true }) { true }
        }
        advanceTimeBy(2_500)
        work.cancel()
        runCurrent()
        assertTrue(told)
        assertFalse(sentenceDone)
        work.join()
    }

    @Test
    fun stoppedFromOutsideBetweenSentencesItSaysSoToo() = runTest {
        var told = false
        val work = launch { PodcastWorker.makeWhile(1_000, { delay(10_000) }, { told = true }) { true } }
        advanceTimeBy(2_500)
        work.cancel()
        work.join()
        assertTrue(told)
    }
}
