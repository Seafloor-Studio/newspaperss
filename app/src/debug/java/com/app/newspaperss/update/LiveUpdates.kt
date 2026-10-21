package com.app.newspaperss.update

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.app.newspaperss.AwaitedAnswers
import com.app.newspaperss.DebugUpdates
import com.app.newspaperss.InstallTap
import com.app.newspaperss.SelfUpdate
import com.app.newspaperss.SelfUpdater
import com.app.newspaperss.container
import com.app.newspaperss.work.EditionWorker
import com.app.newspaperss.work.KokoroWorker
import com.app.newspaperss.work.MoveFeedsWorker
import com.app.newspaperss.work.PodcastWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The live build keeping itself current: a newer CI build is downloaded on Wi-Fi, checked, and
 * installed once nothing is running that installing would kill.
 *
 * What it knows lives in [prefs], so a process that dies mid-download or mid-install picks up
 * where it was. A build that fails its checks, can't be downloaded, or keeps failing to install
 * is given up on ([REJECTED]): only a newer one is fetched again, so 50 MB isn't downloaded over
 * and over. Today then falls back to the browser link.
 */
internal class LiveUpdates(
    private val context: Context,
    private val installedBuild: Int,
    private val scope: CoroutineScope,
    private val listenPlaying: () -> Boolean,
    private val prefs: SharedPreferences = context.getSharedPreferences("live-updates", Context.MODE_PRIVATE),
    private val dir: File = File(context.cacheDir, "live-update"),
    private val download: ApkDownload = ApkDownload(url = { DebugUpdates.downloadFor(Build.SUPPORTED_ABIS) }),
    private val inspect: (File) -> ApkFacts? = { context.packageManager.apkFacts(it) },
    private val installed: () -> ApkFacts = { context.packageManager.installedFacts(context.packageName) },
    private val commit: suspend (File, Int, suspend () -> Boolean) -> Unit = ApkInstaller(context)::commit,
    private val canInstall: () -> Boolean = { context.packageManager.canRequestPackageInstalls() },
    private val foreground: () -> Boolean = ::inForeground,
    /** A picker is out: the app is paused, not left, and its answer would be lost with it. */
    private val awaiting: () -> Boolean = { AwaitedAnswers(context).any },
    private val unmetered: () -> Boolean = { unmeteredNow(context) },
    private val notices: UpdateNotices = UpdateNotices(context),
    private val now: () -> Long = System::currentTimeMillis,
) : SelfUpdater {
    private val work by lazy { WorkManager.getInstance(context) }
    private val changed = MutableStateFlow(0)
    // A tap on Today and the install work can arrive together: one session at a time.
    private val installing = Mutex()

    override val state: Flow<SelfUpdate> by lazy {
        combine(work.getWorkInfosForUniqueWorkFlow(DOWNLOAD), changed) { infos, _ -> stateOf(infos.lastOrNull()) }
            .flowOn(Dispatchers.IO)
            .distinctUntilChanged()
    }

    override fun start() {
        scope.launch(Dispatchers.IO) {
            // The update that brought this build in: its file and notification have done their job.
            if (prefs.getInt(READY, 0) in 1..installedBuild) clearUp()
        }
        LiveCheckWorker.schedule(work)
    }

    override fun newerBuild(build: Int) {
        if (build <= installedBuild || build <= prefs.getInt(REJECTED, 0)) return
        val ready = readyBuild()
        if (ready != null && ready >= build) {
            LiveInstallWorker.enqueue(work)
        } else {
            // Kept, not replaced: one already under way fetches the newest build anyway.
            LiveDownloadWorker.enqueue(work, build)
        }
    }

    override fun refresh() {
        changed.value++
    }

    override suspend fun install(): InstallTap = when (val outcome = install(userAsked = true)) {
        Outcome.Started, Outcome.Done -> InstallTap.Started
        is Outcome.Busy -> {
            LiveInstallWorker.enqueue(work)
            InstallTap.Waiting(outcome.reason)
        }
        Outcome.NeedsPermission -> InstallTap.Waiting("Allow NewspapeRSS to install apps first.")
        Outcome.Gone, Outcome.Retry, Outcome.Failed -> InstallTap.Unavailable
    }

    /** Fetches and checks the newest build, which [target] is or something newer. */
    /**
     * Fetches and checks the newest build, which [target] is or something newer. Every run counts
     * as a try, whether it fails, is stopped by WorkManager (Wi-Fi lost, its 10-minute limit) or
     * dies with the process: after [DOWNLOAD_TRIES] the build is given up on.
     */
    suspend fun download(target: Int): Outcome = withContext(Dispatchers.IO) {
        if (target <= installedBuild || target <= prefs.getInt(REJECTED, 0)) return@withContext Outcome.Done
        val ready = readyBuild()
        if (ready != null && ready >= target) {
            LiveInstallWorker.enqueue(work)
            return@withContext Outcome.Done
        }
        val tries = (if (prefs.getInt(DOWNLOADING, 0) == target) prefs.getInt(TRIES, 0) else 0) + 1
        prefs.edit().putInt(DOWNLOADING, target).putInt(TRIES, tries).commit()
        if (tries > DOWNLOAD_TRIES) {
            log("Build $target: given up after $DOWNLOAD_TRIES tries")
            reject(target)
            return@withContext Outcome.Failed
        }
        // Try again later, or give up on the build after the last try.
        fun again() = if (tries < DOWNLOAD_TRIES) Outcome.Retry else Outcome.Failed.also { reject(target) }
        dir.mkdirs()
        val part = File(dir, "download.part")
        try {
            download.fetch(part)
        } catch (e: IOException) {
            log("Build $target didn't download: ${e.message}")
            return@withContext again()
        }
        val facts = inspect(part)
        when (val verdict = verdict(facts, installed())) {
            is Verdict.Bad -> {
                part.delete()
                log("Build $target was refused: ${verdict.why}")
                // The target, not the number the file claims: a file that failed its checks can't
                // be trusted to say which build it is.
                reject(target)
                return@withContext Outcome.Failed
            }
            // CI uploads the numbered file and the fixed-name one side by side, so the latter can
            // lag behind for a while.
            is Verdict.Stale -> {
                part.delete()
                log("Build $target: the file is still an older build (${verdict.why})")
                return@withContext again()
            }
            Verdict.Good -> {}
        }
        val build = facts?.build ?: target
        if (ready != null && build <= ready) {
            part.delete()
            return@withContext again()
        }
        // Not while an install is deciding on the file this replaces.
        installing.withLock {
            val apk = apkOf(build)
            if (!part.renameTo(apk)) {
                part.delete()
                return@withContext Outcome.Retry
            }
            dir.listFiles()?.filter { it != apk }?.forEach { it.delete() }
            prefs.edit().putInt(READY, build).remove(FAILURES).remove(ASKED).remove(ASKED_PERMISSION).remove(COMMITTED)
                .remove(DOWNLOADING).remove(TRIES).commit()
        }
        changed.value++
        LiveInstallWorker.enqueue(work)
        Outcome.Done
    }

    /**
     * Installs the ready build if nothing is running that installing would kill.
     *
     * @param userAsked tapped on Today: the app being open doesn't count as busy, and Android
     *   is asked again even if the reader turned it down before.
     */
    suspend fun install(userAsked: Boolean): Outcome = installing.withLock {
        withContext(Dispatchers.IO) {
            val ready = prefs.getInt(READY, 0)
            if (ready == 0) return@withContext Outcome.Gone
            if (ready <= installedBuild) {
                clearUp()
                return@withContext Outcome.Done
            }
            val apk = apkOf(ready)
            if (!apk.isFile) {
                // The system clears caches when space runs low: fetched again on the next check.
                forgetReady()
                return@withContext Outcome.Gone
            }
            // Checked again: a file in the cache could have been changed since it was fetched.
            verdict(inspect(apk), installed()).takeIf { it != Verdict.Good }?.let {
                log("Build $ready was refused before installing: $it")
                giveUp(ready)
                return@withContext Outcome.Gone
            }
            busyReason(runningWork(), listenPlaying(), foreground = !userAsked && (foreground() || awaiting()))?.let {
                return@withContext Outcome.Busy(it)
            }
            if (!canInstall()) {
                if (prefs.getInt(ASKED_PERMISSION, 0) != ready) {
                    notices.allowInstalls(ready)
                    prefs.edit().putInt(ASKED_PERMISSION, ready).commit()
                }
                changed.value++
                return@withContext Outcome.NeedsPermission
            }
            // The reader has the notification, or said no: not asked again unless they tap.
            if (!userAsked && prefs.getInt(ASKED, 0) == ready) return@withContext Outcome.Started
            // Committed a moment ago and its answer not yet in.
            if (prefs.getInt(COMMITTED, 0) == ready && now() - prefs.getLong(COMMITTED_AT, 0L) in 0 until COMMIT_PENDING_MS) {
                return@withContext Outcome.Started
            }
            var busy: String? = null
            try {
                // Asked again just before Android takes it: copying 50 MB into the session takes a
                // while, and a timed edition may have started meanwhile.
                commit(apk, ready) {
                    busy = busyReason(runningWork(), listenPlaying(), foreground = !userAsked && (foreground() || awaiting()))
                    busy == null
                }
            } catch (e: CancellationException) {
                // Stopped, not refused: WorkManager or a closed screen. Not counted toward giving up.
                throw e
            } catch (e: IOException) {
                // Usually no room for Android's copy: the phone's problem, not the build's, so not
                // counted toward giving up on it.
                log("Build $ready couldn't be copied to Android: ${e.message}")
                return@withContext Outcome.Retry
            } catch (e: Exception) {
                // SecurityException, or IllegalStateException for a session it closed under us.
                log("Build $ready couldn't be handed to Android: ${e.javaClass.simpleName} ${e.message}")
                return@withContext if (failed(ready)) Outcome.Failed else Outcome.Retry
            }
            busy?.let { return@withContext Outcome.Busy(it) }
            prefs.edit().putInt(COMMITTED, ready).putLong(COMMITTED_AT, now()).commit()
            Outcome.Started
        }
    }

    /** Android's answer to a commit, from [InstallResultReceiver]. */
    fun installResult(build: Int, status: Int, message: String?, confirm: Intent?) {
        // About a build a newer download has since replaced: what it says is no longer true of
        // the ready file. A success is tidied up when the new process starts.
        if (build != prefs.getInt(READY, 0)) {
            log("Build $build: status $status, but build ${prefs.getInt(READY, 0)} is ready now")
            if (status != PackageInstaller.STATUS_SUCCESS) LiveInstallWorker.enqueue(work)
            return
        }
        prefs.edit().remove(COMMITTED).commit()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                prefs.edit().putInt(ASKED, build).commit()
                if (confirm == null) return
                // Open, the reader just tapped Install; otherwise they're told, and Android asks
                // once they tap the notification.
                if (foreground()) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { notices.confirm(build, confirm) }
                } else {
                    notices.confirm(build, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> clearUp()
            // The reader said no, or closed Android's question: Today still offers it.
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                log("Build $build: the install was cancelled")
                prefs.edit().putInt(ASKED, build).commit()
            }
            // Wrong for this phone or this app: trying again would only fail again.
            PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                log("Build $build can't install (status $status): $message")
                giveUp(build)
            }
            // No room, or storage not mounted: the phone's, not the build's. Not counted.
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                log("Build $build: no room to install ($message)")
                LiveInstallWorker.enqueue(work, delayMinutes = RETRY_MINUTES)
            }
            // Blocked, a timeout or no reason: maybe not next time, but not forever.
            else -> {
                log("Build $build didn't install (status $status): $message")
                if (!failed(build)) LiveInstallWorker.enqueue(work, delayMinutes = RETRY_MINUTES)
            }
        }
        changed.value++
    }

    private fun stateOf(download: WorkInfo?): SelfUpdate {
        readyBuild()?.let { return if (canInstall()) SelfUpdate.Ready(it) else SelfUpdate.NeedsPermission(it) }
        if (download == null || download.state.isFinished) return SelfUpdate.None
        val build = download.tags.firstOrNull { it.startsWith(BUILD_TAG) }?.removePrefix(BUILD_TAG)?.toIntOrNull()
        if (build == null || build <= installedBuild) return SelfUpdate.None
        // Enqueued on Wi-Fi is a retry about to run, not a wait.
        return SelfUpdate.Downloading(build, waitingForWifi = download.state != WorkInfo.State.RUNNING && !unmetered())
    }

    private fun readyBuild(): Int? = prefs.getInt(READY, 0).takeIf { it > installedBuild && apkOf(it).isFile }

    private fun apkOf(build: Int) = File(dir, "build-$build.apk")

    private suspend fun runningWork(): Set<String> =
        BUSY_WORK.filterTo(mutableSetOf()) { name -> work.getWorkInfosForUniqueWorkFlow(name).first().any { it.state == WorkInfo.State.RUNNING } }

    /** Counts a failed install of [build]; true once it's given up on. */
    private fun failed(build: Int): Boolean {
        val failures = prefs.getInt(FAILURES, 0) + 1
        prefs.edit().putInt(FAILURES, failures).commit()
        if (failures < MAX_INSTALL_FAILURES) return false
        giveUp(build)
        return true
    }

    private fun giveUp(build: Int) {
        reject(build)
        clearUp()
    }

    private fun reject(build: Int) {
        if (build > prefs.getInt(REJECTED, 0)) prefs.edit().putInt(REJECTED, build).commit()
    }

    private fun forgetReady() {
        prefs.edit().remove(READY).remove(FAILURES).remove(ASKED).remove(ASKED_PERMISSION).remove(COMMITTED).commit()
        changed.value++
    }

    private fun clearUp() {
        dir.listFiles()?.forEach { it.delete() }
        forgetReady()
        notices.cancel()
    }

    private fun log(message: String) = Log.w("newspaperss", "Live update: $message")

    /** How a step went, for its worker or for Today. */
    sealed interface Outcome {
        /** Nothing more to do. */
        data object Done : Outcome
        /** Handed to Android, or waiting for the reader to answer it. */
        data object Started : Outcome
        /** Installing now would kill [reason]'s work. */
        data class Busy(val reason: String) : Outcome
        data object NeedsPermission : Outcome
        /** No checked file to install. */
        data object Gone : Outcome
        /** Try again later. */
        data object Retry : Outcome
        /** Given up on this build. */
        data object Failed : Outcome
    }

    companion object {
        const val DOWNLOAD = "live-update-download"
        const val INSTALL = "live-update-install"
        const val CHECK = "live-update-check"
        const val BUILD_TAG = "build:"

        /** Runs whose death would cost the reader something: a paper, a podcast, a 325 MB voice. */
        val BUSY_WORK = listOf(EditionWorker.UNIQUE, PodcastWorker.UNIQUE, KokoroWorker.UNIQUE, MoveFeedsWorker.UNIQUE)

        internal const val READY = "ready"
        internal const val REJECTED = "rejected"
        private const val FAILURES = "failures"
        private const val ASKED = "asked"
        private const val ASKED_PERMISSION = "askedPermission"
        private const val COMMITTED = "committed"
        private const val COMMITTED_AT = "committedAt"
        private const val DOWNLOADING = "downloading"
        private const val TRIES = "tries"
        /** With the backoff, about a quarter of an hour of a bad connection before giving up on the build. */
        const val DOWNLOAD_TRIES = 5
        const val MAX_INSTALL_FAILURES = 3
        const val RETRY_MINUTES = 30L
        private val COMMIT_PENDING_MS = TimeUnit.MINUTES.toMillis(10)

        fun of(context: Context): LiveUpdates? = context.container.selfUpdates as? LiveUpdates

        /** Why installing now would cut something off, as Today says it; null when nothing would. */
        fun busyReason(running: Set<String>, listenPlaying: Boolean, foreground: Boolean): String? = when {
            EditionWorker.UNIQUE in running -> "It installs once the edition is made."
            PodcastWorker.UNIQUE in running -> "It installs once the audio is made."
            KokoroWorker.UNIQUE in running -> "It installs once the natural voice has downloaded."
            MoveFeedsWorker.UNIQUE in running -> "It installs once your feeds have moved."
            listenPlaying -> "It installs once Listen stops."
            foreground -> "It installs once you leave the app."
            else -> null
        }

        private fun inForeground(): Boolean {
            val me = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(me)
            return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        private fun unmeteredNow(context: Context): Boolean {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        }
    }
}
