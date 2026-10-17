package com.app.newspaperss.update

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.InstallTap
import com.app.newspaperss.SelfUpdate
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.work.EditionWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class LiveUpdatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val server = MockWebServer().apply { start() }
    private val prefs = app.getSharedPreferences("live-updates-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val work get() = WorkManager.getInstance(app)
    private lateinit var dir: File

    private val ourKey = setOf("our-key")
    private val installed = ApkFacts(app.packageName, 4552, "0.1.0-debug.455+abc1234", ourKey)

    /** What each served file, by its text, says about itself; anything else isn't an APK. */
    private val apks = mutableMapOf<String, ApkFacts>()
    private fun apk(build: Int, packageName: String = app.packageName, signers: Set<String> = ourKey) =
        ApkFacts(packageName, build * 10L + 2, "0.1.0-debug.$build+0000000", signers)

    /** Builds handed to Android, with the text of the file each was handed. */
    private val commits = mutableListOf<Pair<Int, String>>()
    private var commitFails = false
    /** What happens while the APK is copied into Android's session. */
    private var whileCopying: () -> Unit = {}
    private var canInstall = true
    private var foreground = false
    private var unmetered = true
    private var listenPlaying = false

    @Before fun setUp() {
        // A real thread, not SynchronousExecutor: one test holds an edition build running.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(Executors.newSingleThreadExecutor()).build())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        dir = tmp.newFolder("live-update")
        HoldingWorker.gate = CompletableDeferred()
    }

    @After fun stop() {
        HoldingWorker.gate.complete(Unit)
        server.close()
    }

    private fun updates(installedBuild: Int = 455) = LiveUpdates(
        app, installedBuild, CoroutineScope(Dispatchers.Unconfined), listenPlaying = { listenPlaying },
        prefs = prefs, dir = dir,
        download = ApkDownload(url = { server.url("/newspapeRSS-debug.apk").toString() }),
        inspect = { apks[it.readText()] },
        installed = { installed },
        commit = { file, build, stillIdle ->
            if (commitFails) throw java.io.IOException("no space for a session")
            whileCopying()
            if (stillIdle()) commits += build to file.readText()
        },
        canInstall = { canInstall },
        foreground = { foreground },
        unmetered = { unmetered },
    )

    private fun serve(text: String) = server.enqueue(MockResponse.Builder().body(text).build())

    private fun downloads() = work.getWorkInfosForUniqueWork(LiveUpdates.DOWNLOAD).get()

    private fun shown() = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    /** A build downloaded, checked and ready to install. */
    private fun ready(updates: LiveUpdates, build: Int = 464) = runBlocking {
        apks["apk-$build"] = apk(build)
        serve("apk-$build")
        assertEquals(LiveUpdates.Outcome.Done, updates.download(build))
    }

    @Test
    fun aNewerBuildIsDownloadedOnlyOnAnUnmeteredNetwork() {
        updates().newerBuild(464)

        val download = downloads().single()
        assertEquals("about 50 MB: never on mobile data", NetworkType.UNMETERED, download.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, download.state)
        assertTrue(LiveUpdates.BUILD_TAG + 464 in download.tags)
    }

    @Test
    fun theDownloadedBuildIsReadyAndShownSo() = runBlocking {
        val updates = updates()
        unmetered = false
        updates.newerBuild(464)
        assertEquals(SelfUpdate.Downloading(464, waitingForWifi = true), updates.state.first())

        ready(updates)

        assertEquals(SelfUpdate.Ready(464), updates.state.first())
        assertEquals(listOf("build-464.apk"), dir.list()!!.toList())
        canInstall = false
        updates.refresh()
        assertEquals(SelfUpdate.NeedsPermission(464), updates.state.first())
    }

    @Test
    fun aMismatchedApkIsDeletedAndThatBuildIsNeverFetchedAgain() = runBlocking {
        val updates = updates()
        for ((text, facts) in listOf(
            "another key" to apk(464, signers = setOf("someone-else")),
            "another app" to apk(464, packageName = "com.example.other"),
        )) {
            apks[text] = facts
            serve(text)
            assertEquals(text, LiveUpdates.Outcome.Failed, updates.download(464))
            assertEquals("$text: nothing kept", emptyList<String>(), dir.list()!!.toList())
            prefs.edit().remove(LiveUpdates.REJECTED).commit()
        }
        serve("not an APK at all")
        assertEquals(LiveUpdates.Outcome.Failed, updates.download(464))
        assertTrue(commits.isEmpty())

        updates.newerBuild(464)
        assertTrue("a refused build isn't downloaded again", downloads().isEmpty())
        updates.newerBuild(465)
        assertEquals("a newer one is", 1, downloads().size)
    }

    @Test
    fun aFailedDownloadIsRetriedThenGivenUpForThatBuild() = runBlocking {
        val updates = updates()
        repeat(LiveUpdates.DOWNLOAD_TRIES - 1) {
            server.enqueue(MockResponse.Builder().code(503).build())
            assertEquals(LiveUpdates.Outcome.Retry, updates.download(464))
        }
        // Cut off: fewer bytes than the server promised.
        server.enqueue(MockResponse.Builder().body("apk").setHeader("Content-Length", 1000).build())
        assertEquals(LiveUpdates.Outcome.Failed, updates.download(464))
        assertEquals("no partial file left", emptyList<String>(), dir.list()!!.toList())

        updates.newerBuild(464)
        assertTrue(downloads().isEmpty())
    }

    /** WorkManager stops a download at 10 minutes or when Wi-Fi goes: on a slow link, every time. */
    @Test
    fun aDownloadStoppedEveryTimeIsGivenUpNotFetchedForever() = runBlocking {
        val updates = updates()
        apks["apk-464"] = apk(464)
        repeat(LiveUpdates.DOWNLOAD_TRIES) {
            server.enqueue(MockResponse.Builder().body("apk-464").bodyDelay(10, java.util.concurrent.TimeUnit.SECONDS).build())
            assertEquals(null, kotlinx.coroutines.withTimeoutOrNull(300) { updates.download(464) })
        }
        val asked = server.requestCount

        assertEquals(LiveUpdates.Outcome.Failed, updates.download(464))
        assertEquals("not fetched again", asked, server.requestCount)
        updates.newerBuild(464)
        assertTrue(downloads().isEmpty())
    }

    /** CI uploads the numbered file and the fixed-name one side by side; the latter can lag. */
    @Test
    fun aFileThatIsStillTheOldBuildIsTriedAgainNotRefused() = runBlocking {
        val updates = updates()
        apks["apk-455"] = apk(455)
        serve("apk-455")
        assertEquals(LiveUpdates.Outcome.Retry, updates.download(464))
        assertEquals(0, prefs.getInt(LiveUpdates.REJECTED, 0))

        ready(updates)
        assertEquals(SelfUpdate.Ready(464), updates.state.first())
    }

    @Test
    fun theReadyBuildInstallsWhenNothingIsRunning() = runBlocking {
        val updates = updates()
        ready(updates)

        assertEquals(LiveUpdates.Outcome.Started, updates.install(userAsked = false))
        assertEquals(listOf(464 to "apk-464"), commits)
    }

    @Test
    fun itWaitsWhileAnEditionIsBeingMade() = runBlocking {
        val updates = updates()
        ready(updates)
        work.enqueueUniqueWork(EditionWorker.UNIQUE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<HoldingWorker>().build())
        idleUntil { work.getWorkInfosForUniqueWork(EditionWorker.UNIQUE).get().single().state == WorkInfo.State.RUNNING }

        assertEquals(LiveUpdates.Outcome.Busy("It installs once the edition is made."), updates.install(userAsked = false))
        assertEquals("not even when tapped", InstallTap.Waiting("It installs once the edition is made."), updates.install())
        assertTrue(commits.isEmpty())

        HoldingWorker.gate.complete(Unit)
        idleUntil { work.getWorkInfosForUniqueWork(EditionWorker.UNIQUE).get().single().state.isFinished }
        assertEquals(LiveUpdates.Outcome.Started, updates.install(userAsked = false))
        assertEquals(1, commits.size)
    }

    @Test
    fun itWaitsWhileListenPlaysOrTheAppIsOpen() = runBlocking {
        val updates = updates()
        ready(updates)
        listenPlaying = true
        assertEquals(LiveUpdates.Outcome.Busy("It installs once Listen stops."), updates.install(userAsked = false))
        listenPlaying = false
        foreground = true
        assertTrue(updates.install(userAsked = false) is LiveUpdates.Outcome.Busy)
        assertTrue(commits.isEmpty())

        assertEquals("tapped, the open app is no reason to wait", InstallTap.Started, updates.install())
        assertEquals(1, commits.size)
    }

    @Test
    fun anEditionStartingWhileTheApkIsCopiedStillHoldsTheInstallBack() = runBlocking {
        val updates = updates()
        ready(updates)
        whileCopying = { listenPlaying = true }

        assertEquals(LiveUpdates.Outcome.Busy("It installs once Listen stops."), updates.install(userAsked = false))
        assertTrue(commits.isEmpty())
    }

    /** A worker stopped, or Today's screen closed, mid-install isn't Android refusing the build. */
    @Test
    fun anInstallStoppedPartWayIsNotCountedAsAFailure() = runBlocking {
        val updates = updates()
        ready(updates)
        whileCopying = { throw kotlinx.coroutines.CancellationException("stopped") }
        repeat(LiveUpdates.MAX_INSTALL_FAILURES) {
            runCatching { updates.install(userAsked = false) }
        }

        assertEquals(listOf("build-464.apk"), dir.list()!!.toList())
        assertEquals(SelfUpdate.Ready(464), updates.state.first())
    }

    @Test
    fun whatWouldBeCutOffIsNamed() {
        assertEquals("It installs once the audio is made.", LiveUpdates.busyReason(setOf("podcast"), listenPlaying = false, foreground = false))
        assertEquals("It installs once the natural voice has downloaded.", LiveUpdates.busyReason(setOf("kokoro"), listenPlaying = false, foreground = false))
        assertEquals(null, LiveUpdates.busyReason(setOf("edition-schedule", "sync"), listenPlaying = false, foreground = false))
    }

    @Test
    fun withoutInstallUnknownAppsTheReaderIsToldOnce() = runBlocking {
        val updates = updates()
        ready(updates)
        canInstall = false
        shadowOf(app.packageManager).addResolveInfoForIntent(
            com.app.newspaperss.unknownAppsSettings(app),
            android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = "com.android.settings"; name = "Settings" } },
        )

        assertEquals(LiveUpdates.Outcome.NeedsPermission, updates.install(userAsked = false))
        assertEquals(LiveUpdates.Outcome.NeedsPermission, updates.install(userAsked = false))
        assertEquals(1, shown().size)
        val intent = shadowOf(shown().single().contentIntent).savedIntent
        assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertTrue(commits.isEmpty())
    }

    /** E-reader firmware can lack Android's settings screens. */
    @Test
    fun withoutTheSettingsScreenTheNotificationOffersTheBrowser() = runBlocking {
        val updates = updates()
        ready(updates)
        canInstall = false
        updates.install(userAsked = false)

        assertEquals(Intent.ACTION_VIEW, shadowOf(shown().single().contentIntent).savedIntent.action)
    }

    @Test
    fun twoInstallsAtOnceHandAndroidOneSession() = runBlocking {
        val updates = updates()
        ready(updates)

        val outcomes = listOf(async(Dispatchers.IO) { updates.install(userAsked = false) }, async(Dispatchers.IO) { updates.install(userAsked = false) }).awaitAll()

        assertEquals(listOf(LiveUpdates.Outcome.Started, LiveUpdates.Outcome.Started), outcomes)
        assertEquals(1, commits.size)
    }

    @Test
    fun whenAndroidAsksTheReaderTheyGetANotificationAndArentAskedAgainUnlessTheyTap() = runBlocking {
        val updates = updates()
        ready(updates)
        updates.install(userAsked = false)
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")

        updates.installResult(464, PackageInstaller.STATUS_PENDING_USER_ACTION, null, confirm)

        val notice = shown().single()
        assertEquals("Build 464 is ready", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Tap to install.", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(confirm.action, shadowOf(notice.contentIntent).savedIntent.action)
        updates.install(userAsked = false)
        assertEquals("the background doesn't ask again", 1, commits.size)
        updates.install(userAsked = true)
        assertEquals(2, commits.size)
    }

    @Test
    fun aBuildThatKeepsFailingToInstallIsGivenUpNotFetchedAgain() = runBlocking {
        val updates = updates()
        ready(updates)
        repeat(LiveUpdates.MAX_INSTALL_FAILURES) {
            updates.install(userAsked = true)
            updates.installResult(464, PackageInstaller.STATUS_FAILURE_STORAGE, "no space", null)
        }

        assertEquals(LiveUpdates.MAX_INSTALL_FAILURES, commits.size)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals("Today falls back to the browser link", SelfUpdate.None, updates.state.first())
        updates.newerBuild(464)
        assertTrue("not downloaded again", downloads().isEmpty())
        assertEquals(InstallTap.Unavailable, updates.install())
    }

    @Test
    fun anApkAndroidCallsInvalidIsGivenUpAtOnce() = runBlocking {
        val updates = updates()
        ready(updates)
        updates.install(userAsked = false)
        updates.installResult(464, PackageInstaller.STATUS_FAILURE_INVALID, "bad", null)

        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(464, prefs.getInt(LiveUpdates.REJECTED, 0))
    }

    @Test
    fun aLateAnswerAboutAnOlderBuildLeavesTheNewerDownloadAlone() = runBlocking {
        val updates = updates()
        ready(updates, 464)
        updates.install(userAsked = false)
        ready(updates, 466)

        updates.installResult(464, PackageInstaller.STATUS_FAILURE_INVALID, "bad", null)
        updates.installResult(464, PackageInstaller.STATUS_SUCCESS, null, null)

        assertEquals(listOf("build-466.apk"), dir.list()!!.toList())
        assertEquals(SelfUpdate.Ready(466), updates.state.first())
        assertEquals(0, prefs.getInt(LiveUpdates.REJECTED, 0))
    }

    @Test
    fun aSessionAndroidRefusesCountsAsAFailure() = runBlocking {
        val updates = updates()
        ready(updates)
        commitFails = true
        repeat(LiveUpdates.MAX_INSTALL_FAILURES - 1) { assertEquals(LiveUpdates.Outcome.Retry, updates.install(userAsked = false)) }
        assertEquals(LiveUpdates.Outcome.Failed, updates.install(userAsked = false))
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aFileChangedInTheCacheIsCheckedAgainAndRefused() = runBlocking {
        val updates = updates()
        ready(updates)
        File(dir, "build-464.apk").writeText("swapped")

        assertEquals(LiveUpdates.Outcome.Gone, updates.install(userAsked = false))
        assertTrue(commits.isEmpty())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun afterTheUpdateItsFileAndNotificationAreClearedAndTheCheckKeepsRunning() = runBlocking {
        ready(updates())
        updates().installResult(464, PackageInstaller.STATUS_PENDING_USER_ACTION, null, Intent("confirm"))
        assertEquals(1, shown().size)

        // The process the update started: build 464 is the one running now.
        val after = updates(installedBuild = 464)
        after.start()

        // The notification goes last, after the file and the state.
        idleUntil { shown().isEmpty() }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(SelfUpdate.None, after.state.first())
        val check = work.getWorkInfosForUniqueWork(LiveUpdates.CHECK).get().single()
        assertFalse(check.state.isFinished)
        assertEquals(NetworkType.CONNECTED, check.constraints.requiredNetworkType)
        // The install work, rerun after the process died under it, has nothing left to do.
        assertEquals(LiveUpdates.Outcome.Gone, after.install(userAsked = false))
    }
}

/** Stands in for a long-running edition build, until [gate] opens. */
class HoldingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        gate.await()
        return Result.success()
    }

    companion object {
        var gate = CompletableDeferred<Unit>()
    }
}
