package com.app.newspaperss.ui.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.app.newspaperss.core.plural
import com.app.newspaperss.listen.LISTEN_SPEEDS
import com.app.newspaperss.listen.ListenPlayer
import com.app.newspaperss.listen.ListenPosition
import com.app.newspaperss.listen.ListenState
import com.app.newspaperss.settings.PodcastVoice
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.TextLayoutResult
import com.app.newspaperss.core.listen.ListenScript

/** The player under a page while Listen has its edition: where it is in the edition, and its controls. */
@Composable
fun PlayerDock(state: ListenState, player: ListenPlayer, voice: PodcastVoice?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            val page = state.pages.getOrNull(state.at.page)
            val inPage = state.secondsInPage
            val pageLeft = ((page?.minutes ?: 0.0) * 60 - inPage).coerceAtLeast(0.0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(page?.source ?: state.editionTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // The only sign this article plays from the podcast: its voice's name.
                // Only while it plays: paused, the next article's voice isn't chosen until it starts.
                if (voice != null && state.playing) {
                    Text(
                        voice.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                            .semantics { contentDescription = "Audio, in ${voice.label}'s voice" },
                    )
                }
                // An episode's time left is in the row under the controls, with which episode it is.
                if (page?.end != true && page?.episode != true) Text("${minutes(pageLeft)} left", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
            }
            Chapters(state, Modifier.padding(vertical = 8.dp))
            val episodes = state.pages.indices.filter { state.pages[it].episode }
            // In the edition, the way to the episodes after it, which only Contents showed.
            if (episodes.isNotEmpty() && page?.episode != true) {
                TextButton(
                    onClick = { player.seek(ListenPosition(episodes.first(), 0)) },
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                        .semantics { contentDescription = "Go to the ${plural(episodes.size, "episode")} after the edition" },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) { Text("Then ${plural(episodes.size, "episode")} ›", style = MaterialTheme.typography.labelLarge) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = player::previous) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous article") }
                IconButton(onClick = player::back) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Back a sentence") }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(56.dp)) {
                    when {
                        state.playing -> Icon(Icons.Default.Pause, contentDescription = "Pause")
                        state.finished -> Icon(Icons.Default.PlayArrow, contentDescription = "Play from the start")
                        else -> Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }
                IconButton(onClick = player::forward) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Next sentence") }
                IconButton(onClick = player::next) { Icon(Icons.Default.SkipNext, contentDescription = "Next article") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val speed = speedLabel(state.speed)
                TextButton(
                    onClick = { player.setSpeed(LISTEN_SPEEDS[(LISTEN_SPEEDS.indexOf(state.speed) + 1).mod(LISTEN_SPEEDS.size)]) },
                    modifier = Modifier.semantics { contentDescription = "Speed $speed. Change speed" },
                ) { Text(speed) }
                val total = state.secondsTotal
                // On an episode the edition is all behind: its own times, if its length is known.
                val times = if (page?.episode == true) {
                    val episodes = state.pages.count { it.episode }
                    val which = state.pages.take(state.at.page + 1).count { it.episode }
                    listOfNotNull(
                        "Episode $which of $episodes",
                        if (page.minutes > 0.0) "${minutes(inPage)} in · ${minutes(pageLeft)} left" else null,
                    ).joinToString(" · ")
                } else {
                    "${minutes(state.secondsIn)} in · ${minutes((total - state.secondsIn).coerceAtLeast(0.0))} left"
                }
                Text(times, Modifier.weight(1f, fill = false).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * The edition as one bar, a piece per article sized by its length: its shape, and where you are in
 * it. The episodes after its end follow a gap, a short piece each whatever their length: an hour's
 * episode to scale would flatten the rest.
 */
@Composable
private fun Chapters(state: ListenState, modifier: Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val todo = MaterialTheme.colorScheme.outlineVariant
    // The articles come first, so a piece's index is its page's; the episodes come last.
    val pages = state.pages.filter { !it.end && !it.episode }
    val episodes = state.pages.indices.filter { state.pages[it].episode }
    val description = when {
        state.at.page < pages.size -> "Article ${state.at.page + 1} of ${pages.size}"
        state.at.page in episodes -> "Episode ${episodes.indexOf(state.at.page) + 1} of ${episodes.size}"
        else -> "Past the edition's end"
    }
    @Composable
    fun Piece(index: Int, minutes: Double, modifier: Modifier) {
        // One of unknown length can't fill as it plays: it's shown heard from the start, so the bar still says which is on.
        val heard = index < state.at.page || state.finished || (index == state.at.page && minutes <= 0.0)
        Box(modifier.fillMaxHeight().background(if (heard) done else todo)) {
            if (index == state.at.page && !state.finished && minutes > 0.0) {
                val fraction = (state.secondsInPage / (minutes * 60)).toFloat().coerceIn(0f, 1f)
                if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(done))
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(4.dp).semantics { contentDescription = description }) {
        // At most a quarter of the bar, however many there are, so the articles always have room.
        val episodesWidth = min(20.dp * episodes.size + 3.dp * (episodes.size - 1).coerceAtLeast(0), maxWidth / 4)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            pages.forEachIndexed { i, page -> Piece(i, page.minutes, Modifier.weight(page.minutes.toFloat().coerceAtLeast(0.2f))) }
            if (episodes.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Row(Modifier.width(episodesWidth).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    episodes.forEach { i -> Piece(i, state.pages[i].minutes, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Brings the voice's [line] on a page of [script]'s blocks into view in [list], whose blocks start
 * [before] rows in. A line already on screen leaves it be; one that isn't turns a screen, putting
 * the line a little below the top. On e-ink each scroll redraws the screen, so it moves by whole
 * screens, and jumps rather than animates, which smears there.
 */
internal suspend fun follow(list: LazyListState, script: ListenScript, line: ListenScript.Line, layouts: Map<Int, TextLayoutResult>, tables: Map<Int, TableRows>, before: Int = 0) {
    val block = script.blocks[line.block]
    val text = drawnAsText(block)
    // The line's top and bottom within its block, when its block has been drawn.
    val span = if (text != null) {
        layouts[line.block]?.let { layout ->
            val range = text.ranges[line.index]
            layout.getLineTop(layout.getLineForOffset(range.first)) to layout.getLineBottom(layout.getLineForOffset(range.last))
        }
    } else {
        (block as? ListenScript.Block.Table)?.let { table ->
            val row = table.rows.runningFold(0) { at, r -> at + r.sentences.size }.indexOfLast { it <= line.index }.coerceAtLeast(0)
            tables[line.block]?.offset(row)?.let { it to it }
        }
    }
    // Items' offsets count from inside the content padding, as do the viewport's bounds here.
    val info = list.layoutInfo
    val viewport = info.viewportSize.height
    val shown = info.visibleItemsInfo.firstOrNull { it.index == before + line.block }
    if (shown != null) {
        // A block without lines measured (a picture, or text not drawn yet) is on screen when all of it is.
        val (top, bottom) = span ?: (0f to shown.size.toFloat())
        if (shown.offset + top >= info.viewportStartOffset && shown.offset + bottom <= info.viewportEndOffset) return
    }
    list.scrollToItem(before + line.block)
    val top = span?.first ?: return
    if (top > viewport / 3) list.scrollBy(top - viewport / 4f)
}
