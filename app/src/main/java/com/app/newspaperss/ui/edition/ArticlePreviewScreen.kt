package com.app.newspaperss.ui.edition

import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.List
import com.app.newspaperss.ui.listen.Contents
import androidx.compose.foundation.clickable
import com.app.newspaperss.listen.PodcastSpeaker
import com.app.newspaperss.listen.ReadingSpeaker
import com.app.newspaperss.ui.listen.ListenNotes
import com.app.newspaperss.ui.listen.voiceNote
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.newspaperss.listen.ListenPlayer
import com.app.newspaperss.listen.ListenPosition
import com.app.newspaperss.listen.ListenState
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.ui.listen.PlayerDock
import com.app.newspaperss.ui.listen.follow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
    /** Listen's player; while it has [editionId] open, its controls sit under the page and the page follows the voice. */
    player: ListenPlayer? = null,
    editionId: Long? = null,
    podcastVoice: StateFlow<PodcastVoice?> = MutableStateFlow(null),
    instead: StateFlow<PodcastSpeaker.Instead?> = MutableStateFlow(null),
    /** The article with a recording, while it's the one playing; null when there's none. */
    reading: StateFlow<ReadingSpeaker.Now?> = MutableStateFlow(null),
    /** Turns the article back to the voice (true), or to its recording again. */
    onVoiceInstead: (Boolean) -> Unit = {},
) {
    // Read in the background so the screen shows at once: reading a large edition during
    // composition holds up the frame, and the tap that opened it seems not to have registered.
    val book by produceState<Book>(Book.Loading, position) {
        val pages = withContext(Dispatchers.IO) { loadFile()?.let(::EpubPages) }
        try {
            val articles = pages?.let { withContext(Dispatchers.IO) { generateSequence(0) { it + 1 }.takeWhile { at -> it.has("OEBPS/" + EpubPages.articleHref(at)) }.count() } } ?: 0
            // Past the articles is the closing page or an episode after the edition, which only
            // Listen draws: see the page's reading below.
            value = if (pages != null && articles > 0) Book.Open(pages, articles) else Book.Missing
            awaitCancellation()
        } finally {
            pages?.close()
        }
    }
    // The article on screen: the "Next" line turns to the next. Kept across rotation.
    var article by rememberSaveable(position) { mutableIntStateOf(position) }
    val open = book as? Book.Open
    val listenState = player?.state?.collectAsStateWithLifecycle()?.value
    // Listen has this edition's page open: not before its script is read, when its place is still
    // the edition's start.
    val listening = listenState?.takeIf { editionId != null && it.editionId == editionId && it.pages.isNotEmpty() && it.script != null }
    // Opening: not after an error, which the page then says (below), offering Listen again.
    val listenOpening = editionId != null && listenState?.editionId == editionId && listening == null && listenState.error == null
    // The page turns with the voice when the voice moves on, not to wherever it was left: an
    // article opened, or the screen recreated, while Listen is paused elsewhere stays.
    var voiceWasOn by rememberSaveable(position) { mutableStateOf(listening?.at?.page) }
    LaunchedEffect(listening?.at?.page) {
        val page = listening?.at?.page
        if (page != null && page != voiceWasOn) article = page
        voiceWasOn = page
    }
    // While it plays, going to another article takes the voice there, which turns the page; paused,
    // only the page turns.
    val toArticle = { at: Int -> if (listening?.playing == true) player.seek(ListenPosition(at, 0)) else article = at }
    // The page on screen stays, title, note and buttons with it, until the next is read: on e-ink a
    // page turn then redraws once, not blank and again.
    var shown by remember(open) { mutableStateOf<PreviewPage?>(null) }
    var unreadable by remember(open) { mutableStateOf(false) }
    // The closing page and the episodes after the edition aren't in the book's articles: drawn
    // from the voice's own script while it's on one.
    // Read when the effect runs, not when it was keyed: the voice and the page move in the same frame.
    val voiceNow by rememberUpdatedState(listening)
    val openingNow by rememberUpdatedState(listenOpening)
    val voicePageScript = listening?.takeIf { it.at.page >= (open?.articles ?: Int.MAX_VALUE) }?.script
    LaunchedEffect(open, article, voicePageScript) {
        val opened = open ?: return@LaunchedEffect
        if (article >= opened.articles) {
            val voice = voiceNow
            when {
                voice != null && voice.at.page == article ->
                    voice.script?.let { script -> shown = PreviewPage(article, script, null, voice.pages.getOrNull(article)?.title) }
                // On its way to another page past the articles: the page follows it there.
                voice != null && voice.at.page >= opened.articles -> {}
                // Still opening, it waits.
                openingNow -> {}
                // Not Listen's (any more): back to the last article.
                else -> article = opened.articles - 1
            }
            return@LaunchedEffect
        }
        // A saved article the file no longer has, after it was made again, say.
        if (article < 0) {
            article = 0
            return@LaunchedEffect
        }
        val read = withContext(Dispatchers.IO) {
            opened.pages.article(article)?.let { withNextLine(it, EpubPages.articleHref(article), opened.pages) }?.let { xhtml ->
                val heading = Jsoup.parse(xhtml, "", Parser.xmlParser()).selectFirst("h1.article-title")?.text()?.ifEmpty { null }
                PreviewPage(article, parsed(xhtml), articleLink(xhtml), heading)
            }
        }
        unreadable = read == null
        if (read != null) shown = read
    }
    val link = shown?.link
    val shownTitle = shown?.title ?: title
    val context = LocalContext.current
    // The address asked about, so the question stays up across rotation; asked only about the
    // page it was asked on.
    var lookAt by rememberSaveable(position) { mutableStateOf<String?>(null) }
    // Saved open, it can come back after Android ended the app: not over a player with nothing in it.
    var contents by rememberSaveable { mutableStateOf(false) }
    if (contents && listening != null && player != null) {
        // The page goes with the pick even when it's the voice's own article, which wouldn't turn it.
        Contents(listening, onPick = { contents = false; article = it; player.seek(ListenPosition(it, 0)) }, onDismiss = { contents = false })
    }
    // Closed with Listen: left saved, it would open by itself the next time Listen starts.
    LaunchedEffect(listening == null) { if (listening == null) contents = false }
    link?.takeIf { it.url == lookAt }?.let { SendForALookDialog(it, shown?.article?.let(episodeFor), onDismiss = { lookAt = null }) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shownTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // Not while this article is the one Listen has, or Listen is opening it.
                    shown?.takeIf { onListen != null && !listenOpening && listening?.at?.page != it.article }?.let { onScreen ->
                        IconButton(onClick = { onListen?.invoke(onScreen.article) }) { Icon(Icons.Default.Headphones, contentDescription = "Listen from here") }
                    }
                    link?.let { l ->
                        IconButton(onClick = { runCatching { context.startActivity(shareIntent(l, shownTitle)) } }) {
                            Icon(Icons.Default.Share, contentDescription = "Share link")
                        }
                    }
                    // Listen's contents: the voice plays on while it's open, and a pick moves both.
                    if (listening != null) IconButton(onClick = { contents = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents") }
                    textSize?.let { TextSizeMenu(it, onTextSize) }
                    // The page on screen's own source, which following "Next" changes, as Share and
                    // Listen do. The menu itself is there for whichever of the two it has.
                    val onScreen = shown?.article
                    val goTo = onScreen?.let { at -> onOpenSource?.let { sourceAt(at) } }
                    if (link != null || goTo != null) {
                        MoreMenu(
                            onSendForALook = link?.let { { lookAt = it.url } },
                            source = goTo,
                            onOpenSource = goTo?.let { { onScreen?.let { onOpenSource?.invoke(it) } } },
                        )
                    }
                },
            )
        },
        bottomBar = { if (listening != null && player != null) PlayerDock(listening, player, podcastVoice.collectAsStateWithLifecycle().value) },
    ) { padding ->
        when {
            book == Book.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This edition's file is gone, so the article can't be shown.")
            }
            unreadable && shown == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This article can't be read from the edition's file.")
            }
            // Words, not an animated bar, which smears on e-ink.
            open == null || shown == null || textSize == null -> Text("Opening…", Modifier.padding(padding).padding(24.dp))
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                val onScreen = shown!!
                // An error before the page was read, as when the book can't be opened: said here.
                listenState?.takeIf { listening == null && editionId != null && it.editionId == editionId }?.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                }
                listening?.let { state ->
                    val here = state.at.page == onScreen.article
                    val recording by reading.collectAsStateWithLifecycle()
                    val why by instead.collectAsStateWithLifecycle()
                    val playing = state.playing && !state.between
                    val now = recording?.takeIf { here && playing && it.editionId == state.editionId && it.page == state.at.page }
                    ListenNotes(
                        state,
                        voiceNote(why).takeIf { here && playing && (now == null || now.voiceInstead) },
                        now, onVoiceInstead,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    val hint = when {
                        state.finished -> "You've heard the whole edition."
                        // An episode's last block is its time marks, each a tap away while it plays.
                        here && playing && state.pages.getOrNull(state.at.page)?.episode == true &&
                            (state.script?.let { s -> s.lines.count { it.block == s.blocks.lastIndex } } ?: 0) > 1 -> "Tap a time to play from there."
                        else -> null
                    }
                    hint?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                episodeFor(onScreen.article)?.let(::episodeNote)?.let { note ->
                    // Held to four lines: it stays above the article for the whole read.
                    Text(note, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    HorizontalDivider()
                }
                key(onScreen.article) {
                    PreviewPageView(
                        onScreen, open.pages, textSize, link,
                        onArticle = { at -> if (at in 0 until open.articles) toArticle(at) },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        voice = listening?.takeIf { it.at.page == onScreen.article && it.script != null },
                        onReadFrom = { line -> player?.seek(ListenPosition(onScreen.article, line)) },
                    )
                }
            }
        }
    }
}

/**
 * A page read into blocks. One nested too deep for the parser says so, and keeps its "Next" line,
 * so the way on through the paper isn't lost with it.
 */
private fun parsed(xhtml: String): ListenScript = try {
    ListenScript.parse(xhtml)
} catch (_: StackOverflowError) {
    val next = BOOK_NEXT_LINE.find(xhtml)?.value.orEmpty()
    ListenScript.parse("<html><body><p class=\"note\">This article is built too deeply to show here; it's whole in the book.</p>$next</body></html>")
}

/**
 * The article's blocks, its text selectable: the selection menu's **Share** sends the words with
 * the original's address under them, so a quote says where it's from.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PreviewPageView(
    page: PreviewPage,
    pages: EpubPages,
    textSize: PreviewTextSize,
    link: ArticleLink?,
    onArticle: (Int) -> Unit,
    modifier: Modifier,
    /** Listen on this article: its sentence is tinted and followed, and while it plays a tap reads from a sentence. */
    voice: ListenState? = null,
    onReadFrom: (line: Int) -> Unit = {},
) {
    val script = page.script
    // Where the reader was in this article, across rotation and coming back to it.
    val list = rememberSaveable(page.article, saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val selection = rememberSelectionState()
    val currentLink by rememberUpdatedState(link)
    val justify = justifies(textSize, (textSize.percent * LocalDensity.current.fontScale).roundToInt())
    // Kept for the page, so a picture scrolled away and back isn't decoded again and doesn't pop in.
    val decoded = remember(page.article) { mutableMapOf<String, ImageBitmap?>() }
    // Following the voice until the reader moves the page; the chip brings them back.
    var following by rememberSaveable(page.article) { mutableStateOf(true) }
    val goTo = { anchor: LinkTarget.Anchor ->
        if (anchor.page != null && anchor.page != page.article) {
            onArticle(anchor.page)
        } else {
            script.anchors[anchor.id]?.let { at -> following = false; scope.launch { list.scrollToItem(at) } }
        }
        Unit
    }
    // The voice's lines, from the player's own reading of the page. The page drawn here adds a "Next"
    // line at its end, so the blocks before it are the same.
    val spoken = voice?.script
    val line = spoken?.lines?.getOrNull(voice.at.line)?.takeIf { it.block < script.blocks.size }
    val layouts = remember(page.article) { mutableStateMapOf<Int, TextLayoutResult>() }
    val tables = remember(page.article) { mutableMapOf<Int, TableRows>() }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(line, following, textSize) {
        if (!following || line == null) return@LaunchedEffect
        follow(list, script, line, layouts, tables)
    }
    val readFrom: ((Int, Int) -> Unit)? = spoken?.takeIf { voice.playing }?.let { lines ->
        { block, sentence -> lines.lines.indexOfFirst { it.block == block && it.index == sentence }.takeIf { it >= 0 }?.let(onReadFrom) }
    }
    Box(modifier) {
    SelectionContainer(
        selection,
        Modifier.appendTextContextMenuComponents {
            item(ShareQuote, "Share") {
                val quote = quoteOf(selection.selectedTexts.map { it.text })
                close()
                if (quote.isNotEmpty()) runCatching { context.startActivity(quoteIntent(quote, currentLink)) }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            itemsIndexed(script.blocks) { index, block ->
                val current = line?.takeIf { it.block == index }?.index
                val onTap = readFrom?.let { read -> { sentence: Int -> read(index, sentence) } }
                when (block) {
                    is ListenScript.Block.Text -> TextBlock(
                        block, current, textSize, onLayout = { layouts[index] = it }, onTap = onTap,
                        onPage = { target -> target.index?.let(onArticle) },
                        onAnchor = goTo,
                        justify = justify,
                    )
                    is ListenScript.Block.Image -> PreviewImage(block, pages, textSize, decoded, current = current != null, onTap = onTap?.let { { it(0) } })
                    is ListenScript.Block.Table -> if (block.tooBig()) {
                        TextBlock(block.asText(), current, textSize, onLayout = { layouts[index] = it }, onTap = onTap)
                    } else {
                        TableBlock(block, current, textSize, tables.getOrPut(index) { TableRows() }, onTap = onTap ?: {})
                    }
                    ListenScript.Block.Rule -> RuleBlock()
                }
            }
        }
    }
        if (voice != null && !following) {
            TextButton(
                onClick = { following = true; scope.launch { list.scrollToItem(line?.block ?: 0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
            ) { Text("Back to where it's reading", color = MaterialTheme.colorScheme.inverseOnSurface) }
        }
    }
}

private object ShareQuote

/**
 * The words selected, from the page's texts as Compose gives them, one per text drawn: a list item's
 * number is its own, and goes in front of its item rather than on a line of its own.
 */
internal fun quoteOf(texts: List<String>): String = buildString {
    for (text in texts) {
        if (isNotEmpty() && !MARKER.matches(lastLineOf(this))) append('\n')
        append(if (MARKER.matches(text)) text.trimEnd() + " " else text)
    }
}.trim()

private fun lastLineOf(text: CharSequence) = text.substring(text.lastIndexOf('\n') + 1)

// A list item's number or bullet, as drawn in front of it: "3. ", "• ", "1. • ".
private val MARKER = Regex("^(?:\\d+\\.|•)(?: (?:\\d+\\.|•))* ?$")

/**
 * A picture from the book, at its own size, or the width of the page for a large one standing
 * alone (alone in its figure, or an article that's only the picture): a tall strip shrunk to the
 * book's image size would otherwise sit narrow in the middle. Small ones (a headshot, a logo, a
 * row of icons) keep their size, as in the book. Its caption under it.
 */
@Composable
private fun PreviewImage(
    block: ListenScript.Block.Image,
    pages: EpubPages,
    textSize: PreviewTextSize,
    decoded: MutableMap<String, ImageBitmap?>,
    current: Boolean = false,
    /** While Listen plays, a tap reads from the picture's description. */
    onTap: (() -> Unit)? = null,
) {
    val bitmap by produceState(decoded[block.src], block.src) {
        if (block.src in decoded) return@produceState
        value = withContext(Dispatchers.IO) {
            pages.entry("OEBPS/" + block.src)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
        decoded[block.src] = value
    }
    val tap = onTap?.let { Modifier.clickable(onClickLabel = "Read from here", onClick = it) } ?: Modifier
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).then(tap), horizontalAlignment = Alignment.CenterHorizontally) {
        bitmap?.let { image ->
            // At the book's size, a pixel to a dp as a web page has it, and never wider than the page.
            val sized = if (fillsWidth(block, image.width to image.height)) Modifier.fillMaxWidth() else Modifier.widthIn(max = image.width.dp)
            Image(image, contentDescription = block.description, contentScale = ContentScale.FillWidth, modifier = sized)
        }
        block.caption?.let {
            // Tinted and underlined while the voice describes it, as a sentence is.
            Text(
                it,
                style = MaterialTheme.typography.bodySmall.sized(textSize),
                textDecoration = if (current) TextDecoration.Underline else null,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).then(if (current) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
            )
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
