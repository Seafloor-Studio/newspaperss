package com.app.newspaperss.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.ReadingTime
import com.app.newspaperss.core.episodes.AudioLength
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EpisodePair
import com.app.newspaperss.settings.Recordings
import kotlin.math.roundToInt

/**
 * What the episode detector made of a post: its kind, and the post it pairs with, if still stored.
 *
 * @param hasAudio whether it has audio of its own, and [pairHasAudio] its pair.
 * @param previewSeconds a transcript's audio, when every recording it has is far too short to be
 *   its episode ([AudioLength.isPreviewOf]): a preview, so Listen reads it.
 * @param recordings which recordings Listen plays, as Settings has them.
 */
data class EpisodeJudgement(
    val kind: EpisodeKind,
    val pair: EpisodePair? = null,
    val hasAudio: Boolean = true,
    val pairHasAudio: Boolean = true,
    val previewSeconds: Int? = null,
    val recordings: Recordings = Recordings(),
)

/** An edition's article as the detector judged it, or null if it never was. */
fun episodeJudgement(content: EditionContent, recordings: Recordings = Recordings()): EpisodeJudgement? {
    val kind = content.episodeKind ?: return null
    // Its own audio, or its notes post's: Listen tries both, so only when both are previews.
    val audio = listOfNotNull(content.audioSeconds, content.pairAudioSeconds.takeIf { content.pair?.episodeKind == EpisodeKind.NOTES })
    // The edition's minutes are its words at the default reading speed, the only one there is.
    val words = (content.entry.minutes * ReadingTime.DEFAULT_WPM).roundToInt()
    val preview = audio.takeIf { kind == EpisodeKind.TRANSCRIPT && it.isNotEmpty() && it.all { s -> AudioLength.isPreviewOf(s, words) } }?.max()
    return EpisodeJudgement(kind, content.pair, content.hasAudio, content.pairHasAudio, preview, recordings)
}

/**
 * A kind's words for a reader: [said] as a reader would say it, after "It's…" in a report of a
 * wrong label, and [label] as a list shows it before naming the post it pairs with.
 */
private class KindWords(val said: String, val label: String)

private fun wordsFor(kind: EpisodeKind) = when (kind) {
    EpisodeKind.ARTICLE -> KindWords("An article", "Article")
    EpisodeKind.NOTES -> KindWords("Episode notes", "Episode notes")
    EpisodeKind.TRANSCRIPT -> KindWords("A transcript", "Transcript")
    EpisodeKind.AUDIO_VERSION -> KindWords("An audio version of another post", "Audio version")
}

fun kindName(kind: EpisodeKind): String = wordsFor(kind).said

fun labelName(kind: EpisodeKind): String = wordsFor(kind).label

/**
 * What a post is beside podcast episodes, in a few words for a list; null for an ordinary article
 * or one never judged. An article says something only when it's paired, and only once the pair's
 * kind is known: paired with a transcript it isn't the same as paired with its reading.
 */
fun episodeLabel(kind: EpisodeKind?, pair: EpisodePair?): String? = when (kind) {
    EpisodeKind.NOTES -> labelName(EpisodeKind.NOTES)
    EpisodeKind.TRANSCRIPT -> pair?.let { "Transcript of “${it.title}”" } ?: "Transcript of an episode"
    EpisodeKind.AUDIO_VERSION -> pair?.let { "Audio version of “${it.title}”" } ?: "Audio version of another post"
    EpisodeKind.ARTICLE -> when (pair?.episodeKind) {
        EpisodeKind.AUDIO_VERSION -> "Has an audio version"
        EpisodeKind.TRANSCRIPT -> "Its transcript is a separate post"
        else -> null
    }
    null -> null
}

/**
 * What the post is and what Listen does with it, for the top of the article, as Listen would with
 * the Settings it has now. Short, and Listen's part early, as it's held to a few lines; a pair's
 * title is shortened for the same reason.
 */
fun episodeNote(judgement: EpisodeJudgement): String? {
    val (kind, pair, hasAudio, pairHasAudio, preview, recordings) = judgement
    val title = pair?.title?.let { "“${shortened(it)}”" }
    return when (kind) {
        EpisodeKind.NOTES ->
            if (hasAudio && recordings.playAtTheEnd) "Episode notes. Listen reads them, then plays the episode after the edition." else "Episode notes. Listen reads them."
        EpisodeKind.TRANSCRIPT -> (title?.let { "Transcript of $it." } ?: "Transcript of an episode.") + " " + when {
            !recordings.playEpisodes -> "Listen reads it."
            // Where Listen looks for the episode: the post's own audio, or its notes post's.
            !hasAudio && !(pairHasAudio && pair?.episodeKind == EpisodeKind.NOTES) -> "No episode audio found, so Listen reads it."
            preview != null -> "Its audio is a ${maxOf(1, (preview / 60.0).roundToInt())}-min preview, so Listen reads it."
            else -> "Listen plays the episode in its place when it can."
        }
        EpisodeKind.AUDIO_VERSION -> (title?.let { "Audio version of $it." } ?: "Audio version of another post.") + " " +
            if (hasAudio && recordings.playReadings) "Listen plays it with that post, when that post is in the edition." else "Listen reads it."
        EpisodeKind.ARTICLE -> when (pair?.episodeKind) {
            EpisodeKind.AUDIO_VERSION -> "Has an audio version, $title." + if (pairHasAudio && recordings.playReadings) " Listen plays it in this post's place." else ""
            EpisodeKind.TRANSCRIPT -> "Its transcript is a separate post, $title."
            else -> null
        }
    }
}

/** [title] cut to the last whole word within [NOTE_TITLE] characters, when it's longer. */
private fun shortened(title: String): String {
    if (title.length <= NOTE_TITLE) return title
    val cut = title.take(NOTE_TITLE).let { if (title[NOTE_TITLE] == ' ') it else it.substringBeforeLast(' ', it) }
    return cut.trimEnd(',', ':', ';', ' ') + "…"
}

private const val NOTE_TITLE = 45

/**
 * [episodeLabel] under an article's title. Outlined rather than filled, so it reads on e-ink,
 * where a tinted background can vanish.
 */
@Composable
fun EpisodeLabelTag(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // A pair's title can be long; the label is a pointer, not the title.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(top = 4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
