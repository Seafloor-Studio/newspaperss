package com.app.newspaperss.ui.listen

import com.app.newspaperss.listen.ReadingSpeaker
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.app.newspaperss.core.plural
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.border
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.listen.PodcastSpeaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.BoxWithConstraints
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenScript.Block
import com.app.newspaperss.core.listen.ListenScript.Kind
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.listen.LISTEN_SPEEDS
import com.app.newspaperss.listen.ListenPlayer
import com.app.newspaperss.listen.ListenPosition
import com.app.newspaperss.listen.ListenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt


/**
 * What's playing: the page being read, its sentence tinted and the page following the voice, with
 * the player under it. Take the phone out of a pocket and this is where it is: the text to read
 * along, the picture being described, the sentence that didn't land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    player: ListenPlayer,
    onBack: () -> Unit,
    podcastVoice: StateFlow<PodcastVoice?> = MutableStateFlow(null),
    instead: StateFlow<PodcastSpeaker.Instead?> = MutableStateFlow(null),
    /** Settings › Article text size: the article scaled by the same share as the preview's text. */
    textSize: PreviewTextSize = PreviewTextSize.DEFAULT,
    /** The article with a recording, while it's the one playing; null when there's none. */
    reading: StateFlow<ReadingSpeaker.Now?> = MutableStateFlow(null),
    /** Turns the article back to the voice (true), or to its recording again. */
    onVoiceInstead: (Boolean) -> Unit = {},
) {
    val state by player.state.collectAsStateWithLifecycle()
    val recording by reading.collectAsStateWithLifecycle()
    val voice by podcastVoice.collectAsStateWithLifecycle()
    val why by instead.collectAsStateWithLifecycle()
    val voiceNote = voiceNote(why)
    var contents by rememberSaveable { mutableStateOf(false) }
    val onPage = state.pages.getOrNull(state.at.page)
    val title = when {
        state.pages.isEmpty() -> "Listening"
        onPage?.end == true -> "Listening · the end"
        onPage?.episode == true -> "Listening · episode ${state.pages.take(state.at.page + 1).count { it.episode }} of ${state.pages.count { it.episode }}"
        else -> "Listening · ${state.at.page + 1} of ${state.pages.count { !it.end && !it.episode }}"
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (state.pages.isNotEmpty()) {
                        IconButton(onClick = { contents = true }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Contents") }
                    }
                },
            )
        },
        bottomBar = { if (state.pages.isNotEmpty()) PlayerDock(state, player, voice) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val script = state.script
            when {
                state.error != null && script == null -> Message(state.error!!)
                state.editionId == null -> Message("Nothing is playing. Open an edition and tap Listen.")
                script == null -> Message("Opening…")
                // Only while playing, as the voice's name: paused, the next article's voice isn't chosen yet.
                else -> {
                    val playing = state.playing && !state.between
                    val here = recording?.takeIf { playing && it.editionId == state.editionId && it.page == state.at.page }
                    // While a recording plays, the podcast's note about the voice is about nothing heard.
                    Page(state, script, player, voiceNote.takeIf { playing && (here == null || here.voiceInstead) }, textSize, here, onVoiceInstead)
                }
            }
        }
    }
    // Not over an empty player: saved open, it can come back after Android ended the app.
    if (contents && state.pages.isNotEmpty()) Contents(state, onPick = { contents = false; player.seek(ListenPosition(it, 0)) }, onDismiss = { contents = false })
}

/** One quiet line where the voice changes; nothing is said aloud about it. */
internal fun voiceNote(why: PodcastSpeaker.Instead?): String? = when (why) {
    PodcastSpeaker.Instead.NOT_MADE_YET -> "From here, your phone's voice: the audio hasn't reached this article yet."
    PodcastSpeaker.Instead.LEFT_OUT -> "This article plays in your phone's voice: it was left out of the audio, as it's not in English or couldn't be made."
    null -> null
}

/**
 * What Listen has to say above the page it's reading: the recording playing in the article's
 * place and the switch to the voice, the natural voice's note, a missing voice for the page's
 * language, and an error. Shown by the playing screen and the article preview alike.
 */
@Composable
internal fun ListenNotes(state: ListenState, voiceNote: String?, recording: ReadingSpeaker.Now?, onVoiceInstead: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    if (recording == null && voiceNote == null && state.missingLanguage == null && state.error == null) return
    Column(modifier) {
        recording?.let { RecordingNote(it, onVoiceInstead) }
        voiceNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        state.missingLanguage?.let { MissingVoice(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Page(
    state: ListenState,
    script: ListenScript,
    player: ListenPlayer,
    voiceNote: String?,
    textSize: PreviewTextSize,
    recording: ReadingSpeaker.Now? = null,
    onVoiceInstead: (Boolean) -> Unit = {},
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Following the voice until the reader scrolls away; the chip brings them back.
    var following by remember(state.editionId, state.at.page) { mutableStateOf(true) }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    val layouts = remember(script) { mutableStateMapOf<Int, TextLayoutResult>() }
    val tables = remember(script) { mutableMapOf<Int, TableRows>() }
    // Notes above the article take the first rows.
    val notes = listOfNotNull(recording, voiceNote, state.missingLanguage, state.error).size
    val line = script.lines.getOrNull(state.at.line)
    // Jumps, not animated scrolls: animation smears on e-ink.
    // Keyed on the voice's note too, which comes and goes with play and pause and moves every row;
    // not on an error's, which would scroll the reason out of sight.
    // And on the size: one that changes reflows every block and moves the sentence.
    LaunchedEffect(line, following, script, voiceNote != null, recording != null, textSize) {
        if (!following || line == null) return@LaunchedEffect
        follow(list, script, line, layouts, tables, before = notes)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            recording?.let { item { RecordingNote(it, onVoiceInstead) } }
            voiceNote?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            state.missingLanguage?.let { tag -> item { MissingVoice(tag) } }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) } }
            val marks = if (state.pages.getOrNull(state.at.page)?.episode == true) script.blocks.indexOfFirst { it is Block.Text && it.kind == Kind.PARAGRAPH } else -1
            itemsIndexed(script.blocks) { index, block ->
                val current = line?.block == index
                // Under the row before the marks, the heading's: the rows the page follows by stay the
                // script's blocks, and the marks' own row starts at its first mark, as the voice's place does.
                Column {
                    val readFrom = { sentence: Int -> player.seek(ListenPosition(state.at.page, script.lines.indexOfFirst { it.block == index && it.index == sentence })) }
                    when (block) {
                        is Block.Image -> ImageBlock(block, current, player, textSize) { readFrom(0) }
                        is Block.Text -> TextBlock(
                            block, if (current) line!!.index else null, textSize, onLayout = { layouts[index] = it }, onTap = readFrom,
                            // The "Next" line: the voice goes on to that article, as ⏭ does.
                            onPage = { target -> player.seek(ListenPosition(target.index ?: state.pages.indexOfFirst { it.end }, 0)) },
                        )
                        is Block.Table -> if (block.tooBig()) {
                            TextBlock(block.asText(), if (current) line!!.index else null, textSize, onLayout = { layouts[index] = it }, onTap = readFrom)
                        } else {
                            TableBlock(block, if (current) line!!.index else null, textSize, tables.getOrPut(index) { TableRows() }, onTap = readFrom)
                        }
                        Block.Rule -> RuleBlock()
                    }
                    if (index == marks - 1 && script.lines.count { it.block == marks } > 1) {
                        Text(
                            "Tap a time to play from there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            }
            if (state.finished) {
                item { Text("You've heard the whole edition.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp)) }
            }
        }
        if (!following) {
            TextButton(
                onClick = { following = true; scope.launch { list.scrollToItem(notes + (line?.block ?: 0)) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface, CircleShape),
            ) { Text("Back to where it's reading", color = MaterialTheme.colorScheme.inverseOnSurface) }
        }
    }
}

@Composable
private fun ImageBlock(block: Block.Image, current: Boolean, player: ListenPlayer, textSize: PreviewTextSize, onTap: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, block.src) {
        value = withContext(Dispatchers.IO) {
            player.image(block.src)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
        }
    }
    val outline = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClickLabel = "Read from here", onClick = onTap),
    ) {
        bitmap?.let {
            val frame = if (current) Modifier.background(outline).padding(3.dp) else Modifier
            // The caption under it says what it is.
            Image(it, contentDescription = null, modifier = frame.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit)
        }
        val caption = block.description ?: "An image without a description"
        val captionStyle = MaterialTheme.typography.bodySmall.sized(textSize)
        Text(
            caption,
            style = captionStyle,
            modifier = Modifier.padding(top = 4.dp).then(if (current) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
            textDecoration = if (current) TextDecoration.Underline else null,
        )
    }
}

/** The page's language has no voice on this phone: Android can download one. */
@Composable
internal fun MissingVoice(tag: String) {
    val context = LocalContext.current
    val language = Locale.forLanguageTag(tag).getDisplayLanguage(LocalConfiguration.current.locales[0]).ifEmpty { tag }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("This article is in $language, and your phone has no $language voice, so it's read in your phone's own.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                try {
                    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Get a $language voice") }
        }
    }
}

@Composable
internal fun Contents(state: ListenState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.editionTitle) },
        text = {
            LazyColumn {
                itemsIndexed(state.pages) { i, page ->
                    val here = i == state.at.page
                    Column(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Listen", onClick = { onPick(i) }).padding(vertical = 10.dp)
                            .semantics(mergeDescendants = true) {},
                    ) {
                        Text(
                            (if (here) "▶ " else "") + page.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        // An episode has its notes' title, so it says what it is; its length may be unknown.
                        val length = minutes(page.minutes * 60).takeUnless { page.end || (page.episode && page.minutes == 0.0) }
                        val details = listOfNotNull("Episode".takeIf { page.episode }, page.source, length, "Playing".takeIf { here }).joinToString(" · ")
                        if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal fun speedLabel(speed: Float): String = (if (speed == speed.roundToInt().toFloat()) speed.roundToInt().toString() else speed.toString()) + "×"

internal fun minutes(seconds: Double): String = ReadingTime.format(seconds / 60)

/**
 * The article plays its recording, or the reader turned it back to the voice: which, and the
 * switch to the other. Where the voice's note goes, and like it, quiet.
 */
@Composable
internal fun RecordingNote(now: ReadingSpeaker.Now, onVoiceInstead: (Boolean) -> Unit) {
    val minutes = now.reading.seconds?.let { " · ${ReadingTime.format(it / 60.0)}" }.orEmpty()
    val what = if (now.reading.episode) "its episode" else "its recorded reading"
    val has = if (now.reading.episode) "it has its episode" else "it has a recorded reading"
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp),
    ) {
        Text(
            if (now.voiceInstead) "Read by the voice: $has$minutes." else "Playing $what$minutes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // An episode after the edition has nothing for the voice to read instead.
        if (!now.reading.atTheEnd) {
            TextButton(onClick = { onVoiceInstead(!now.voiceInstead) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (now.voiceInstead) "Play $what" else "Have the voice read it instead")
            }
        }
    }
}
