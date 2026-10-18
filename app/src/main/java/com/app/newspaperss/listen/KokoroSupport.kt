package com.app.newspaperss.listen

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Whether this phone should be offered Kokoro at all.
 *
 * Making a podcast keeps several cores busy for hours. A phone that can't do that without
 * overheating doesn't make a slower podcast, it makes a throttled one: a run measured at 1.6x
 * realtime cool has been seen at 5.7x, and Android's own thermal mitigation cuts power to the
 * radios at the severities beyond that. Better not to offer the voice than to cook the phone for
 * a podcast that never finishes.
 */
object KokoroSupport {
    /**
     * Why Kokoro can't run on this phone, as a sentence for Settings, or null if it can.
     *
     * A phone that already has Kokoro ([installed]) keeps it whatever the bars say. They were
     * raised after Kokoro shipped, and taking a working voice away from someone who has already
     * downloaded 384 MB of it would be its own harm; the heat is handled at that point by
     * [com.app.newspaperss.work.PodcastWorker] stopping when the phone gets hot.
     */
    fun why(context: Context, installed: Boolean): String? {
        if (!KokoroInstall.supported) return "Kokoro needs a 64-bit phone, and this one is 32-bit."
        if (installed) return null
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return "Kokoro needs Android 10 or newer, where the phone can say when it's overheating."
        }
        val memory = context.getSystemService(ActivityManager::class.java)
        if (memory?.isLowRamDevice == true) return "Kokoro needs more memory than this phone has."
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores < MIN_CORES) {
            return "Kokoro keeps several cores busy for hours; with $cores this phone would overheat before a podcast was made."
        }
        return null
    }

    /** Android 10 brought [android.os.PowerManager.getCurrentThermalStatus], which the making needs. */
    private const val MIN_SDK = 29

    /** Kokoro runs on four threads: on fewer than this they are most of the phone. */
    private const val MIN_CORES = 6
}
