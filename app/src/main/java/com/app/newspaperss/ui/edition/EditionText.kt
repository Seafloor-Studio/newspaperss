package com.app.newspaperss.ui.edition

import com.app.newspaperss.core.ReadingTime

/** "12 min", or "1 hr 5 min": never 0 for something that takes any time. */
internal fun minutesLabel(minutes: Double): String = ReadingTime.format(minutes.coerceAtLeast(1.0))

/** "8 articles · about 33 min", as Today's cards and the edition page say it. */
internal fun articlesAndMinutes(count: Int, minutes: Double): String = ReadingTime.summary(count, minutes)
