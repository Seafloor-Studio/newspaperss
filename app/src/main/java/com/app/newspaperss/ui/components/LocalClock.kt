package com.app.newspaperss.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The clock screens read for today's date and lines like "2 days ago" or "last checked at 6:53".
 * The phone's own outside tests; ScreenshotTest fixes it, so a screen renders the same on any day.
 */
val LocalClock = staticCompositionLocalOf<Clock> { PhoneClock }

/**
 * The system clock in whatever zone the phone is in when it's read. Clock.systemDefaultZone()
 * keeps the zone it started with, which would go stale when the reader travels.
 */
private object PhoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}
