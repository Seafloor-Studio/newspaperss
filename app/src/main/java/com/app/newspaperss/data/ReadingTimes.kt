package com.app.newspaperss.data

import android.content.SharedPreferences

/**
 * Gives saved links that never got a reading time one, a batch at a time: those saved before
 * pages were measured, and imports that came with a title, which [ReadingListRepository] doesn't
 * look up. New links are measured when saved; this only catches up.
 *
 * It works through the list in id order, remembering where it got to after each link, so a page
 * that won't load, or a run stopped part way, doesn't hold up the links after it. A link still
 * unmeasured at the end of the list waits for the next pass. After [MAX_PASSES] passes the links
 * left are given up on (everything up to the last one tried), rather than fetched twice a day for
 * good; links saved after that get passes of their own (a link saved during the last pass gets
 * only what's left of it).
 */
class ReadingTimes(
    private val db: AppDatabase,
    private val titles: ReadingListTitles,
    private val prefs: SharedPreferences,
) {
    /** Measures the next batch; returns how many links it tried. */
    suspend fun measureNext(): Int {
        val sourceId = db.sources().byUrl(ReadingListRepository.URL)?.id ?: return 0
        val floor = prefs.getLong(GIVEN_UP_THROUGH, 0L)
        val after = maxOf(prefs.getLong(AFTER, 0L), floor)
        var ids = db.articles().unmeasured(sourceId, after, BATCH)
        if (ids.isEmpty()) {
            if (after == floor) return 0
            val passes = prefs.getInt(PASSES, 1) + 1
            if (passes > MAX_PASSES) {
                prefs.edit().putLong(GIVEN_UP_THROUGH, after).putLong(AFTER, after).putInt(PASSES, 1).apply()
                return 0
            }
            ids = db.articles().unmeasured(sourceId, floor, BATCH)
            // Nothing left to measure: a pass that tried nothing doesn't count against the next link.
            prefs.edit().putLong(AFTER, floor).putInt(PASSES, if (ids.isEmpty()) 1 else passes).apply()
        }
        for (id in ids) {
            titles.fetch(listOf(id))
            prefs.edit().putLong(AFTER, id).apply()
        }
        return ids.size
    }

    companion object {
        /** Twice a day, so at most 40 pages a day. Small for the same reason as the titles' batches. */
        const val BATCH = 20
        const val MAX_PASSES = 3
        private const val AFTER = "after"
        private const val PASSES = "passes"
        private const val GIVEN_UP_THROUGH = "given-up-through"
    }
}
