package com.app.newspaperss.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.app.newspaperss.R
import com.app.newspaperss.notify.Notifier
import com.app.newspaperss.unknownAppsSettings

/** The live build's one notification: a newer build that needs the reader's tap. */
internal class UpdateNotices(private val context: Context) {
    /** Android wants the reader to confirm; [confirm] is its screen for that. */
    fun confirm(build: Int, confirm: Intent) =
        post("Build $build is ready", "Tap to install.", confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun allowInstalls(build: Int) =
        post("Build $build is ready", "Tap to let NewspapeRSS install it.", unknownAppsSettings(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun cancel() = NotificationManagerCompat.from(context).cancel(Notifier.TEST_BUILD_ID)

    private fun post(title: String, text: String, tap: Intent) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // Made here rather than with the app's others, so a release build has no such channel.
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Test builds", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A newer test build is ready to install"
            },
        )
        val action = PendingIntent.getActivity(context, 0, tap, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationManagerCompat.from(context).notify(
            Notifier.TEST_BUILD_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(action)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL = "test-builds"
    }
}
