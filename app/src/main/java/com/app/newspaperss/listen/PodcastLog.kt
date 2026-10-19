package com.app.newspaperss.listen

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the podcast's making did, run by run (when each run started and stopped and why, what it
 * kept, the sentences that took long or sound wrong), and where each play started and what stopped
 * one. Kept in [dir] as a file per day so a phone without a computer attached can show it, and
 * written to logcat for one with.
 *
 * A night of making is about 150 entries, so the [keep] the screen shows is a couple of nights.
 * What is *stored* is [retainDays] of days, which is a different question: a run is read beside the
 * ones before it, and a change that only shows up over a fortnight can't be read off two nights.
 * Days are files rather than one long file because the entries carry no year — trimming one file by
 * age would mean telling last January from next — while a day in the name sorts and expires without
 * reading a byte of it.
 *
 * @param dir null to write to logcat only.
 */
class PodcastLog(
    private val dir: File?,
    private val keep: Int = KEEP,
    private val retainDays: Int = RETAIN_DAYS,
    /**
     * The single file the log lived in before days: folded into today's once and deleted, so a
     * phone updating mid-test keeps the night it was testing. Removable once none has it.
     */
    private val legacy: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // Not the phone's locale: the log is read by people fixing the app, and pasted to them.
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)

    /** The day last written to: the fold and the expiry happen when it turns over, not every line. */
    private var wrote: String? = null

    fun add(message: String) {
        Log.i(TAG, message)
        val dir = dir ?: return
        synchronized(this) {
            try {
                val today = day.format(Date(now()))
                if (wrote != today) {
                    dir.mkdirs()
                    fold(dir, today)
                    expire(dir)
                    wrote = today
                }
                File(dir, "$today$SUFFIX").appendText("${stamp.format(Date(now()))}  $message\n")
            } catch (e: Exception) {
                // A full disk, or a file where the folder should be: the log is the first to give up.
            }
        }
    }

    /** The entries the screen shows, oldest first: the newest [keep] of however many days are kept. */
    fun read(): List<String> = synchronized(this) { entries().takeLast(keep) }

    /**
     * Every day kept, in one file to share. Days are separate files and a share takes one, so this
     * is written beside them under a name [days] doesn't match. Null if there is nothing to send.
     */
    fun export(): File? = synchronized(this) {
        val dir = dir ?: return null
        val all = entries()
        if (all.isEmpty()) return null
        return try {
            File(dir, EXPORT).apply { writeText(all.joinToString("\n", postfix = "\n")) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The days, oldest first, with whatever the single file still holds in front of them.
     *
     * [fold] moves that file in, but only on the next write — and a reader who updates after a
     * night's making and opens the log before anything else runs would otherwise be told there is
     * nothing, with a fortnight of it sitting on disk and no way to reach it: this folder is
     * private storage, so the share is the only way out of the phone.
     */
    private fun entries(): List<String> {
        val dir = dir ?: return emptyList()
        return try {
            legacy?.takeIf { it.isFile }?.readLines().orEmpty() + days(dir).flatMap { it.readLines() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The day files, oldest first. Named so [EXPORT] and anything else here isn't taken for one. */
    private fun days(dir: File) = dir.listFiles().orEmpty()
        .filter { it.isFile && DAY.matches(it.name) }
        .sortedBy { it.name }

    private fun fold(dir: File, today: String) {
        val legacy = legacy?.takeIf { it.isFile } ?: return
        File(dir, "$today$SUFFIX").appendText(legacy.readText())
        legacy.delete()
    }

    /** Days older than [retainDays]. The names sort, so this is a comparison, not a parse. */
    private fun expire(dir: File) {
        val oldest = day.format(Date(now() - retainDays * DAY_MS)) + SUFFIX
        days(dir).filter { it.name < oldest }.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "PodcastLog"
        const val SUFFIX = ".txt"
        const val EXPORT = "audio-log.txt"

        /** Entries the screen shows at once: more than this is for the shared file to carry. */
        const val KEEP = 400

        /** Days kept. A fortnight covers a change that only shows up over several nights. */
        const val RETAIN_DAYS = 14

        const val DAY_MS = 24 * 60 * 60 * 1000L
        val DAY = Regex("""\d{4}-\d{2}-\d{2}\.txt""")
    }
}
