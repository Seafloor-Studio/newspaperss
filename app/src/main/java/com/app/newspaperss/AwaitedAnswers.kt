package com.app.newspaperss

import android.content.Context

/**
 * The answers [MainActivity] is waiting on from another app: a picker opening a file to import,
 * making one to export or save an edition to, or choosing the delivery folder. Android pauses
 * the app for those just as when the reader leaves it, so a background install would end it
 * there, and the answer would arrive nowhere: the import is lost, and the file the picker just
 * made is left empty.
 *
 * Kept on disk, by request code: if Android ends the app while the picker is open, it restores
 * it to take the answer, and a background install in another process meanwhile must wait too.
 * Committed, not applied, so a process killed right after the launch still leaves it.
 */
class AwaitedAnswers(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val codes: Set<String> get() = prefs.getStringSet(KEY, null).orEmpty()

    /** Waiting on at least one answer. */
    val any: Boolean get() = codes.isNotEmpty()

    fun sent(requestCode: Int) {
        prefs.edit().putStringSet(KEY, codes + requestCode.toString()).commit()
    }

    fun answered(requestCode: Int) {
        val left = codes - requestCode.toString()
        if (left.size != codes.size) prefs.edit().putStringSet(KEY, left).commit()
    }

    /** A fresh start with nothing to restore: no answer is coming any more. */
    fun forget() {
        if (any) prefs.edit().remove(KEY).commit()
    }

    private companion object {
        const val PREFS = "awaited-answers"
        const val KEY = "request-codes"
    }
}
