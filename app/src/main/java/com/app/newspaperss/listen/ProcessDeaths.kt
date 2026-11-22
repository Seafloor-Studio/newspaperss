package com.app.newspaperss.listen

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    /** Android's own words for it ([ApplicationExitInfo.getDescription]), made safe to share: see [ProcessDeaths.shareable]. */
    val androidSays: String? = null,
) {
    /** For the audio log, which the reader shares: when, in the phone's time, so it can be set against the runs logged. */
    val description: String get() {
        // English month names, as the rest of the log is English. Android has no names for the root
        // locale and writes "M11" for November, which the JVM tests can't see.
        val at = SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(Date(atMs))
        return "$name at $at, ${rssKb / 1024} MB resident, ${pssKb / 1024} MB proportional" + (androidSays?.let { "; Android says: $it" } ?: "")
    }
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
            androidSays = shareable(info.description),
        )
    }

    /**
     * Android's description, as the shared log may carry it: on one line, at most [SAYS_LENGTH]
     * characters, and with any address's sign-in and query cut, where a key would be. It's mostly
     * a few words ("excessive cpu usage"), but a not-responding one names what was waited on, and
     * nothing promises what else it holds.
     */
    internal fun shareable(said: String?): String? {
        val line = said?.replace(Regex("""\s+"""), " ")?.trim()?.let(::withoutKeys)
        if (line.isNullOrEmpty()) return null
        return if (line.length <= SAYS_LENGTH) line else line.take(SAYS_LENGTH - 1) + "…"
    }

    private const val SAYS_LENGTH = 200

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
