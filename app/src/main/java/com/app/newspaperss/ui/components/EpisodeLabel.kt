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
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.data.EpisodePair

/**
 * What a post is beside podcast episodes, in a few words for a list; null for an ordinary article
 * or one never judged. An article says something only when it's paired, and only once the pair's
 * kind is known: paired with a transcript it isn't the same as paired with its reading.
 */
fun episodeLabel(kind: EpisodeKind?, pair: EpisodePair?): String? = when (kind) {
    EpisodeKind.NOTES -> "Episode notes"
    EpisodeKind.TRANSCRIPT -> pair?.let { "Transcript of “${it.title}”" } ?: "Transcript of an episode"
    EpisodeKind.AUDIO_VERSION -> pair?.let { "Audio version of “${it.title}”" } ?: "Audio version of another post"
    EpisodeKind.ARTICLE -> when (pair?.episodeKind) {
        EpisodeKind.AUDIO_VERSION -> "Has an audio version"
        EpisodeKind.TRANSCRIPT -> "Its transcript is a separate post"
        else -> null
    }
    null -> null
}

/** [episodeLabel] as a sentence for the top of the article, saying what Listen does with it for now. */
fun episodeNote(kind: EpisodeKind?, pair: EpisodePair?): String? {
    val label = episodeLabel(kind, pair) ?: return null
    val what = when (kind) {
        EpisodeKind.NOTES -> "this post comes with a podcast episode."
        EpisodeKind.TRANSCRIPT -> "an episode's words, written out."
        EpisodeKind.AUDIO_VERSION -> "a reading of that post."
        else -> pair?.let { "“${it.title}”." }
    }
    return "$label: $what Listen still reads it aloud for now."
}

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
