package com.app.newspaperss.core.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroTextTest {
    /** 425 characters that sherpa-onnx 1.13.8 makes 512 tokens of, and exits on. */
    private val crashes = (List(9) { "The quick brown fox jumps over the lazy dog" } + "The quick brown a cat sat on.").joinToString(" ")

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun assertWhole(text: String, pieces: List<String>) {
        assertTrue(pieces.toString(), pieces.all { KokoroText.weight(it) <= KokoroText.LIMIT && it.isNotBlank() && it == it.trim() })
        assertEquals("no word cut, none lost or moved", words(text), words(pieces.joinToString(" ")))
    }

    @Test
    fun aLineWithinTheLimitIsSaidAsItIs() {
        val line = "  Nobody knew, then a teacher counted.  "
        assertEquals(listOf(line), KokoroText.pieces(line))
        val longest = "a".repeat(KokoroText.LIMIT)
        assertEquals(listOf(longest), KokoroText.pieces(longest))
    }

    @Test
    fun theLineThatEndedSherpaIsSaidInPiecesWithinTheLimit() {
        val pieces = KokoroText.pieces(crashes)
        assertEquals(2, pieces.size)
        assertWhole(crashes, pieces)
        assertTrue(pieces.last().endsWith("a cat sat on."))
    }

    @Test
    fun aLongLineIsCutAtAClauseBeforeACommaAndAtACommaBeforeASpace() {
        val clause = "word ".repeat(30) + "and so it went; " + "word ".repeat(10) + "then, " + "word ".repeat(40)
        val atClause = KokoroText.pieces(clause)
        assertTrue(atClause.toString(), atClause[0].endsWith("went;"))
        assertWhole(clause, atClause)

        val comma = "word ".repeat(40) + "then, " + "word ".repeat(40)
        val atComma = KokoroText.pieces(comma)
        assertTrue(atComma.toString(), atComma[0].endsWith("then,"))
        assertWhole(comma, atComma)
    }

    @Test
    fun aMarkInsideANumberOrBeforeAQuoteIsReadRightly() {
        // "1,250" isn't cut at its comma; a comma inside closing quotes still is a comma.
        val line = "word ".repeat(30) + "\"so it goes,\" she said " + "word ".repeat(10) + "and 1,250 " + "word ".repeat(30)
        val pieces = KokoroText.pieces(line)
        assertTrue(pieces.toString(), pieces[0].endsWith("goes,\""))
        assertWhole(line, pieces)
    }

    @Test
    fun aMarkNearThePieceStartIsPassedOverForASpaceFurtherOn() {
        val line = "Well, " + "word ".repeat(70)
        val pieces = KokoroText.pieces(line)
        assertTrue(pieces.toString(), KokoroText.weight(pieces[0]) > KokoroText.LIMIT / 2)
        assertWhole(line, pieces)
    }

    @Test
    fun digitsAndCapitalsCountForTheSoundsTheyBecome() {
        // Each digit is read as several words' worth: 30 of them fill more than the limit.
        val numbers = (1..10).joinToString(" ") { "${it}00" }
        assertTrue(numbers.length < KokoroText.LIMIT / 4)
        val pieces = KokoroText.pieces("The years $numbers.")
        assertTrue(pieces.toString(), pieces.size >= 2)
        assertWhole("The years $numbers.", pieces)
        assertTrue(KokoroText.weight("NASA") > KokoroText.weight("nasa"))
        assertTrue(KokoroText.weight("%") > KokoroText.weight("."))
    }

    @Test
    fun aRunWithNoSpaceIsCutAtTheLimit() {
        val run = "x".repeat(600)
        val pieces = KokoroText.pieces(run)
        assertEquals(listOf(250, 250, 100), pieces.map { it.length })
        assertEquals(run, pieces.joinToString(""))
        // Nor between the halves of a character outside the BMP.
        // The ten letters put the limit's edge between the halves of the 22nd face.
        val emoji = "a".repeat(10) + String(Character.toChars(0x1F600)).repeat(30)
        assertTrue(KokoroText.pieces(emoji).all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
}
