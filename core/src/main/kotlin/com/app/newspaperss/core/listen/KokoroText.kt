package com.app.newspaperss.core.listen

/**
 * Cuts a line too long to give Kokoro in one go into pieces it can say one at a time.
 *
 * sherpa-onnx 1.13.8 ends the process when a sentence's phonemes come to exactly Kokoro's 510
 * tokens and it ends in a stop or a comma: it appends the stop after checking the length, then
 * finds 511 (piper-phonemize-lexicon.cc, offline-tts-kokoro-model.cc). Its 2.0 drops espeak, and
 * with it every word not in the lexicon, numbers included, so long lines are cut here instead.
 */
object KokoroText {
    /**
     * The most a piece weighs, in [weight]s. Plain English came to 1.13 tokens a character, so this
     * is about 285 tokens: room under 510 for a line that expands more than its weights say.
     */
    const val LIMIT = 250

    /** A piece lighter than this is cut at a space further on rather than at a mark nearer its start. */
    private const val SHORTEST = LIMIT / 2

    private const val PLAIN = 1
    // Measured with sherpa-onnx 1.13.8 in tokens a character: a capital is spelt out in acronyms
    // (up to about 3); ASCII symbols are read as words (up to about 6, "asterisk"); a digit up to
    // 12, since "7777777" is "seven million, seven hundred and…"; other characters up to about 8
    // (Ω, €, ™), with the rare one that unfolds into several letters (ﬃ, Ⅻ) left to the margin.
    private const val CAPITAL = 3
    private const val SYMBOL = 6
    private const val DIGIT = 11
    private const val OTHER = 11

    // espeak spells out a word with no vowel and a lone letter ("www", "xkcd", "e.g."): up to 10
    // tokens a letter, for "w". A stop inside a word is read "dot" or "point".
    private const val SPELT = 9
    private const val DOT = 4
    private const val VOWELS = "aeiouyAEIOUY"
    private val WORDS = setOf("a", "A", "I")

    private const val READ_PLAINLY = ".,;:!?'\"()[]-–—…‘’“”"
    private const val CLOSERS = "'\")]’”"

    /** Places to cut, best first: a clause's end, a comma, then any space between words. */
    private val TIERS = listOf(";:—–；：。", ",，、")

    /** Marks a cut can follow with no space after them, as in Chinese and Japanese. */
    private const val UNSPACED = "；：。，、"

    /** Where a run with no space in it is best cut: after a part of an address or a list. */
    private const val JOINS = "/.-?&=_"

    /** Spaces that keep the words either side together. */
    private const val NO_BREAK = "   ⁠"

    private fun weight(c: Char): Int = when {
        c in 'a'..'z' || c.isWhitespace() || c in READ_PLAINLY -> PLAIN
        c in 'A'..'Z' -> CAPITAL
        c in '0'..'9' -> DIGIT
        c.code < 128 -> SYMBOL
        else -> OTHER
    }

    /** About how many tokens each character of [text] becomes, in plain characters' worth. */
    private fun weights(text: CharSequence): IntArray {
        val weights = IntArray(text.length) { weight(text[it]) }
        var i = 0
        while (i < text.length) {
            if (!text[i].isAsciiLetter()) {
                if (text[i] == '.' && text.getOrNull(i + 1)?.isLetterOrDigit() == true) weights[i] = DOT
                i++
                continue
            }
            val start = i
            while (i < text.length && text[i].isAsciiLetter()) i++
            val word = text.subSequence(start, i)
            if (word.none { it in VOWELS } || word.length == 1 && word.toString() !in WORDS) {
                for (j in start until i) weights[j] = SPELT
            }
        }
        return weights
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    fun weight(text: CharSequence): Int = weights(text).sum()

    /**
     * [text] as pieces of at most [LIMIT], cut between words after the best punctuation in reach,
     * each trimmed and none empty. A line within the limit comes back as it is, alone; a run with
     * no space in it longer than the limit is cut at the limit.
     */
    fun pieces(text: String): List<String> {
        if (weight(text) <= LIMIT || text.isBlank()) return listOf(text)
        // upTo[i] is the weight of text[0, i).
        val upTo = IntArray(text.length + 1)
        weights(text).forEachIndexed { i, w -> upTo[i + 1] = upTo[i] + w }
        val pieces = mutableListOf<String>()
        var start = text.indexOfFirst { !it.isWhitespace() }
        while (start < text.length && upTo[text.length] - upTo[start] > LIMIT) {
            var end = start + 1
            while (upTo[end + 1] - upTo[start] <= LIMIT) end++
            var cut = cut(text, start, end, upTo)
            // A combining accent stays with the letter it sits on.
            while (cut - 1 > start && Character.getType(text[cut]).toByte() in MARKS) cut--
            text.substring(start, cut).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
            start = cut
            while (start < text.length && text[start].isWhitespace()) start++
        }
        text.substring(start).trim().takeIf { it.isNotEmpty() }?.let { pieces += it }
        return pieces
    }

    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)

    /** Where to end the piece starting at [start] that can reach no further than [end]. */
    private fun cut(text: String, start: Int, end: Int, upTo: IntArray): Int {
        // A cut at b leaves text[b] to the next piece and the mark before it with this one.
        val between = (end downTo start + 1).filter {
            text[it - 1] in UNSPACED || text[it].isWhitespace() && text[it] !in NO_BREAK && !text[it - 1].isWhitespace()
        }
        fun mark(b: Int): Char {
            var p = b - 1
            while (p > start && text[p] in CLOSERS) p--
            return text[p]
        }
        fun weight(b: Int) = upTo[b] - upTo[start]
        val rest = upTo[text.length] - upTo[start]
        if (rest <= 2 * LIMIT) {
            // The last cut: as near the middle as the best mark allows, so neither piece is a scrap.
            val fair = between.filter { weight(it) >= maxOf(rest / 3, rest - LIMIT) && weight(it) <= rest - rest / 3 }
            val nearest = compareBy<Int> { Math.abs(2 * weight(it) - rest) }
            for (tier in TIERS) fair.filter { mark(it) in tier }.minWithOrNull(nearest)?.let { return it }
            fair.minWithOrNull(nearest)?.let { return it }
        }
        val long = between.filter { weight(it) >= SHORTEST }
        for (tier in TIERS) long.firstOrNull { mark(it) in tier }?.let { return it }
        long.firstOrNull()?.let { return it }
        for (tier in TIERS) between.firstOrNull { mark(it) in tier }?.let { return it }
        between.firstOrNull()?.let { return it }
        // No space within reach: cut after a part of an address, else at the limit, but not
        // between a surrogate pair.
        (end - 1 downTo start + 1).firstOrNull { text[it] in JOINS }?.let { return it + 1 }
        return if (text[end - 1].isHighSurrogate() && end - 1 > start) end - 1 else end
    }
}
