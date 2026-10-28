package com.app.newspaperss.core.listen

import com.app.newspaperss.core.listen.ListenScript.Block
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * What's wrong with how [ListenScript] holds a page of the book, for checking it over many real
 * pages: text on the page missing from the blocks or out of order, and spoken sentences that
 * aren't where their block shows them. Empty when all is well.
 */
internal fun pageProblems(xhtml: String): List<String> {
    val script = ListenScript.parse(xhtml)
    val problems = mutableListOf<String>()
    // The quotation marks a <q> is drawn with are the only text added; spaces can differ, and
    // Jsoup's text() drops the invisible zero-width space and soft hyphen, which are kept to break at.
    fun squeeze(text: String) = text.filterNot { it.isWhitespace() || it in "“”\u200B\u00AD" }
    val page = squeeze(Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("body")?.text().orEmpty())
    val held = squeeze(
        script.blocks.joinToString("") { block ->
            when (block) {
                is Block.Text -> block.shown
                is Block.Image -> block.caption.orEmpty()
                is Block.Table -> block.caption.orEmpty() + block.rows.joinToString("") { row -> row.cells.joinToString("") { it.shown } }
                Block.Rule -> ""
            }
        },
    )
    if (page != held) {
        val at = page.indices.firstOrNull { it >= held.length || page[it] != held[it] } ?: page.length
        problems += "the blocks differ from the page from: ${page.substring(at).take(80)} | held: ${held.substring(at.coerceAtMost(held.length)).take(80)}"
    }
    for ((b, block) in script.blocks.withIndex()) {
        if (block !is Block.Text) continue
        if (block.ranges.size != block.sentences.size) problems += "block $b: ${block.sentences.size} sentences, ${block.ranges.size} places"
        for ((i, sentence) in block.sentences.withIndex()) {
            val range = block.ranges.getOrNull(i) ?: continue
            if (range.first < 0 || range.last >= block.shown.length || range.isEmpty()) {
                problems += "block $b, sentence $i: place $range outside \"${block.shown.take(60)}\""
                continue
            }
            // What's said is what's shown there, less what's only shown (a footnote's marker).
            val there = block.shown.substring(range.first, range.last + 1).filterNot { it.isWhitespace() }
            val said = sentence.filterNot { it.isWhitespace() }
            var j = 0
            for (c in there) if (j < said.length && c == said[j]) j++
            if (j < said.length || there.first() != said.first() || there.last() != said.last()) {
                problems += "block $b, sentence $i: says \"${sentence.take(60)}\" where it shows \"${there.take(60)}\""
            }
        }
    }
    return problems
}
