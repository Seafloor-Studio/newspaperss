package com.app.newspaperss.ui.listen

import androidx.compose.foundation.background
import com.app.newspaperss.core.listen.ListenScript
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.core.listen.ListenScript.StyleKind
import com.app.newspaperss.settings.PreviewTextSize

/** [this] at the article text size, on top of Android's font size as every size here is. */
internal fun TextStyle.sized(size: PreviewTextSize) = copy(fontSize = fontSize * size.percent / 100, lineHeight = lineHeight * size.percent / 100)

/** Colours a page's text is drawn with, from the theme. */
internal class PageColors(val link: Color, val code: Color, val mark: Color, val tint: Color)

@Composable
internal fun pageColors() = MaterialTheme.colorScheme.let { PageColors(it.primary, it.surfaceVariant, it.tertiaryContainer, it.secondaryContainer) }

/**
 * A block's text as drawn: its styles over their words, and [current], the sentence being read,
 * tinted and underlined (on e-ink a tint alone is too faint to follow).
 */
internal fun styledText(block: Block.Text, current: IntRange?, colors: PageColors): AnnotatedString = buildAnnotatedString {
    append(block.shown)
    for (style in block.styles) {
        val span = when (style.kind) {
            StyleKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            StyleKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            StyleKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
            StyleKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            StyleKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.code)
            StyleKind.SUB -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em)
            StyleKind.SUP -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em)
            StyleKind.SMALL -> SpanStyle(fontSize = 0.85.em)
            StyleKind.MARK -> SpanStyle(background = colors.mark)
            StyleKind.LINK -> SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
        }
        addStyle(span, style.start, style.end)
    }
    current?.let { addStyle(SpanStyle(background = colors.tint, textDecoration = TextDecoration.Underline), it.first, it.last + 1) }
}

/** Where a tap at [offset] in [block] leads: a web page, another page of the book, a place on one, or nowhere. */
internal sealed interface LinkTarget {
    data class Web(val url: String) : LinkTarget

    /** The [index]th article of the book: the "Next" line's. */
    data class Page(val index: Int) : LinkTarget

    /** The element with [id]: on this page, or the [page]th article's (a footnote and its way back). */
    data class Anchor(val id: String, val page: Int? = null) : LinkTarget
}

internal fun linkAt(block: Block.Text, offset: Int): LinkTarget? {
    val href = block.styles.firstOrNull { it.kind == StyleKind.LINK && offset >= it.start && offset < it.end }?.href ?: return null
    if (href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true)) return LinkTarget.Web(href)
    if (href.startsWith("#") && href.length > 1) return LinkTarget.Anchor(href.drop(1))
    // EpubWriter names the articles' pages by position.
    val page = ARTICLE_PAGE.matchEntire(href.substringBefore('#'))?.let { it.groupValues[1].toInt() - 1 } ?: return null
    return href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { LinkTarget.Anchor(it, page) } ?: LinkTarget.Page(page)
}

private val ARTICLE_PAGE = Regex("article-(\\d{1,6})\\.xhtml")

/**
 * A run of text: a paragraph, heading, list item with its number, a code block or the page's
 * furniture. A tap on a sentence reads from it, and on a web link opens it.
 */
@Composable
internal fun TextBlock(
    block: Block.Text,
    current: Int?,
    textSize: PreviewTextSize,
    onLayout: (TextLayoutResult) -> Unit,
    /** Reads from the tapped sentence; null while Listen isn't playing this page. */
    onTap: ((Int) -> Unit)?,
    onPage: (LinkTarget.Page) -> Unit = {},
    /** Goes to a footnote or back; null leaves such a link as the text it's in. */
    onAnchor: ((LinkTarget.Anchor) -> Unit)? = null,
    /** Paragraphs justified, as the book has them; not at large sizes, where it opens wide gaps. */
    justify: Boolean = false,
) {
    val typography = MaterialTheme.typography
    // As the book's stylesheet has it: serif throughout, the source in spaced capitals, the byline in italics.
    val style = when {
        block.preformatted -> typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else -> when (block.kind) {
            Kind.KICKER -> typography.bodySmall.copy(letterSpacing = 0.12.em)
            Kind.TITLE -> typography.headlineSmall
            Kind.BYLINE -> typography.bodyMedium.copy(fontStyle = FontStyle.Italic)
            Kind.HEADING -> typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            Kind.META -> typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            Kind.QUOTE, Kind.PARAGRAPH, Kind.ITEM -> typography.bodyLarge
        }.copy(fontFamily = FontFamily.Serif)
    }.sized(textSize)
    val colors = pageColors()
    val ranges = block.ranges
    // Rebuilt only when this block's sentence changes, not for every sentence read elsewhere.
    val text = remember(block, current, colors.tint) {
        styledText(block, ranges.getOrNull(current ?: -1), colors).let { if (block.kind == Kind.KICKER) it.toUpperCase() else it }
    }
    val uri = LocalUriHandler.current
    // The gesture below lives as long as the block; the next article's equal block needs this one's tap.
    val tap by rememberUpdatedState(onTap)
    val page by rememberUpdatedState(onPage)
    val anchor by rememberUpdatedState(onAnchor)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val said = block.sentences.isNotEmpty()
    val indent = when {
        block.kind == Kind.QUOTE -> 16.dp
        block.depth > 0 -> 16.dp * block.depth
        else -> 0.dp
    }
    val bar = MaterialTheme.colorScheme.onBackground
    // A line of furniture that's one link to a page, as the "Next" line is: the whole line is the
    // link, at least a finger tall, not only its words.
    val wholeLine = if (block.kind == Kind.META) block.styles.filter { it.kind == StyleKind.LINK }.singleOrNull()?.let { linkAt(block, it.start) as? LinkTarget.Page } else null
    val outer = Modifier.fillMaxWidth()
        .then(if (wholeLine != null) Modifier.heightIn(min = 48.dp).clickable(onClickLabel = linkLabel(block, wholeLine)) { page(wholeLine) } else Modifier)
        .padding(start = indent, bottom = if (block.kind in setOf(Kind.KICKER, Kind.TITLE, Kind.ITEM)) 4.dp else 12.dp)
        // A quote has a rule down its side, as the book's does.
        .then(if (block.kind == Kind.QUOTE) Modifier.drawBehind { drawRect(bar, size = Size(2.dp.toPx(), size.height)) }.padding(start = 14.dp) else Modifier)
    // On the text itself, so a tap's offset is the text's own, not shifted by a list item's number.
    val modifier = Modifier
        .pointerInput(block) {
            detectTapGestures { offset ->
                val at = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                when (val link = linkAt(block, at)) {
                    is LinkTarget.Web -> runCatching { uri.openUri(link.url) }.also { return@detectTapGestures }
                    is LinkTarget.Page -> page(link).also { return@detectTapGestures }
                    is LinkTarget.Anchor -> anchor?.let { it(link); return@detectTapGestures }
                    null -> wholeLine?.let { page(it); return@detectTapGestures }
                }
                val sentence = ranges.indexOfFirst { at <= it.last + 1 }
                if (sentence >= 0) tap?.invoke(sentence)
            }
        }
        // For TalkBack, the paragraph is one stop that reads from its start, with an action for each link in it.
        .semantics {
            tap?.let { read -> if (said) onClick(label = "Read from here") { read(0); true } }
            if (block.kind == Kind.TITLE || block.kind == Kind.HEADING) heading()
            // Drawn in capitals, read as written: all capitals can be spelled out a letter at a time.
            if (block.kind == Kind.KICKER) contentDescription = block.shown
            val links = block.styles.filter { it.kind == StyleKind.LINK }.mapNotNull { style ->
                when (val target = linkAt(block, style.start)) {
                    is LinkTarget.Web -> CustomAccessibilityAction(linkLabel(block, target, style)) { runCatching { uri.openUri(target.url) }; true }
                    is LinkTarget.Page -> CustomAccessibilityAction(linkLabel(block, target, style)) { page(target); true }
                    is LinkTarget.Anchor -> anchor?.let { go -> CustomAccessibilityAction(linkLabel(block, target, style)) { go(target); true } }
                    null -> null
                }
            }
            if (links.isNotEmpty()) customActions = links
        }
    val align = when {
        justify && block.kind == Kind.PARAGRAPH && !block.preformatted -> TextAlign.Justify
        // The book's end mark, under the article's last line.
        block.kind == Kind.META && block.shown == END_MARK -> TextAlign.Center
        else -> TextAlign.Unspecified
    }
    val drawn = @Composable { m: Modifier -> Text(text, m, style = style, textAlign = align, softWrap = !block.preformatted, onTextLayout = { layout = it; onLayout(it) }) }
    Row(outer) {
        // A number is read out with its item; a bullet would be read as the word "bullet".
        block.marker?.let { marker ->
            val number = marker.replace("•", "").trim()
            Text("$marker ", style = style, modifier = Modifier.clearAndSetSemantics { if (number.isNotEmpty()) contentDescription = number })
        }
        if (block.preformatted) {
            // A code block keeps its lines: it scrolls sideways rather than wrapping.
            Row(Modifier.weight(1f).background(colors.code, MaterialTheme.shapes.small).horizontalScroll(rememberScrollState()).padding(8.dp)) { drawn(modifier) }
        } else {
            drawn(modifier.weight(1f))
        }
    }
}

/**
 * What TalkBack calls a link: "Open *its words*", "Go to *the next article*" without the arrow drawn
 * after it, "Go to note 3" for a footnote's marker, and "Back to the text" for its way back (an
 * arrow or the like, with nothing to read).
 */
internal fun linkLabel(block: Block.Text, target: LinkTarget, style: ListenScript.Style = block.styles.first { it.kind == StyleKind.LINK }): String {
    val words = block.shown.substring(style.start, style.end).replace("→", "").replace('\u00A0', ' ').trim()
    return when (target) {
        is LinkTarget.Web -> "Open $words"
        is LinkTarget.Page -> "Go to $words"
        is LinkTarget.Anchor -> if (words.any(Char::isLetterOrDigit)) "Go to note $words" else "Back to the text"
    }
}

private const val END_MARK = "■"

/** Where a table's rows are drawn, from its top: what the page scrolls by to follow the voice down a long table. */
internal class TableRows {
    var top = 0f
    val rows = mutableMapOf<Int, Float>()

    fun offset(row: Int): Float? = rows[row]?.let { it - top }
}

/** A table past this many cells is drawn as lines of text: laying out thousands of cells freezes the screen. */
internal const val MOST_TABLE_CELLS = 1_500

internal fun Block.Table.tooBig() = rows.sumOf { it.cells.size } > MOST_TABLE_CELLS

/** A block as the text it's drawn as, with its sentences' places: a table too big to draw cell by cell is too. */
internal fun drawnAsText(block: Block): Block.Text? = when (block) {
    is Block.Text -> block
    is Block.Table -> block.takeIf { it.tooBig() }?.asText()
    else -> null
}

/** A table as lines of text, a row a line, as it's said: for one too big to draw cell by cell. */
internal fun Block.Table.asText(): Block.Text {
    val shown = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    for (row in rows.filter { it.sentences.isNotEmpty() }) {
        if (shown.isNotEmpty()) shown.append('\n')
        val at = shown.length
        ListenScript.joinedRanges(row.sentences).forEach { ranges += (at + it.first)..(at + it.last) }
        shown.append(row.sentences.joinToString(" "))
    }
    return Block.Text(kind, said, shown.toString(), ranges)
}

/** A column of a table's row: a cell, a column a cell to its left spans across, or one a cell above spans down. */
internal sealed interface Slot {
    data class Cell(val cell: Block.Table.Cell) : Slot
    data object Across : Slot
    data object Down : Slot
}

/** Each row's columns: a cell spanning down from a row above leaves the columns under it taken. */
internal fun Block.Table.grid(): List<List<Slot>> {
    val below = mutableMapOf<Int, Int>()
    return rows.map { row ->
        val out = mutableListOf<Slot>()
        fun spannedDown() {
            while ((below[out.size] ?: 0) > 0) {
                below[out.size] = below.getValue(out.size) - 1
                out += Slot.Down
            }
        }
        for (cell in row.cells) {
            spannedDown()
            val at = out.size
            out += Slot.Cell(cell)
            repeat(cell.colspan.coerceIn(1, MOST_COLUMNS) - 1) { out += Slot.Across }
            if (cell.rowspan > 1) for (c in at until out.size) below[c] = cell.rowspan.coerceAtMost(MOST_TABLE_CELLS) - 1
        }
        spannedDown()
        out
    }
}

private const val MOST_COLUMNS = 50

/**
 * A table, cell by cell, its header cells bold. It's read a row at a time, so the row being read is
 * tinted and a tap on a row reads from it. Too wide for the screen at this text size, it scrolls
 * sideways rather than wrapping words a letter to a line.
 */
@Composable
internal fun TableBlock(block: Block.Table, current: Int?, textSize: PreviewTextSize, positions: TableRows, onTap: ((Int) -> Unit)?) {
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif).sized(textSize)
    val line = MaterialTheme.colorScheme.outlineVariant
    val tint = MaterialTheme.colorScheme.secondaryContainer
    val grid = remember(block) { block.grid() }
    val columns = grid.maxOfOrNull { it.size } ?: 0
    // Each row's first line within the block: rows with nothing to say have none.
    val firsts = remember(block) { block.rows.runningFold(0) { at, row -> at + row.sentences.size } }
    // About eight letters a column at least.
    val least = with(LocalDensity.current) { style.fontSize.toDp() } * 4 * columns
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).onGloballyPositioned { positions.top = it.positionInRoot().y }) {
        block.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall.sized(textSize), modifier = Modifier.padding(bottom = 4.dp)) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(Modifier.horizontalScroll(rememberScrollState()).width(maxOf(maxWidth, least)).border(1.dp, line)) {
                block.rows.forEachIndexed { r, row ->
                    val reading = current != null && row.sentences.isNotEmpty() && current in firsts[r] until firsts[r + 1]
                    val rowModifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .onGloballyPositioned { positions.rows[r] = it.positionInRoot().y }
                        .then(if (reading) Modifier.background(tint) else Modifier)
                        .then(if (onTap != null && row.sentences.isNotEmpty()) Modifier.clickable(onClickLabel = "Read from here") { onTap(firsts[r]) } else Modifier)
                    if (r > 0) HorizontalDivider(color = line)
                    Row(rowModifier) {
                        grid[r].forEachIndexed { c, slot ->
                            // A cell spanning across is drawn once, over its columns; one spanning down leaves a gap.
                            if (slot == Slot.Across) return@forEachIndexed
                            val cell = (slot as? Slot.Cell)?.cell
                            if (c > 0) Box(Modifier.fillMaxHeight().background(line).padding(start = 1.dp))
                            Text(
                                cell?.shown.orEmpty(),
                                Modifier.weight((cell?.colspan ?: 1).coerceIn(1, MOST_COLUMNS).toFloat()).padding(horizontal = 6.dp, vertical = 4.dp),
                                style = if (cell?.header == true) style.copy(fontWeight = FontWeight.Bold) else style,
                                textDecoration = if (reading) TextDecoration.Underline else null,
                            )
                        }
                        repeat(columns - grid[r].size) {
                            Box(Modifier.fillMaxHeight().background(line).padding(start = 1.dp))
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RuleBlock() {
    HorizontalDivider(Modifier.padding(bottom = 12.dp))
}
