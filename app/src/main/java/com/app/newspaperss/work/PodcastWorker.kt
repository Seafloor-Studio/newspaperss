package com.app.newspaperss.work

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.newspaperss.container
import com.app.newspaperss.listen.PodcastMaker
import com.app.newspaperss.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Makes the podcasts asked for ([PodcastMaker.makeAll]), only while the phone charges: a
 * 30-minute podcast would take about a fifth of a charge. Unplugged, it stops and runs again
 * when the phone is plugged back in; the pages made are kept.
 *
 * It runs in the foreground, with a quiet notification, where Android allows that: as background
 * work, Android stops it after 10 minutes and, overnight, lets it run only a minute in several.
 * From Android 12, foreground work can start from the background only if the app's battery use
 * is Unrestricted, or the app is on screen; otherwise it's made as background work, slowly.
 */
class PodcastWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val log get() = applicationContext.container.podcastLog

    /** Noted from the guard as the stop comes, and again as the run ends: once is enough. */
    @Volatile
    private var timedOut = false

    override suspend fun doWork(): Result = try {
        val holdBack = holdBack()
        if (holdBack != null) {
            // Before loading Kokoro: that alone is seconds and 325 MB, and would heat the phone further.
            log.add("Not started (try ${runAttemptCount + 1}): $holdBack; trying again in ${COOL_OFF_MS / 60_000} min, ${standing()}")
            enqueue(applicationContext, COOL_OFF_MS)
        } else {
            val asked = mayGoForeground()
            log.add("Run started (try ${runAttemptCount + 1}): ${if (asked) "asking for the foreground" else "in the background"}, ${standing()}")
            if (asked && !goForeground()) {
                enqueue(applicationContext)
            } else {
                val started = SystemClock.elapsedRealtime()
                var checked = false
                var cooling = false
                val stopped = makeWhile(CHECK_MS, { applicationContext.container.podcastMaker.makeAll() }, ::noteTimeout) {
                    val foreground = inForeground()
                    if (!checked) {
                        checked = true
                        log.add(if (foreground) "In the foreground" else "Not in the foreground")
                    }
                    val hot = holdBack()
                    when {
                        !plugged() -> false.also { log.add("Stopped: unplugged") }
                        hot != null -> false.also {
                            cooling = true
                            log.add("Stopped: $hot; trying again in ${COOL_OFF_MS / 60_000} min")
                        }
                        foreground || SystemClock.elapsedRealtime() - started < BACKGROUND_MS -> true
                        else -> {
                            // Asked, and refused silently inside WorkManager's service: Android 15's
                            // 6 hours a day used up, most likely.
                            if (asked) holdOff(TIMED_OUT_MS)
                            log.add("Stopped at ${BACKGROUND_MS / 60_000} min, not in the foreground" + if (asked) "; not asking again for 12 hours" else "")
                            false
                        }
                    }
                }
                if (!stopped) log.add("Run ended")
                if (stopped) enqueue(applicationContext, if (cooling) COOL_OFF_MS else 0L)
            }
        }
        Result.success()
    } catch (e: CancellationException) {
        noteTimeout()
        log.add("Stopped by Android" + if (Build.VERSION.SDK_INT >= 31) ": ${stopReasonName(stopReason)} (reason $stopReason)" else "")
        throw e
    } catch (e: Exception) {
        // Kokoro couldn't load (its files damaged since the check, say): asked again, it tries again.
        // The class only: a message could carry anything, and the log is shared.
        log.add("Run failed: ${e.javaClass.name}")
        Result.failure()
    } catch (e: LinkageError) {
        log.add("Kokoro's native code didn't load: ${e.javaClass.name}")
        Result.failure()
    }

    /** What decides whether Android lets the work into the foreground, for the log. */
    private fun standing(): String {
        val power = applicationContext.getSystemService(PowerManager::class.java)
        val battery = if (power.isIgnoringBatteryOptimizations(applicationContext.packageName)) "battery unrestricted" else "battery optimized"
        val held = if (SystemClock.elapsedRealtime() < holdOffUntil) ", holding off after a refusal" else ""
        return "Android ${Build.VERSION.SDK_INT}, $battery, ${if (plugged()) "plugged in" else "unplugged"}, ${heat()}$held"
    }

    /**
     * Why this run shouldn't be making speech now, or null.
     *
     * Android throttles the CPU long before it calls the heat severe: a run measured at 1.6x
     * realtime on a cool phone has been seen at 5.7x, where it makes a second of audio every six,
     * heats the phone for no gain, and loses the unkept part of a piece each time Android stops
     * it. Stopping at moderate also keeps the phone short of the severities where Android's own
     * mitigation starts cutting power to the radios.
     */
    private fun holdBack(): String? {
        val power = applicationContext.getSystemService(PowerManager::class.java) ?: return null
        if (power.isPowerSaveMode) return "the phone is in battery saver"
        if (Build.VERSION.SDK_INT < 29) return null
        val status = power.currentThermalStatus
        if (status >= PowerManager.THERMAL_STATUS_MODERATE) return "the phone is hot (${thermalName(status)})"
        val headroom = headroom(power)
        if (headroom != null && headroom >= HEADROOM_LIMIT) {
            return "the phone is about to be throttled (headroom ${"%.2f".format(Locale.ROOT, headroom)})"
        }
        return null
    }

    /** The heat, for the log: a run's pace means little without it. */
    private fun heat(): String {
        if (Build.VERSION.SDK_INT < 29) return "heat unknown"
        val power = applicationContext.getSystemService(PowerManager::class.java) ?: return "heat unknown"
        val saver = if (power.isPowerSaveMode) ", battery saver" else ""
        val headroom = headroom(power)?.let { ", headroom ${"%.2f".format(Locale.ROOT, it)}" } ?: ""
        return "thermal ${thermalName(power.currentThermalStatus)}$headroom$saver"
    }

    /** Null when Android won't say: asked too often (ours is every [CHECK_MS]) or too soon after boot. */
    private fun headroom(power: PowerManager): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching { power.getThermalHeadroom(HEADROOM_SECONDS) }.getOrNull()?.takeUnless { it.isNaN() }
    }

    /**
     * Whether Android will let this work go foreground. Asking when it won't is worse than not
     * asking: WorkManager counts the work as foreground before Android refuses, and then ignores
     * the phone being unplugged and its own 10-minute limit.
     */
    private fun mayGoForeground(): Boolean {
        if (SystemClock.elapsedRealtime() < holdOffUntil) return false
        if (Build.VERSION.SDK_INT < 31) return true
        val power = applicationContext.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(applicationContext.packageName)) return true
        val me = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** False if Android refused: this run ends, so WorkManager lets go of it, and the next runs in the background. */
    private suspend fun goForeground(): Boolean {
        val notification = applicationContext.container.notifier.makingPodcast()
        val info = when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(Notifier.PODCAST_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(Notifier.PODCAST_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(Notifier.PODCAST_ID, notification)
        }
        return try {
            setForeground(info)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Started from the background after all: the app left the screen just then, say.
            log.add("Foreground refused: ${e.javaClass.name}; in the background for an hour")
            holdOff(HOLD_OFF_MS)
            false
        }
    }

    /**
     * Stopped by WorkManager with Android 15's 6 hours a day in the foreground used up: asking
     * again soon would be refused silently. Noted as soon as the stop comes, not once Kokoro
     * finishes its sentence: the next run may start before that.
     */
    private fun noteTimeout() {
        if (Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT && !timedOut) {
            timedOut = true
            holdOff(TIMED_OUT_MS)
            log.add("Android's daily time in the foreground used up; not asking again for 12 hours")
        }
    }

    private fun holdOff(ms: Long) {
        holdOffUntil = SystemClock.elapsedRealtime() + ms
    }

    /**
     * Whether WorkManager's service really is in the foreground: Android can refuse it silently,
     * inside the service. Not the process's standing, which the app's own player or screen lifts.
     */
    @Suppress("DEPRECATION") // Still lists the app's own services.
    private fun inForeground() = applicationContext.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.foreground && it.service.className == FOREGROUND_SERVICE }

    /** Plugged in, charging or not: a phone holding its charge at 80% overnight still counts. */
    private fun plugged(): Boolean {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return true
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    companion object {
        internal const val UNIQUE = "podcast"
        private const val CHECK_MS = 30_000L

        /** How long the phone is left alone after a run stopped, or didn't start, for heat. */
        private const val COOL_OFF_MS = 20 * 60_000L

        /** How far ahead Android forecasts the heat. Its own ceiling is a minute. */
        private const val HEADROOM_SECONDS = 30

        /** 1.0 is throttling; backing off a little short of it keeps the run ahead of the throttle. */
        private const val HEADROOM_LIMIT = 0.95f

        /** WorkManager's, by name: the class is internal to it. */
        private const val FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"

        /** Short of WorkManager's 10 minutes, when the work isn't really in the foreground. */
        private const val BACKGROUND_MS = 9 * 60_000L

        /** After a refusal, how long the work stays in the background before asking again. */
        private const val HOLD_OFF_MS = 60 * 60_000L

        /** After the foreground's daily time ran out: Android counts it over a day. */
        private const val TIMED_OUT_MS = 12 * 60 * 60_000L

        /** Until when the work doesn't ask for the foreground, after a refusal. Kept only while the app runs. */
        @Volatile
        internal var holdOffUntil = 0L

        /**
         * Runs [make] while [keepGoing], checked every [checkMs]; true if it was stopped. Here as
         * well as in WorkManager: work that went foreground in WorkManager's eyes but not
         * Android's is never stopped by WorkManager. [cancelled] is called as soon as the work is
         * cancelled, while [make] may still be finishing: from outside, or by [make] failing.
         */
        internal suspend fun makeWhile(checkMs: Long, make: suspend () -> Unit, cancelled: () -> Unit, keepGoing: () -> Boolean): Boolean = coroutineScope {
            var stopped = false
            var finished = false
            val making = launch { make() }
            val guard = launch {
                try {
                    while (true) {
                        delay(checkMs)
                        if (!keepGoing()) {
                            stopped = true
                            making.cancel()
                            break
                        }
                    }
                } finally {
                    if (!finished && !stopped) cancelled()
                }
            }
            making.join()
            finished = true
            guard.cancel()
            stopped
        }

        /**
         * Makes the podcasts asked for once the phone charges. Appended: a run under way finishes
         * its page, sees the newest edition asked for, and moves to it; one finishing just as
         * this is asked runs again after.
         */
        fun enqueue(context: Context, afterMs: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<PodcastWorker>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                // Appended, so a podcast asked for while the phone cools waits for the cool-off too:
                // the phone being too hot to make speech is the reason either way.
                .apply { if (afterMs > 0) setInitialDelay(afterMs, TimeUnit.MILLISECONDS) }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Android's stop reasons by name: the number alone sent a session guessing. */
        internal fun stopReasonName(reason: Int) = when (reason) {
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled by the app"
            WorkInfo.STOP_REASON_PREEMPT -> "preempted"
            WorkInfo.STOP_REASON_TIMEOUT -> "timed out"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "device state, heat most likely"
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery low"
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "unplugged"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "no network"
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "phone in use"
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage low"
            WorkInfo.STOP_REASON_QUOTA -> "out of its share of background work"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background work restricted"
            WorkInfo.STOP_REASON_APP_STANDBY -> "app in standby"
            WorkInfo.STOP_REASON_USER -> "by the reader"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "the system needed the phone"
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "the app was about to be launched"
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "the day's foreground time used up"
            WorkInfo.STOP_REASON_NOT_STOPPED -> "not stopped"
            WorkInfo.STOP_REASON_UNKNOWN -> "unknown"
            else -> "unnamed"
        }

        /** Android's heat by name, for the log. Internal so a test can ask for every one of them. */
        @RequiresApi(Build.VERSION_CODES.Q)
        internal fun thermalName(status: Int) = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "status $status"
        }
    }
}
