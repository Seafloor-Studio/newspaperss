package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.KokoroText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** A line too long for Kokoro is said in pieces and comes back as one line's audio. */
class KokoroPiecesTest {
    private val rate = 24_000
    private val said = mutableListOf<String>()

    /** A Kokoro that says each character as one sample of its piece's number. */
    private val say = { text: String ->
        said += text
        Speech(FloatArray(text.length) { said.size.toFloat() }, rate)
    }

    @Test
    fun aShortLineIsOneCallWithNothingAdded() {
        val speech = speakInPieces("Nobody knew.", say)
        assertEquals(listOf("Nobody knew."), said)
        assertEquals(12, speech.samples.size)
    }

    @Test
    fun aLongLineIsSaidPieceByPieceAndJoinedWithAPause() {
        val line = "word ".repeat(40) + "then, " + "word ".repeat(40)
        val speech = speakInPieces(line, say)

        assertEquals(KokoroText.pieces(line), said)
        assertEquals(2, said.size)
        val gap = (rate * PIECE_GAP_SECONDS).toInt()
        assertEquals(said[0].length + gap + said[1].length, speech.samples.size)
        assertEquals(rate, speech.sampleRate)
        // The first piece, the quiet, then the second, in order.
        assertArrayEquals(FloatArray(said[0].length) { 1f }, speech.samples.copyOfRange(0, said[0].length), 0f)
        assertArrayEquals(FloatArray(gap), speech.samples.copyOfRange(said[0].length, said[0].length + gap), 0f)
        assertArrayEquals(FloatArray(said[1].length) { 2f }, speech.samples.copyOfRange(said[0].length + gap, speech.samples.size), 0f)
    }
}
