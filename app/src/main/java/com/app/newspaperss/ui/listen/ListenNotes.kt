package com.app.newspaperss.ui.listen

import com.app.newspaperss.listen.ReadingSpeaker
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.app.newspaperss.listen.PodcastSpeaker
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.listen.ListenState
import java.util.Locale
import kotlin.math.roundToInt


/** One quiet line where the voice changes; nothing is said aloud about it. */
internal fun voiceNote(why: PodcastSpeaker.Instead?): String? = when (why) {
    PodcastSpeaker.Instead.NOT_MADE_YET -> "From here, your phone's voice: the audio hasn't reached this article yet."
    PodcastSpeaker.Instead.LEFT_OUT -> "This article plays in your phone's voice: it was left out of the audio, as it's not in English or couldn't be made."
    null -> null
}

/**
 * What Listen has to say above the page it's reading: the recording playing in the article's
 * place and the switch to the voice, the natural voice's note, a missing voice for the page's
 * language, and an error. Shown above the article view's page.
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
