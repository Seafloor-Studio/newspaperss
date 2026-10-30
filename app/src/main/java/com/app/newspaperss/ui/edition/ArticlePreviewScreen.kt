package com.app.newspaperss.ui.edition

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.ui.components.EpisodeJudgement
import com.app.newspaperss.ui.components.episodeNote
import com.app.newspaperss.ui.listen.LinkTarget
import com.app.newspaperss.ui.listen.RuleBlock
import com.app.newspaperss.ui.listen.TableBlock
import com.app.newspaperss.ui.listen.TableRows
import com.app.newspaperss.ui.listen.TextBlock
import com.app.newspaperss.ui.listen.asText
import com.app.newspaperss.ui.listen.sized
import com.app.newspaperss.ui.listen.tooBig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import kotlin.math.roundToInt

/** An article's title and the web address of its original, as the book's page gives them. */
internal data class ArticleLink(val title: String, val url: String)

/**
 * The original's address from a page's "Read the original at …" link, or null when the page has
 * none: the book only makes that a link for a web address, so whatever it holds can be shared.
 */
internal fun articleLink(xhtml: String): ArticleLink? {
    val doc = Jsoup.parse(xhtml, "", Parser.xmlParser())
    val url = doc.selectFirst("p.source-link a[href]")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
    return ArticleLink(doc.selectFirst("h1.article-title")?.text().orEmpty(), url)
}

// The book's own line, as EpubWriter writes it after a long article.
private val BOOK_NEXT_LINE = Regex("<p class=\"article-nav\">.*?</p>\n?", RegexOption.DOT_MATCHES_ALL)

/**
 * [xhtml], the book's page at [href], ending in a line naming the next article, or after the last
 * saying the paper ends. The book itself names the next article only after a long one, where an
 * e-reader's page turn isn't enough; in the app, without it the way on is back through the
 * contents. Read from the book's contents page, in its words. A page that isn't an article is
 * returned as it is.
 */
internal fun withNextLine(xhtml: String, href: String, pages: EpubPages): String {
    val contents = pages.entry("OEBPS/contents.xhtml")?.toString(Charsets.UTF_8) ?: return xhtml
    val entries = Jsoup.parse(contents, "", Parser.xmlParser()).select("ol.contents li")
    val at = entries.indexOfFirst { it.selectFirst("a[href]")?.attr("href") == href }
    if (at < 0) return xhtml
    val line = entries.getOrNull(at + 1)?.let { next ->
        val link = next.selectFirst("a[href]") ?: return xhtml
        // The entry's language, for a title in another script; "Next" stays the book's English.
        val language = listOf("lang", "xml:lang", "dir").filter(next::hasAttr).joinToString("") { " $it=\"${escapeXml(next.attr(it))}\"" }
        val meta = next.selectFirst(".meta")?.text()?.ifEmpty { null }?.let { " <span class=\"meta\">· ${escapeXml(it)}</span>" }.orEmpty()
        "<p class=\"article-nav\"><a class=\"next\" href=\"${escapeXml(link.attr("href"))}\">Next: <span class=\"title\"$language>${escapeXml(link.text())}</span>$meta<span aria-hidden=\"true\">&#160;&#8594;</span></a></p>"
    } ?: "<p class=\"article-nav\">${escapeXml(EpubWriter.END_TITLE)}.</p>"
    return xhtml.replace(BOOK_NEXT_LINE, "").replaceFirst("</body>", "$line\n</body>")
}

private fun escapeXml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun shareIntent(link: ArticleLink, fallbackTitle: String): Intent {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, link.title.ifEmpty { fallbackTitle })
        putExtra(Intent.EXTRA_TEXT, link.url)
    }
    return Intent.createChooser(send, null)
}

/** The page on screen: which article, what it holds, and its original's address and title. */
private class PreviewPage(val article: Int, val script: ListenScript, val link: ArticleLink?, val title: String?)

private sealed interface Book {
    data object Loading : Book
    data object Missing : Book
    class Open(val pages: EpubPages, val articles: Int) : Book
}

/**
 * One article of the edition, read straight out of the EPUB and drawn by the app from its page
 * ([ListenScript], as the playing screen draws it): close to the e-reader's page, though each
 * e-reader sets its own fonts and margins. The "Next" line at its end turns to the next article.
 *
 * @param loadFile the edition's file, or null if it's gone. Called off the main thread.
 * @param textSize the preview's text size, on top of Android's font size; null until it's known,
 *   and the page waits for it rather than being laid out twice. The same size as Settings' Article
 *   text size: [onTextSize] is called when the reader picks another with Aa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlePreviewScreen(
    loadFile: suspend () -> File?,
    position: Int,
    title: String,
    onBack: () -> Unit,
    textSize: PreviewTextSize? = PreviewTextSize.DEFAULT,
    onTextSize: (PreviewTextSize) -> Unit = {},
    /** Listens from the start of the article at this position; null offers no Listen. */
    onListen: ((Int) -> Unit)? = null,
    /**
     * The name of where the article at this position came from, for **Go to …**; null for an
     * article with no page to go to, such as one whose source has since been removed.
     */
    sourceAt: (Int) -> String? = { null },
    /** Opens the page of where the article at this position came from; null offers no Go to. */
    onOpenSource: ((Int) -> Unit)? = null,
    /**
     * What the episode detector made of the article at this position, or null if it never judged
     * it: said in a line above the page, and a wrong label can be sent for a look.
     */
    episodeFor: (Int) -> EpisodeJudgement? = { null },
) {
    // Read in the background so the screen shows at once: reading a large edition during
    // composition holds up the frame, and the tap that opened it seems not to have registered.
    val book by produceState<Book>(Book.Loading, position) {
        val pages = withContext(Dispatchers.IO) { loadFile()?.let(::EpubPages) }
        try {
            val articles = pages?.let { withContext(Dispatchers.IO) { generateSequence(0) { it + 1 }.takeWhile { at -> it.has("OEBPS/" + EpubPages.articleHref(at)) }.count() } } ?: 0
            value = if (pages != null && articles > position) Book.Open(pages, articles) else Book.Missing
            awaitCancellation()
        } finally {
            pages?.close()
        }
    }
    // The article on screen: the "Next" line turns to the next. Kept across rotation.
    var article by rememberSaveable(position) { mutableIntStateOf(position) }
    val open = book as? Book.Open
    val page by produceState<PreviewPage?>(null, open, article) {
        val pages = open?.pages ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val href = EpubPages.articleHref(article)
            pages.article(article)?.let { withNextLine(it, href, pages) }?.let { xhtml ->
                val heading = Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("h1.article-title")?.text()?.ifEmpty { null }
                PreviewPage(article, parsed(xhtml), articleLink(xhtml), heading)
            }
        }
    }
    val shown = page?.takeIf { it.article == article }
    val link = shown?.link
    val shownTitle = shown?.title ?: title
    val context = LocalContext.current
    // The address asked about, so the question stays up across rotation; asked only about the
    // page it was asked on.
    var lookAt by rememberSaveable(position) { mutableStateOf<String?>(null) }
    link?.takeIf { it.url == lookAt }?.let { SendForALookDialog(it, episodeFor(article), onDismiss = { lookAt = null }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shownTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (onListen != null && shown != null) {
                        IconButton(onClick = { onListen(article) }) { Icon(Icons.Default.Headphones, contentDescription = "Listen from here") }
                    }
                    link?.let { l ->
                        IconButton(onClick = { runCatching { context.startActivity(shareIntent(l, shownTitle)) } }) {
                            Icon(Icons.Default.Share, contentDescription = "Share link")
                        }
                    }
                    textSize?.let { TextSizeMenu(it, onTextSize) }
                    // The page on screen's own source, which following "Next" changes, as Share and
                    // Listen do. The menu itself is there for whichever of the two it has.
                    val goTo = shown?.let { onOpenSource?.let { sourceAt(article) } }
                    if (link != null || goTo != null) {
                        MoreMenu(
                            onSendForALook = link?.let { { lookAt = it.url } },
                            source = goTo,
                            onOpenSource = goTo?.let { { onOpenSource?.invoke(article) } },
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            book == Book.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This edition's file is gone, so the article can't be shown.")
            }
            // Words, not an animated bar, which smears on e-ink.
            open == null || shown == null || textSize == null -> Text("Opening…", Modifier.padding(padding).padding(24.dp))
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                episodeFor(article)?.let(::episodeNote)?.let { note ->
                    // Held to four lines: it stays above the article for the whole read.
                    Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    HorizontalDivider()
                }
                key(article) {
                    PreviewPageView(
                        shown, open.pages, textSize, link,
                        onArticle = { at -> if (at in 0 until open.articles) article = at },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}

/** A page read into blocks; null-safe for a page the parser can't take (nested too deep). */
private fun parsed(xhtml: String): ListenScript = try {
    ListenScript.parse(xhtml)
} catch (_: StackOverflowError) {
    ListenScript(emptyList())
}

/**
 * The article's blocks, its text selectable: the selection menu's **Share** sends the words with
 * the original's address under them, so a quote says where it's from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewPageView(page: PreviewPage, pages: EpubPages, textSize: PreviewTextSize, link: ArticleLink?, onArticle: (Int) -> Unit, modifier: Modifier) {
    val script = page.script
    // Where the reader was in this article, across rotation and coming back to it.
    val list = rememberSaveable(page.article, saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selection = rememberSelectionState()
    val currentLink by rememberUpdatedState(link)
    val justify = justifies(textSize, (textSize.percent * LocalDensity.current.fontScale).roundToInt())
    val goTo = { anchor: LinkTarget.Anchor ->
        if (anchor.page != null && anchor.page != page.article) {
            onArticle(anchor.page)
        } else {
            script.anchors[anchor.id]?.let { at -> scope.launch { list.scrollToItem(at) } }
        }
        Unit
    }
    SelectionContainer(
        selection,
        Modifier.appendTextContextMenuComponents {
            item(ShareQuote, "Share") {
                val quote = selection.selectedTexts.joinToString("\n\n") { it.text }.trim()
                close()
                if (quote.isNotEmpty()) runCatching { context.startActivity(quoteIntent(quote, currentLink)) }
            }
        },
    ) {
        LazyColumn(modifier, state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            itemsIndexed(script.blocks) { _, block ->
                when (block) {
                    is ListenScript.Block.Text -> TextBlock(
                        block, current = null, textSize, onLayout = {}, onTap = null,
                        onPage = { target -> target.index?.let(onArticle) },
                        onAnchor = goTo,
                        justify = justify,
                    )
                    is ListenScript.Block.Image -> PreviewImage(block, pages, textSize)
                    is ListenScript.Block.Table -> if (block.tooBig()) {
                        TextBlock(block.asText(), current = null, textSize, onLayout = {}, onTap = null)
                    } else {
                        TableBlock(block, current = null, textSize, remember { TableRows() }, onTap = {})
                    }
                    ListenScript.Block.Rule -> RuleBlock()
                }
            }
        }
    }
}

private object ShareQuote

/**
 * A picture from the book, at its own size, or the width of the page for a large one standing
 * alone (alone in its figure, or an article that's only the picture): a tall strip shrunk to the
 * book's image size would otherwise sit narrow in the middle. Small ones (a headshot, a logo, a
 * row of icons) keep their size, as in the book. Its caption under it.
 */
@Composable
private fun PreviewImage(block: ListenScript.Block.Image, pages: EpubPages, textSize: PreviewTextSize) {
    val bitmap by produceState<ImageBitmap?>(null, block.src) {
        value = withContext(Dispatchers.IO) {
            pages.entry("OEBPS/" + block.src)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        bitmap?.let { image ->
            // At the book's size, a pixel to a dp as a web page has it, and never wider than the page.
            val sized = if (fillsWidth(block, image.width to image.height)) Modifier.fillMaxWidth() else Modifier.widthIn(max = image.width.dp)
            Image(image, contentDescription = block.description, contentScale = ContentScale.FillWidth, modifier = sized)
        }
        block.caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.sized(textSize), modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Whether a picture of [size] (width, height in pixels) fills the page's width: see [PreviewImage]. */
internal fun fillsWidth(block: ListenScript.Block.Image, size: Pair<Int, Int>?): Boolean =
    block.standalone && size != null && (size.first >= FILL_MIN_WIDTH || size.second >= FILL_MIN_HEIGHT)

// Wide enough to be the picture rather than an icon, or a strip shrunk by the book's 1200px limit.
private const val FILL_MIN_WIDTH = 240
private const val FILL_MIN_HEIGHT = 800

@Composable
private fun TextSizeMenu(textSize: PreviewTextSize, onTextSize: (PreviewTextSize) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Text size" }) { Text("Aa") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (size in PreviewTextSize.entries) {
                DropdownMenuItem(
                    text = { Text(size.label) },
                    onClick = { open = false; onTextSize(size) },
                    // A tick, not only a tint, so the current size shows on e-ink.
                    trailingIcon = if (size == textSize) { { Icon(Icons.Default.Check, contentDescription = "Current size") } } else null,
                )
            }
        }
    }
}

/** @param source the name of where the article came from, for **Go to …**; null leaves it out. */
@Composable
private fun MoreMenu(onSendForALook: (() -> Unit)?, source: String?, onOpenSource: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // First: from an article in the paper, its feed's page is the way to its cap, its
            // article text, leaving it out, or marking this one unread.
            if (source != null && onOpenSource != null) {
                DropdownMenuItem(text = { Text("Go to $source") }, onClick = { open = false; onOpenSource() })
            }
            if (onSendForALook != null) {
                DropdownMenuItem(text = { Text("Send for a look…") }, onClick = { open = false; onSendForALook() })
            }
        }
    }
}

/**
 * Whether the preview justifies paragraphs, as the book does. At Larger and up, or the same size
 * reached through Android's font size, a narrow phone fits a few words a line and justifying
 * opens wide gaps between them, so paragraphs are left-aligned instead.
 */
internal fun justifies(textSize: PreviewTextSize, textZoom: Int): Boolean =
    textSize < PreviewTextSize.LARGER && textZoom < PreviewTextSize.LARGER.percent
