package com.app.newspaperss.core.feed

/** itunes:duration, which feeds write as seconds ("5400"), "MM:SS" or "HH:MM:SS". */
internal object FeedDurations {
    fun seconds(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 6 }) return null
        return parts.fold(0L) { total, part -> total * 60 + part.toLong() }.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }
}
