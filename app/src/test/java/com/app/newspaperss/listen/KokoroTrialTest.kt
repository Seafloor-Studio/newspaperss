package com.app.newspaperss.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which editions are made on two threads and which on four. */
class KokoroTrialTest {
    private val editions = (1L..200L).toList()

    @Test
    fun itOnlyEverAsksForTwoOrFour() {
        assertEquals(
            setOf(KokoroTrial.FEWER, KOKORO_THREADS),
            editions.map { KokoroTrial.threadsFor(it) }.toSet(),
        )
    }

    /** Every run of an edition has to agree, or a night is half one arm and half the other. */
    @Test
    fun anEditionAlwaysGetsTheSameAnswer() {
        editions.forEach { assertEquals(KokoroTrial.threadsFor(it), KokoroTrial.threadsFor(it)) }
    }

    /** Lopsided arms would take twice as many nights to say anything. */
    @Test
    fun theTwoArmsComeUpAboutAsOften() {
        val fewer = editions.count { KokoroTrial.threadsFor(it) == KokoroTrial.FEWER }
        assertTrue("$fewer of ${editions.size} on ${KokoroTrial.FEWER} threads", fewer in 80..120)
    }

    /**
     * Editions arrive about one a day, so an arm that alternated would line up with the days of
     * the week — and a Saturday paper is not a Tuesday one.
     */
    @Test
    fun itDoesntSimplyAlternate() {
        val shared = editions.zipWithNext().count { (a, b) -> KokoroTrial.threadsFor(a) == KokoroTrial.threadsFor(b) }
        assertTrue("$shared of ${editions.size - 1} neighbours shared an arm", shared in 60..140)
    }
}
