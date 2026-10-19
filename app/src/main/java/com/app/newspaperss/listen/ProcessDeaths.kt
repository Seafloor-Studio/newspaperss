package com.app.newspaperss.listen

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * What Android says ended the app's last process.
 *
 * A run that is killed leaves [PodcastStore.saying]'s marker behind and the line that was in
 * flight takes the blame, whatever actually happened: the marker only records which line it was.
 * That is fair when Kokoro's native code took the app down and wrong when Android took the app for
 * memory, where two unlucky kills would hand a page to the phone's voice for good. Android keeps
 * the answer, so nothing here has to guess at it.
 */
data class LastExit(
    val reason: Int,
    /** Android's name for it. */
    val name: String,
    val rssKb: Long,
    val pssKb: Long,
    val atMs: Long,
    /** Whether the app took itself down, as against Android taking it. */
    val crash: Boolean,
    /** Worth a line in the log; a process that ended normally isn't. */
    val abnormal: Boolean,
) {
    val description: String get() = "$name, ${rssKb / 1024} MB resident, ${pssKb / 1024} MB proportional"
}

object ProcessDeaths {
    /** Null below Android 11, which is where Android started keeping the record, or if it has none. */
    fun last(context: Context): LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { lastSinceR(context) }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lastSinceR(context: Context): LastExit? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return LastExit(
            reason = info.reason,
            name = name(info.reason, info.status),
            rssKb = info.rss,
            pssKb = info.pss,
            atMs = info.timestamp,
            crash = crash(info.reason, info.status),
            abnormal = abnormal(info.reason),
        )
    }

    /**
     * Whether the app fell over, rather than Android ending it. A signal is both: SIGKILL is
     * Android stopping the process, and the rest are the app's own native code failing.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun crash(reason: Int, status: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> true
        ApplicationExitInfo.REASON_SIGNALED -> status != SIGKILL
        else -> false
    }

    /** Ends that say nothing: the reader swiped the app away, or it was updated. */
    @RequiresApi(Build.VERSION_CODES.R)
    internal fun abnormal(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF,
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_PACKAGE_UPDATED,
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        ApplicationExitInfo.REASON_PERMISSION_CHANGE,
        -> false
        else -> true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun name(reason: Int, status: Int) = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "something it depended on died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "using too much of the phone"
        ApplicationExitInfo.REASON_EXIT_SELF -> "ended itself"
        ApplicationExitInfo.REASON_FREEZER -> "frozen"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "couldn't start"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "the phone was out of memory"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "the app was changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app was updated"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> if (status == SIGKILL) "stopped by Android (SIGKILL)" else "signal $status"
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "the reader closed it"
        ApplicationExitInfo.REASON_USER_STOPPED -> "the reader stopped it"
        else -> "reason $reason"
    }

    /** Android stopping a process, not the app falling over: [ApplicationExitInfo.getStatus] carries the signal. */
    private const val SIGKILL = 9
}
