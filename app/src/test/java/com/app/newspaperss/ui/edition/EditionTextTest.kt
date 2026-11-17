package com.app.newspaperss.ui.edition

import org.junit.Assert.assertEquals
import org.junit.Test

/** Today's cards and the edition page say an edition's length as its cover and notification do. */
class EditionTextTest {
    @Test
    fun anHourOrMoreIsSaidInHours() {
        assertEquals("8 articles · about 33 min", articlesAndMinutes(8, 33.0))
        assertEquals("12 articles · about 1 hr 15 min", articlesAndMinutes(12, 75.0))
        assertEquals("1 hr 5 min", minutesLabel(65.0))
        assertEquals("a picture with no words still takes a moment", "1 min", minutesLabel(0.0))
    }
}
