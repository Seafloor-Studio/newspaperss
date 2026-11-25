package com.app.newspaperss.work

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.foreground.SystemForegroundService
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.listen.EncoderLost
import com.app.newspaperss.listen.PodcastMaker
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
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

    private fun unplugged() =
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0))

    private fun batterySaver(yes: Boolean) =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(yes)

    private suspend fun run(updater: ForegroundUpdater) =
        TestListenableWorkerBuilder<PodcastWorker>(app).setForegroundUpdater(updater).build().doWork()

    /** A worker whose making fails as [failure] says, as the maker's would. */
    private suspend fun runFailing(failure: Exception) = runMaking { throw failure }

    /** A worker whose making is [making], given the seconds-left the worker hands the maker. */
    private suspend fun runMaking(updater: ForegroundUpdater = allowed, making: (secondsLeft: () -> Double?) -> Boolean): ListenableWorker.Result {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                object : PodcastWorker(appContext, workerParameters) {
                    override suspend fun make(secondsLeft: () -> Double?) = making(secondsLeft)
                }
        }
        return TestListenableWorkerBuilder<PodcastWorker>(app).setForegroundUpdater(updater).setWorkerFactory(factory).build().doWork()
    }

    /** Android's 10 minutes start with the work: time spent getting the foreground counts. */
    @Test
    fun theTimeLeftCountsFromTheStartOfTheWork() = runTest {
        unrestricted(true)
        val slow = ForegroundUpdater { _: Context, _: UUID, info: ForegroundInfo ->
            shown = info
            ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
            Futures.immediateFuture(null)
        }
        var given: Double? = null

        assertTrue(runMaking(slow) { left -> given = left(); false } is ListenableWorker.Result.Success)
        assertEquals(420.0, given!!, 1.0)
    }

    /** In the foreground for most of a long run and then not, it says so: not "short of 9 min". */
    @Test
    fun aRunThatLosesTheForegroundLateSaysSo() = runTest {
        unrestricted(true)

        assertTrue(runMaking { ShadowSystemClock.advanceBy(Duration.ofMinutes(40)); true } is ListenableWorker.Result.Success)

        assertEquals(1, noted("Stopped: out of the foreground 40 min into the run; not asking again for 12 hours"))
        assertEquals(0, noted("Stopped short of"))
        assertEquals(1, queued())
    }

    /**
     * Not really in the foreground, the maker is told how long it has before the 9-minute stop,
     * and stopping short of it is a stop like the guard's: queued to go on, and Android's refusal
     * noted as the guard notes it.
     */
    @Test
    fun aBackgroundRunThatStopsItselfShortOfTheLimitGoesOnLater() = runTest {
        unrestricted(true)
        var given: Double? = null

        assertTrue(runMaking { left -> given = left(); true } is ListenableWorker.Result.Success)

        assertEquals(540.0, given!!, 1.0)
        assertEquals(1, queued())
        assertEquals(1, noted("Stopped short of 9 min, not in the foreground; not asking again for 12 hours"))
        assertEquals(0, noted("Run ended"))
        assertTrue(PodcastWorker.holdOffUntil > 0)
    }

    @Test
    fun aRunThatFinishesIsntQueuedAgain() = runTest {
        unrestricted(true)
        assertTrue(runMaking { false } is ListenableWorker.Result.Success)
        assertEquals(0, queued())
        assertEquals(1, noted("Run ended"))
    }

    /**
     * Overnight, nothing else asks for another run: a page that failed once would wait for the
     * reader to open the app, and the podcast wouldn't be ready in the morning.
     */
    @Test
    fun aRunThatFailsOnAPageRunsAgainAfterAWhile() = runTest {
        unrestricted(true)

        assertTrue(runFailing(PodcastMaker.PageFailed(IllegalStateException("codec"))) is ListenableWorker.Result.Success)

        val again = WorkManager.getInstance(app).getWorkInfosForUniqueWork("podcast").get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, again.size)
        assertEquals(5 * 60_000L, again.single().initialDelayMillis)
        assertEquals(1, noted("Run failed: java.lang.IllegalStateException; trying again in 5 min"))
    }

    /** A lost encoder isn't the page's fault: the run goes on a few minutes later, as after a failure. */
    @Test
    fun aRunThatLosesTheEncoderRunsAgainAfterAWhile() = runTest {
        unrestricted(true)

        assertTrue(runFailing(EncoderLost("reclaimed", IllegalStateException())) is ListenableWorker.Result.Success)

        val again = WorkManager.getInstance(app).getWorkInfosForUniqueWork("podcast").get().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(5 * 60_000L, again.single().initialDelayMillis)
        assertEquals(1, noted("Run stopped: the encoder was lost; trying again in 5 min"))
    }

    private fun workManagerService(foreground: Boolean?) = shadowOf(app.getSystemService(ActivityManager::class.java)).setServices(
        listOfNotNull(
            foreground?.let {
                ActivityManager.RunningServiceInfo().apply {
                    service = ComponentName(app, SystemForegroundService::class.java)
                    this.foreground = it
                }
            },
        ),
    )

    /**
     * Android's daily foreground time ran out after an hour of runs: WorkManager's service left in
     * the foreground after a run would explain it. Checked on a while after the run, not as it
     * ends, when WorkManager hasn't yet let it go.
     */
    @Test
    fun aServiceStillInTheForegroundAfterTheRunIsNoted() = runTest {
        unrestricted(true)
        assertTrue(runMaking { false } is ListenableWorker.Result.Success)
        workManagerService(foreground = true)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PodcastWorker.SERVICE_CHECK_MS - 1_000))
        assertEquals(0, noted("WorkManager's service"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, noted("90s after the run, WorkManager's service still in the foreground after the run ended"))
    }

    @Test
    fun aServiceLetGoAfterTheRunIsNotedToo() = runTest {
        unrestricted(true)
        assertTrue(runMaking { false } is ListenableWorker.Result.Success)
        workManagerService(foreground = null)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PodcastWorker.SERVICE_CHECK_MS + 1_000))
        assertEquals(1, noted("90s after the run, WorkManager's service has stopped"))
    }

    /** During another run the service is that run's: nothing to say about the last one. */
    @Test
    fun theServiceIsntCheckedOnDuringAnotherRun() = runTest {
        unrestricted(true)
        assertTrue(runMaking { false } is ListenableWorker.Result.Success)
        workManagerService(foreground = true)

        assertTrue(
            runMaking {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PodcastWorker.SERVICE_CHECK_MS + 1_000))
                false
            } is ListenableWorker.Result.Success,
        )
        assertEquals(0, noted("after the run, WorkManager's service"))
        assertEquals("noted as the second run started", 1, noted("WorkManager's service already in the foreground as this run starts"))
    }

    /** Kokoro not loading would fail every run: it waits to be asked again rather than loop all night. */
    @Test
    fun aRunThatFailsOtherwiseWaitsToBeAskedAgain() = runTest {
        unrestricted(true)

        assertTrue(runFailing(IllegalStateException("model")) is ListenableWorker.Result.Failure)

        assertEquals(0, queued())
        assertEquals(1, noted("Run failed: java.lang.IllegalStateException"))
    }

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

    /**
     * When, so a death can be set against the runs logged around it, and in Android's words, which
     * say what a "not responding" was waiting on. The log is shared: an address's sign-in and
     * query, where a key would be, are cut.
     */
    @Test
    fun aDeathSaysWhenAndWhatAndroidSaid() = runTest {
        val zone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            unrestricted(true)
            val info = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setProcessName(app.packageName)
                .setReason(ApplicationExitInfo.REASON_ANR)
                .setTimestamp(1_760_000_000_000L)
                .setDescription("Input dispatching timed out\n(waiting on https://reader:hunter2@example.com/feed?token=s3cret)")
                .build()
            shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(info)

            assertTrue(run(allowed) is ListenableWorker.Result.Success)

            val line = app.container.podcastLog.read().single { it.contains("The process before this one ended") }
            assertTrue(line, line.contains("not responding at 9 Oct 08:53, "))
            assertTrue(line, line.endsWith("; Android says: Input dispatching timed out (waiting on https://example.com/feed"))
            assertFalse(line, line.contains("hunter2") || line.contains("s3cret"))
        } finally {
            java.util.TimeZone.setDefault(zone)
        }
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

    @Test
    fun theBatteryIsSaidAsAndroidReportsIt() {
        fun battery(plugged: Int, status: Int, level: Int) = app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, plugged).putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_LEVEL, level).putExtra(BatteryManager.EXTRA_SCALE, 100),
        )
        battery(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_STATUS_CHARGING, 64)
        assertEquals("plugged in, charging, 64%", PodcastWorker.batteryNow(app))
        battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_NOT_CHARGING, 80)
        assertEquals("held at its limit, it's plugged in but not charging", "plugged in, not charging, 80%", PodcastWorker.batteryNow(app))
        battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING, 41)
        assertEquals("on battery, 41%", PodcastWorker.batteryNow(app))
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

    /** What a phone that updated with a podcast still waiting has queued: it would loop all night. */
    @Test
    fun aRequestQueuedByAnOlderVersionLosesItsChargingConstraint() = runTest {
        val work = WorkManager.getInstance(app)
        val old = OneTimeWorkRequestBuilder<PodcastWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .build()
        work.enqueueUniqueWork("podcast", ExistingWorkPolicy.APPEND_OR_REPLACE, old).result.get()
        assertEquals("held for the charger", WorkInfo.State.ENQUEUED, work.getWorkInfoById(old.id).get()!!.state)

        PodcastWorker.dropChargingConstraint(app)

        assertFalse(work.getWorkInfoById(old.id).get()!!.constraints.requiresCharging())
        // Free of the charger, the test WorkManager runs it at once, and it finishes on another
        // thread: left running, it writes to a database tearDown has closed, failing the next test.
        idleUntil { work.getWorkInfoById(old.id).get()!!.state.isFinished }
    }

    /** Held at 80%, Android says "not charging": still plugged in, and the run goes on. */
    @Test
    fun aPhoneHoldingItsChargeStillMakesTheAudio() = runTest {
        unrestricted(true)
        app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC)
                .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_NOT_CHARGING),
        )

        assertTrue(run(allowed) is ListenableWorker.Result.Success)

        assertTrue(shown != null)
        assertEquals(0, noted("unplugged"))
    }

    @Test
    fun runAgainUnpluggedItDoesntStartAndWaitsForTheCharger() = runTest {
        unrestricted(true)
        unplugged()

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
