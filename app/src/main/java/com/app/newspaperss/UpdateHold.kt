package com.app.newspaperss

import android.content.SharedPreferences
import java.util.concurrent.TimeUnit

/**
 * The reader is in another app the app opened (a file or folder picker, the browser, the share
 * sheet) and hasn't come back. The app isn't in the foreground then, but they haven't left it: a
 * background update would end it underneath them, and the picker's answer would have nowhere to go.
 *
 * Kept on disk, so an install in a process started after this one died still waits. Any return to
 * the app clears it, and it lapses after [LIMIT_MILLIS]: a reader who went from the browser to Home
 * has left, and would otherwise hold every update until they next open the app.
 */
class UpdateHold(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
    val held: Boolean get() = now() - prefs.getLong(AWAY_SINCE, Long.MIN_VALUE / 2) in 0 until LIMIT_MILLIS

    fun away() = prefs.edit().putLong(AWAY_SINCE, now()).apply()

    fun back() {
        if (prefs.contains(AWAY_SINCE)) prefs.edit().remove(AWAY_SINCE).apply()
    }

    companion object {
        val LIMIT_MILLIS = TimeUnit.HOURS.toMillis(1)
        private const val AWAY_SINCE = "awaySince"
    }
}
