package com.app.newspaperss.ui.listen

import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.core.listen.ListenScript.StyleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextTest {
    private fun cell(text: String, colspan: Int = 1, rowspan: Int = 1) = Block.Table.Cell(text, header = false, colspan = colspan, rowspan = rowspan)
    private fun row(vararg cells: Block.Table.Cell) = Block.Table.Row(cells.toList(), listOf(cells.joinToString(", ") { it.shown }))

    @Test
    fun aCellSpanningDownKeepsTheCellsUnderItInTheirColumns() {
        val table = Block.Table(
            listOf(
                row(cell("Day"), cell("Time"), cell("Event")),
                row(cell("Monday", rowspan = 2), cell("9:00"), cell("Talk")),
                row(cell("11:00"), cell("Lunch")),
                row(cell("Tuesday"), cell("All day", colspan = 2)),
            ),
        )
        fun shown(slot: Slot) = when (slot) {
            is Slot.Cell -> slot.cell.shown
            Slot.Across -> "→"
            Slot.Down -> "↓"
        }
        assertEquals(
            listOf(listOf("Day", "Time", "Event"), listOf("Monday", "9:00", "Talk"), listOf("↓", "11:00", "Lunch"), listOf("Tuesday", "All day", "→")),
            table.grid().map { it.map(::shown) },
        )
    }

    @Test
    fun aTableTooBigToDrawCellByCellIsDrawnAsItsLinesWithTheirPlaces() {
        val big = Block.Table(List(MOST_TABLE_CELLS / 2 + 1) { row(cell("$it"), cell("x")) })
        assertTrue(big.tooBig())
        assertFalse(Block.Table(listOf(row(cell("1"), cell("2")))).tooBig())
        val text = drawnAsText(big)!!
        assertEquals(big.said, text.sentences)
        // Each line is where it's drawn, a row to a line.
        assertEquals(listOf("0, x", "1, x"), text.ranges.take(2).map { text.shown.substring(it.first, it.last + 1) })
        assertEquals('\n', text.shown[text.ranges[0].last + 1])
        assertNull(drawnAsText(Block.Table(listOf(row(cell("1"))))))
    }

    @Test
    fun onlyWebLinksOpenAndTheNextLineGoesToItsArticle() {
        fun at(href: String): LinkTarget? {
            val block = Block.Text(Kind.META, emptyList(), "Next: The one after", styles = listOf(ListenScript.Style(6, 19, StyleKind.LINK, href)))
            return linkAt(block, 8)
        }
        assertEquals(LinkTarget.Web("https://example.com/a"), at("https://example.com/a"))
        assertEquals(LinkTarget.Page(1), at("article-002.xhtml"))
        assertEquals(LinkTarget.Page(null), at("end.xhtml"))
        // A footnote, on this page or the second article's; anything but the web opens nothing.
        assertEquals(LinkTarget.Anchor("fn1"), at("#fn1"))
        assertEquals(LinkTarget.Anchor("fn1", 1), at("article-002.xhtml#fn1"))
        for (href in listOf("#", "javascript:alert(1)", "intent://x#Intent;end", "file:///etc/hosts", "mailto:a@example.com")) assertNull(href, at(href))
        assertNull(linkAt(Block.Text(Kind.META, emptyList(), "Plain"), 2))
    }

    @Test
    fun linksAreNamedForTalkBackByWhereTheyGo() {
        fun label(shown: String, href: String): String {
            val block = Block.Text(Kind.META, emptyList(), shown, styles = listOf(ListenScript.Style(0, shown.length, StyleKind.LINK, href)))
            return linkLabel(block, linkAt(block, 0)!!)
        }
        assertEquals("Go to Next: Two · 30 min", label("Next: Two · 30 min\u00A0→", "article-002.xhtml"))
        assertEquals("Open the paper", label("the paper", "https://example.com/paper"))
        assertEquals("Go to note 3", label("3", "#fn3"))
        assertEquals("Back to the text", label("↩", "#ref3"))
    }
}
