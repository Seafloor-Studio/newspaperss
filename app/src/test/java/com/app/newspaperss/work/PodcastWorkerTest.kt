package com.app.newspaperss.work

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
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
import org.robolectric.shadows.ShadowActivityManager
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
        PodcastWorker.lastExitNoted = 0
        app.container.notifier.createChannels()
    }

    @After fun tearDown() {
        PodcastWorker.holdOffUntil = 0
        PodcastWorker.lastExitNoted = 0
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

    private fun processEnded(reason: Int, rss: Long = 0, pss: Long = 0) {
        val info = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setProcessName(app.packageName)
            .setReason(reason)
            .setRss(rss)
            .setPss(pss)
            .setTimestamp(42L)
            .build()
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(info)
    }

    private fun noted(what: String) = app.container.podcastLog.read().count { it.contains(what) }

    private fun thermal(status: Int) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setCurrentThermalStatus(status)

    private fun plugged(yes: Boolean) {
        val plug = if (yes) BatteryManager.BATTERY_PLUGGED_AC else 0
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plug))
    }

    private fun batterySaver(yes: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(yes)

    private suspend fun run(updater: ForegroundUpdater) =
        TestListenableWorkerBuilder<PodcastWorker>(app).setForegroundUpdater(updater).build().doWork()

    /**
     * A kill leaves no log of its own: the run simply stops mid-line. Android's record is read
     * back once per process, not once per run, so it doesn't crowd out the run that follows.
     */
    @Test
    fun aProcessThatEndedBadlyIsNotedOnceNotEveryRun() = runTest {
        unrestricted(true)
        processEnded(ApplicationExitInfo.REASON_LOW_MEMORY, rss = 1_400_000, pss = 1_200_000)
        val before = noted("out of memory")

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertEquals(before + 1, noted("out of memory"))
        assertTrue("the log should say how big it had got", noted("1367 MB resident") >= 1)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertEquals("the same death shouldn't be logged twice", before + 1, noted("out of memory"))
    }

    @Test
    fun aProcessTheReaderClosedIsNotWorthALine() = runTest {
        unrestricted(true)
        processEnded(ApplicationExitInfo.REASON_USER_REQUESTED)
        val before = noted("ended:")

        assertTrue(run(allowed) is ListenableWorker.Result.Success)
        assertEquals(before, noted("ended:"))
    }

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
        assertEquals("not charging, or holding its charge", PodcastWorker.stopReasonName(WorkInfo.STOP_REASON_CONSTRAINT_CHARGING))
        assertEquals("device state, heat most likely", PodcastWorker.stopReasonName(WorkInfo.STOP_REASON_DEVICE_STATE))
        assertEquals("unnamed", PodcastWorker.stopReasonName(-99))
    }

    /**
     * In the foreground, WorkManager checks a charging constraint in the app and counts a phone
     * holding its charge at 80% as unplugged: the run was stopped as it started, all night.
     */
    @Test
    fun theMakerWaitsForTheChargerWithoutCarryingTheConstraintIntoTheForeground() {
        PodcastWorker.enqueue(app)

        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork("podcast").get()
        val wait = work.single { WaitForCharger::class.java.name in it.tags }
        val maker = work.single { PodcastWorker::class.java.name in it.tags }
        assertTrue(wait.constraints.requiresCharging())
        assertTrue(wait.constraints.requiresBatteryNotLow())
        assertFalse(maker.constraints.requiresCharging())
        assertEquals(WorkInfo.State.BLOCKED, maker.state)
    }

    @Test
    fun runAgainUnpluggedItDoesntStartAndWaitsForTheCharger() = runTest {
        unrestricted(true)
        plugged(false)

        assertTrue(run(allowed) is ListenableWorker.Result.Success)

        assertNull(shown)
        assertEquals(1, noted("unplugged; waiting for the charger"))
        val queued = WorkManager.getInstance(app).getWorkInfosForUniqueWork("podcast").get()
        assertTrue(queued.single { it.state == WorkInfo.State.ENQUEUED }.constraints.requiresCharging())
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
