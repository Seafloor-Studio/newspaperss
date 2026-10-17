package com.app.newspaperss.ui.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.listen.Podcasts
import com.app.newspaperss.ui.settings.SettingsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Under Listen, while Kokoro is in use: how much of the edition's podcast is made, as a bar of its
 * articles filled as each is made, or, for an edition without one, **Make the podcast**.
 *
 * @param minutes each article's reading time, in book order.
 */
@Composable
fun PodcastLine(podcasts: Podcasts, editionId: Long, minutes: List<Double>, modifier: Modifier = Modifier) {
    val progress by remember(editionId, minutes.size) { podcasts.observe(editionId, minutes.size) }.collectAsStateWithLifecycle(null)
    val p = progress ?: return
    val scope = rememberCoroutineScope()
    val heard = minutes.map(ListenTime::fromReading)
    val small = MaterialTheme.typography.bodySmall
    val soft = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        if (!p.asked) {
            Text("No audio for this edition yet: it plays in your phone's voice.", style = small, color = soft)
            val making = SettingsSummary.aboutTime(PodcastPace.minutesToMake(minutes.sum().toInt(), p.pace))
            TextButton(onClick = {
                scope.launch {
                    // Asked for and its work started together, even if the page is left meanwhile;
                    // a full disk mustn't take the app down.
                    runCatching { withContext(NonCancellable) { podcasts.request(editionId) } }
                }
            }) { Text("Make the audio · $making while charging") }
            return@Column
        }
        // The text says the same: TalkBack reads that, not the bar.
        Row(Modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            heard.forEachIndexed { i, weight ->
                // An article being made fills as its pieces are kept, every couple of minutes of audio.
                val share = p.share.getOrElse(i) { 0.0 }.toFloat()
                Box(Modifier.weight(weight.toFloat().coerceAtLeast(0.1f)).height(6.dp).background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)) {
                    if (share > 0f) Box(Modifier.fillMaxWidth(share).height(6.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                }
            }
        }
        val made = heard.indices.sumOf { heard[it] * p.share.getOrElse(it) { 0.0 } }
        val anyMade = p.made.any { it }
        val leftOut = p.leftOut.any { it }
        val total = ReadingTime.format(heard.sum())
        // Rounded to whole minutes, nearly done would read as all of it.
        val sofar = if (ReadingTime.format(made) == total) "Almost all of $total made." else "${ReadingTime.format(made)} of $total made."
        val rest = if (leftOut) "The rest plays in your phone's voice until it's made, and articles it left out always do." else "The rest plays in your phone's voice until it's made."
        val done = p.finished || p.made.all { it }
        val text = when {
            p.made.all { it } -> "Audio ready."
            p.finished && !anyMade -> "No audio could be made for this edition, so it plays in your phone's voice."
            p.finished -> "Audio ready. Articles left out, not in English or that couldn't be said, play in your phone's voice."
            p.stopped -> "Stopped. " + (if (made > 0.0) "$sofar " else "") + "The rest plays in your phone's voice."
            p.makingNow -> "Making the audio now. $sofar $rest"
            p.makingOther -> "Waiting while another edition's audio is made: this one comes next. " + (if (made > 0.0) "$sofar " else "") + rest
            made == 0.0 -> "Waiting to make the audio: it's made while the phone charges, and Android can take a few minutes to start. Until then, Listen uses your phone's voice."
            else -> "$sofar It carries on while the phone charges. $rest"
        }
        Text(text, style = small, color = soft, modifier = Modifier.padding(top = 6.dp))
        var deleting by rememberSaveable { mutableStateOf(false) }
        FlowRow {
            when {
                done -> {}
                p.stopped -> {
                    val rest = SettingsSummary.aboutTime(PodcastPace.minutesToMake((heard.sum() - made).toInt().coerceAtLeast(1), p.pace))
                    TextButton(onClick = { scope.launch { runCatching { withContext(NonCancellable) { podcasts.request(editionId) } } } }) {
                        Text("Make the rest · $rest while charging")
                    }
                }
                else -> TextButton(onClick = { scope.launch(Dispatchers.IO) { podcasts.stop(editionId) } }) { Text("Stop making") }
            }
            if (p.bytes > 0) TextButton(onClick = { deleting = true }) { Text("Delete audio · ${megabytes(p.bytes)}") }
        }
        if (deleting) {
            AlertDialog(
                onDismissRequest = { deleting = false },
                title = { Text("Delete this edition's audio?") },
                text = { Text("It frees ${megabytes(p.bytes)}. The edition plays in your phone's voice, and you can make the audio again.") },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = false
                        scope.launch(Dispatchers.IO) { podcasts.delete(editionId) }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
            )
        }
    }
}

/** Whole megabytes, at least 1: a podcast is a few MB, and "0 MB" would say there's nothing to free. */
internal fun megabytes(bytes: Long): String = "${(bytes / 1_000_000).coerceAtLeast(1)} MB"
