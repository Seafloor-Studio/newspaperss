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
        // The comma is nearer the middle, but a clause's end is the better place.
        val clause = "word ".repeat(43) + "then, " + "word ".repeat(3) + "so it went; " + "word ".repeat(37)
        val atClause = KokoroText.pieces(clause)
        assertTrue(atClause.toString(), atClause[0].endsWith("went;"))
        assertWhole(clause, atClause)

        val comma = "word ".repeat(40) + "then, " + "word ".repeat(40)
        val atComma = KokoroText.pieces(comma)
        assertTrue(atComma.toString(), atComma[0].endsWith("then,"))
        assertWhole(comma, atComma)
    }

    @Test
    fun theLastCutIsNearTheMiddleSoNoPieceIsAScrap() {
        val line = "word ".repeat(54) + "and that was all, she said."
        assertTrue(KokoroText.weight(line) in 251..300)
        val pieces = KokoroText.pieces(line)
        assertEquals(2, pieces.size)
        assertTrue(pieces.toString(), pieces.all { KokoroText.weight(it) >= KokoroText.weight(line) / 3 && words(it).size > 1 })
        assertWhole(line, pieces)

        // A comma in reach but near the end would leave a scrap: a space nearer the middle wins.
        val late = "word ".repeat(48) + "so, " + "word ".repeat(11)
        val evenly = KokoroText.pieces(late)
        assertTrue(evenly.toString(), evenly.all { KokoroText.weight(it) >= KokoroText.weight(late) / 3 })
        assertWhole(late, evenly)

        // Just over the limit, with a comma near the middle: cut there.
        val middle = "word ".repeat(25) + "then, " + "word ".repeat(25)
        assertTrue(KokoroText.weight(middle) > KokoroText.LIMIT)
        assertEquals(listOf("word ".repeat(25) + "then,", "word ".repeat(25).trim()), KokoroText.pieces(middle))
    }

    @Test
    fun aMarkInsideANumberOrBeforeAQuoteIsReadRightly() {
        // "1,250" isn't cut at its comma; a comma inside closing quotes still is a comma.
        val line = "word ".repeat(38) + "\"so it goes,\" she said " + "word ".repeat(2) + "and 1,250 " + "word ".repeat(30)
        val pieces = KokoroText.pieces(line)
        assertTrue(pieces.toString(), pieces[0].endsWith("goes,\""))
        assertWhole(line, pieces)
    }

    @Test
    fun aMarkNearThePieceStartIsPassedOverForASpaceFurtherOn() {
        val line = "Well, " + "word ".repeat(120)
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
    fun wordsReadLetterByLetterCountForEachLetter() {
        // Each came to 466 to 510 tokens in one piece with plain weights; 510 is where sherpa exits.
        for (unit in listOf("www.example.org/utm_source=feed ", "https://www.example.com/a/b/c ", "e.g. i.e. etc. vs. ", "xkcd www rss cms ", "nyt.com bbc.co.uk ")) {
            val line = unit.repeat(KokoroText.LIMIT / unit.length).trim()
            val pieces = KokoroText.pieces(line)
            assertTrue("$unit: $pieces", pieces.size >= 2)
            assertWhole(line, pieces)
        }
        assertTrue(KokoroText.weight("xkcd") > 4 * KokoroText.weight("kick"))
        assertTrue(KokoroText.weight("e.g.") > 4 * KokoroText.weight("eggs"))
        // "dot", not a pause.
        assertTrue(KokoroText.weight("example.org") > KokoroText.weight("example org") + 2)
        assertEquals("a word with a vowel is read as a word", 8, KokoroText.weight("strength"))
    }

    @Test
    fun aRunWithNoSpaceIsCutAfterAPartOfAnAddressOrAtTheLimit() {
        val address = "https://example.com/" + "abcdefgh/".repeat(40)
        val parts = KokoroText.pieces(address)
        assertTrue(parts.toString(), parts.size >= 2 && parts.dropLast(1).all { it.endsWith("/") })
        assertEquals(address, parts.joinToString(""))

        val run = "ab".repeat(300)
        val pieces = KokoroText.pieces(run)
        assertEquals(listOf(250, 250, 100), pieces.map { it.length })
        assertEquals(run, pieces.joinToString(""))
        // Nor between the halves of a character outside the BMP: the ten letters put the limit's
        // edge between the halves of the 22nd face.
        val emoji = "a".repeat(10) + String(Character.toChars(0x1F600)).repeat(30)
        assertTrue(KokoroText.pieces(emoji).all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        // Nor between a letter and the accent on it.
        val accents = "é".repeat(60)
        val accented = KokoroText.pieces(accents)
        assertTrue(accented.size >= 2)
        assertTrue(accented.all { Character.getType(it.first()) != Character.NON_SPACING_MARK.toInt() })
        assertEquals(accents, accented.joinToString(""))
    }

    @Test
    fun aNoBreakSpaceHoldsAndChineseCommasAndStopsCut() {
        val line = "10 km ".repeat(20)
        val pieces = KokoroText.pieces(line)
        assertTrue(pieces.size >= 2)
        assertTrue(pieces.toString(), pieces.all { piece -> piece.split(' ').all { it.contains(' ') } })

        val chinese = "我们走了，".repeat(9) + "我们到了。" + "我们走了，".repeat(9)
        val clauses = KokoroText.pieces(chinese)
        assertTrue(clauses.toString(), clauses.size >= 2 && clauses.dropLast(1).all { it.last() in "，。" })
        assertEquals(chinese, clauses.joinToString(""))
    }
}
