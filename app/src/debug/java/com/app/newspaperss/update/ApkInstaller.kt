package com.app.newspaperss.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import java.io.File

/** Hands a checked APK to Android's [PackageInstaller]; the answer comes to [InstallResultReceiver]. */
internal class ApkInstaller(private val context: Context) {
    fun commit(apk: File, build: Int) {
        val installer = context.packageManager.packageInstaller
        // A session from a process that died before committing would otherwise keep its copy of
        // the APK for days.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Without asking, once this app installed the one it replaces: Android asks the first
            // time after a browser install, and always before Android 12.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(InstallResultReceiver.sender(context, build, id))
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }
}

/** Android's answer to a commit: installed, needs the reader, or failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = LiveUpdates.of(context) ?: return
        updates.installResult(
            build = intent.getIntExtra(EXTRA_BUILD, 0),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java),
        )
    }

    companion object {
        const val EXTRA_BUILD = "com.app.newspaperss.BUILD"

        // Mutable: Android adds the status to it. Explicit, so nothing else can receive it.
        fun sender(context: Context, build: Int, sessionId: Int): IntentSender = PendingIntent.getBroadcast(
            context, sessionId,
            Intent(context, InstallResultReceiver::class.java).putExtra(EXTRA_BUILD, build),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        ).intentSender
    }
}
