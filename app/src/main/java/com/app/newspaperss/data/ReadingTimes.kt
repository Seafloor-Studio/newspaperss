package com.app.newspaperss.data

import android.content.SharedPreferences

/**
 * Gives saved links that never got a reading time one, a batch at a time: those saved before
 * pages were measured, and imports that came with a title, which [ReadingListRepository] doesn't
 * look up. New links are measured when saved; this only catches up.
 *
 * It works through the list in id order, remembering where it got to, so a page that won't load
 * doesn't hold up the links after it. A link still unmeasured at the end of the list waits for
 * the next pass, and after [MAX_PASSES] passes only links newer than the last one tried are
 * looked at: a page that has failed that often is left without a time rather than fetched
 * twice a day for good.
 */
class ReadingTimes(
    private val db: AppDatabase,
    private val titles: ReadingListTitles,
    private val prefs: SharedPreferences,
) {
    /** Measures the next batch; returns how many links it tried. */
    suspend fun measureNext(): Int {
        val sourceId = db.sources().byUrl(ReadingListRepository.URL)?.id ?: return 0
        var after = prefs.getLong(AFTER, 0L)
        var ids = db.articles().unmeasured(sourceId, after, BATCH)
        if (ids.isEmpty() && after > 0L) {
            val passes = prefs.getInt(PASSES, 1)
            if (passes >= MAX_PASSES) return 0
            after = 0L
            prefs.edit().putLong(AFTER, after).putInt(PASSES, passes + 1).apply()
            ids = db.articles().unmeasured(sourceId, after, BATCH)
        }
        if (ids.isEmpty()) return 0
        titles.fetch(ids)
        // Only once the batch is done: a run stopped part way (the charger pulled out) does it
        // again, and the links it did measure are no longer asked for.
        prefs.edit().putLong(AFTER, ids.last()).apply()
        return ids.size
    }

    companion object {
        /** Twice a day, so at most 40 pages a day. Small for the same reason as the titles' batches. */
        const val BATCH = 20
        const val MAX_PASSES = 3
        private const val AFTER = "after"
        private const val PASSES = "passes"
    }
}
