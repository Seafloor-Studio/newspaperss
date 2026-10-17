package com.app.newspaperss

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Where the live (debug) build is with installing a newer build of itself. */
sealed interface SelfUpdate {
    /** Nothing under way: Today falls back to [DebugUpdates.newer]'s download link. */
    data object None : SelfUpdate

    /** @param waitingForWifi it waits for an unmetered network: the APK is about 50 MB. */
    data class Downloading(val build: Int, val waitingForWifi: Boolean) : SelfUpdate

    /** Downloaded and checked; it installs once nothing is running, or now if tapped. */
    data class Ready(val build: Int) : SelfUpdate

    /** Downloaded, but Android's "Install unknown apps" is off for this app. */
    data class NeedsPermission(val build: Int) : SelfUpdate
}

/** What tapping Install on Today did. */
sealed interface InstallTap {
    /** Android took the APK; it may still ask the reader to confirm. */
    data object Started : InstallTap

    /** Something is running that installing would kill; it installs when that's done. */
    data class Waiting(val reason: String) : InstallTap

    /** The download is gone or failed its checks: the browser link is the way left. */
    data object Unavailable : InstallTap
}

/**
 * The live build installing newer CI builds of itself. The debug source set fills it in; release
 * builds get [NONE], and carry neither the code nor the install permission (Play and F-Droid both
 * refuse self-updating apps).
 */
interface SelfUpdater {
    val state: Flow<SelfUpdate>

    /** At app start: the background check, and clearing up after an update that just installed. */
    fun start()

    /** [DebugUpdates] knows of [build], newer than this one. */
    fun newerBuild(build: Int)

    /** The app came to the front: the reader may have just allowed installing apps. */
    fun refresh()

    suspend fun install(): InstallTap

    companion object {
        val NONE = object : SelfUpdater {
            override val state: Flow<SelfUpdate> = flowOf(SelfUpdate.None)
            override fun start() {}
            override fun newerBuild(build: Int) {}
            override fun refresh() {}
            override suspend fun install(): InstallTap = InstallTap.Unavailable
        }
    }
}

/** Android's "Install unknown apps" switch for this app. Opening it needs no permission. */
fun unknownAppsSettings(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
