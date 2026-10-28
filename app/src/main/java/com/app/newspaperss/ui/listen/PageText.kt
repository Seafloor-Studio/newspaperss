package com.app.newspaperss.ui.listen

import androidx.compose.foundation.background
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

/** Where a tap at [offset] in [block] leads: a web page, another page of the book, or nowhere. */
internal sealed interface LinkTarget {
    data class Web(val url: String) : LinkTarget

    /** The [index]th page of the book: the "Next" line's article, or the closing page if null. */
    data class Page(val index: Int?) : LinkTarget
}

internal fun linkAt(block: Block.Text, offset: Int): LinkTarget? {
    val href = block.styles.firstOrNull { it.kind == StyleKind.LINK && offset >= it.start && offset < it.end }?.href ?: return null
    if (href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true)) return LinkTarget.Web(href)
    // EpubWriter names the articles' pages by position; a footnote's link (with a #) stays on the page.
    if (href == "end.xhtml") return LinkTarget.Page(null)
    return ARTICLE_PAGE.matchEntire(href)?.let { LinkTarget.Page(it.groupValues[1].toInt() - 1) }
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
    onTap: (Int) -> Unit,
    onPage: (LinkTarget.Page) -> Unit = {},
) {
    val typography = MaterialTheme.typography
    val style = when {
        block.preformatted -> typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else -> when (block.kind) {
            Kind.KICKER -> typography.labelLarge
            Kind.TITLE -> typography.headlineSmall
            Kind.BYLINE -> typography.bodySmall
            Kind.HEADING -> typography.titleMedium
            Kind.QUOTE -> typography.bodyLarge.copy(fontStyle = FontStyle.Italic)
            Kind.META -> typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            Kind.PARAGRAPH, Kind.ITEM -> typography.bodyLarge
        }
    }.sized(textSize)
    val colors = pageColors()
    val ranges = block.ranges
    // Rebuilt only when this block's sentence changes, not for every sentence read elsewhere.
    val text = remember(block, current, colors.tint) { styledText(block, ranges.getOrNull(current ?: -1), colors) }
    val uri = LocalUriHandler.current
    // The gesture below lives as long as the block; the next article's equal block needs this one's tap.
    val tap by rememberUpdatedState(onTap)
    val page by rememberUpdatedState(onPage)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val said = block.sentences.isNotEmpty()
    val indent = when {
        block.kind == Kind.QUOTE -> 16.dp
        block.depth > 0 -> 16.dp * block.depth
        else -> 0.dp
    }
    val outer = Modifier.fillMaxWidth().padding(start = indent, bottom = if (block.kind == Kind.KICKER || block.kind == Kind.TITLE) 4.dp else 12.dp)
    // On the text itself, so a tap's offset is the text's own, not shifted by a list item's number.
    val modifier = Modifier
        .pointerInput(block) {
            detectTapGestures { offset ->
                val at = layout?.getOffsetForPosition(offset) ?: return@detectTapGestures
                when (val link = linkAt(block, at)) {
                    is LinkTarget.Web -> runCatching { uri.openUri(link.url) }.also { return@detectTapGestures }
                    is LinkTarget.Page -> page(link).also { return@detectTapGestures }
                    null -> {}
                }
                val sentence = ranges.indexOfFirst { at <= it.last + 1 }
                if (sentence >= 0) tap(sentence)
            }
        }
        // For TalkBack, the paragraph is one stop that reads from its start.
        .semantics {
            if (said) onClick(label = "Read from here") { tap(0); true }
            if (block.kind == Kind.TITLE || block.kind == Kind.HEADING) heading()
        }
    val drawn = @Composable { m: Modifier -> Text(text, m, style = style, softWrap = !block.preformatted, onTextLayout = { layout = it; onLayout(it) }) }
    Row(outer) {
        // A number is read out with its item; a bullet would be read as the word "bullet".
        block.marker?.let { Text("$it ", style = style, modifier = if (it.any(Char::isDigit)) Modifier else Modifier.clearAndSetSemantics {}) }
        if (block.preformatted) {
            // A code block keeps its lines: it scrolls sideways rather than wrapping.
            Row(Modifier.weight(1f).background(colors.code, MaterialTheme.shapes.small).horizontalScroll(rememberScrollState()).padding(8.dp)) { drawn(modifier) }
        } else {
            drawn(modifier.weight(1f))
        }
    }
}

/**
 * A table, cell by cell, its header cells bold. It's read a row at a time, so the row being read is
 * tinted and a tap on a row reads from it.
 */
@Composable
internal fun TableBlock(block: Block.Table, current: Int?, textSize: PreviewTextSize, onTap: (Int) -> Unit) {
    val style = MaterialTheme.typography.bodyMedium.sized(textSize)
    val line = MaterialTheme.colorScheme.outlineVariant
    val tint = MaterialTheme.colorScheme.secondaryContainer
    // Each row's first line within the block: rows with nothing to say have none.
    val firsts = block.rows.runningFold(0) { at, row -> at + row.sentences.size }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        block.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall.sized(textSize), modifier = Modifier.padding(bottom = 4.dp)) }
        Column(Modifier.fillMaxWidth().border(1.dp, line)) {
            block.rows.forEachIndexed { r, row ->
                val reading = current != null && row.sentences.isNotEmpty() && current in firsts[r] until firsts[r + 1]
                val rowModifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .then(if (reading) Modifier.background(tint) else Modifier)
                    .then(if (row.sentences.isNotEmpty()) Modifier.clickable(onClickLabel = "Read from here") { onTap(firsts[r]) } else Modifier)
                if (r > 0) HorizontalDivider(color = line)
                Row(rowModifier) {
                    row.cells.forEachIndexed { c, cell ->
                        if (c > 0) Box(Modifier.fillMaxHeight().background(line).padding(start = 1.dp))
                        Text(
                            cell.shown,
                            Modifier.weight(cell.colspan.coerceAtLeast(1).toFloat()).padding(horizontal = 6.dp, vertical = 4.dp),
                            style = if (cell.header) style.copy(fontWeight = FontWeight.Bold) else style,
                            textDecoration = if (reading) TextDecoration.Underline else null,
                        )
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
