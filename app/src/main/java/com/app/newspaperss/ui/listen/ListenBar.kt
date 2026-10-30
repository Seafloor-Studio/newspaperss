package com.app.newspaperss.ui.listen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.newspaperss.listen.ListenState

/**
 * Whether the bar shows: Listen has an edition open, or is opening one, and hasn't heard it to its
 * end. Kept while opening so that switching editions doesn't blink it away. Not after an error,
 * which only the article view explains: the bar's Play would only meet it again.
 */
fun showsListenBar(state: ListenState?): Boolean =
    state != null && state.editionId != null && (state.loading || state.pages.isNotEmpty()) && !state.finished && state.error == null

/**
 * Listen, along the bottom of the app while an edition is open in it, playing or paused: what it's
 * reading, play and pause, a tap to open the article it's on, and [onClose] to stop listening, which
 * puts the bar away (where it got to is kept). No progress bar: on e-ink every redraw is a flash.
 * While the edition opens, Listen knows nothing of it yet but its id, so the bar says only that,
 * with a still hourglass rather than a spinner for the same reason.
 */
@Composable
fun ListenBar(state: ListenState, onOpen: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val page = state.pages.getOrNull(state.at.page)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open the player", onClick = onOpen)
                    .heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.loading) {
                    Text("Opening the edition…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        listOfNotNull(if (state.playing) "Listening" else "Paused", page?.source).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(page?.title ?: state.editionTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!state.loading) IconButton(onClick = onToggle) {
                    if (state.playing) Icon(Icons.Default.Pause, contentDescription = "Pause") else Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Stop listening") }
            }
        }
    }
}
