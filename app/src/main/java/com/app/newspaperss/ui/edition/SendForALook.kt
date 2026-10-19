package com.app.newspaperss.ui.edition

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.ui.components.EpisodeJudgement
import com.app.newspaperss.ui.components.kindName
import com.app.newspaperss.ui.components.labelName
import com.app.newspaperss.ui.components.openInBrowser

private const val NEW_ISSUE = "https://github.com/Seafloor-Studio/newspaperss/issues/new"

// GitHub's form takes its fields from the address, and refuses one much past 8,000 characters
// once encoded: a CJK character takes 9, and an address already percent-encoded triples.
private const val MAX_TITLE = 120
private const val MAX_FORM = 8_000

/** [title], or the address's host without one, cut to [MAX_TITLE] whole characters so an emoji isn't cut in half. */
private fun issueTitle(link: ArticleLink): String = link.title.ifBlank { Uri.parse(link.url).host.orEmpty() }.let { t ->
    if (t.codePointCount(0, t.length) > MAX_TITLE) t.substring(0, t.offsetByCodePoints(0, MAX_TITLE - 1)).trimEnd() + "…" else t
}

/** GitHub's form filled in, or with the address left for the reader to paste if it would make the form too long. */
private fun issueForm(link: ArticleLink, title: String, label: String, body: (address: String) -> String): String {
    fun form(address: String) = Uri.parse(NEW_ISSUE).buildUpon()
        .appendQueryParameter("title", title)
        .appendQueryParameter("labels", label)
        .appendQueryParameter("body", body(address))
        .build().toString()
    return form(link.url).takeIf { it.length <= MAX_FORM } ?: form("(too long to fill in: please paste it here)")
}

/**
 * The address of GitHub's new-issue form, filled in for the daily issue check to compare the
 * article in the book with its page. The `extraction` label is asked for too, though GitHub keeps
 * it only for people who can label this repository's issues; the title's "Extraction:" is what
 * marks it for everyone else.
 */
internal fun lookIssueUrl(link: ArticleLink, appVersion: String): String =
    issueForm(link, "Extraction: ${issueTitle(link)}", "extraction") { address ->
        """
            |Article: $address
            |App version: $appVersion
            |
            |What looks wrong (optional; a missing picture, text that shouldn't be there, the article cut short):
            |
        """.trimMargin()
    }

/**
 * The form for a post the episode detector labelled wrongly: what it [got] and what the reader
 * says it [is], by the kinds' stored names, so the post can become a test case of the episode
 * corpus. The "Episode label:" title marks it, as "Extraction:" does the other.
 */
internal fun labelIssueUrl(link: ArticleLink, got: EpisodeKind, `is`: EpisodeKind, appVersion: String): String =
    issueForm(link, "Episode label: ${issueTitle(link)}", "episodes") { address ->
        """
            |Post: $address
            |Labelled: ${got.label}
            |Should be: ${`is`.label}
            |App version: $appVersion
            |
            |Anything else (optional):
            |
        """.trimMargin()
    }

/**
 * Asks before opening an issue form in the browser, saying first that the issue is public. For a
 * post the episode detector judged ([judgement]), the reader picks why: the article came out wrong
 * ([lookIssueUrl]), or its label is wrong and what it is instead ([labelIssueUrl]). Kept across
 * rotation, as is its answer when no browser could take the form.
 */
@Composable
internal fun SendForALookDialog(link: ArticleLink, judgement: EpisodeJudgement?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var noBrowser by rememberSaveable(link.url) { mutableStateOf(false) }
    var aboutLabel by rememberSaveable(link.url) { mutableStateOf(false) }
    var instead by rememberSaveable(link.url) { mutableStateOf<EpisodeKind?>(null) }
    val label = judgement?.let { labelName(it.kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (judgement == null) "Send this article for a look?" else "What's wrong with it?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (judgement != null) {
                    Column(Modifier.selectableGroup()) {
                        Choice("The article came out wrong", selected = !aboutLabel) { aboutLabel = false }
                        // An ordinary article has no label on screen: reporting it is reporting a missed episode.
                        Choice(
                            if (judgement.kind == EpisodeKind.ARTICLE) "It's taken for an ordinary article, but it's…" else "It's labelled “$label”, and it's…",
                            selected = aboutLabel,
                        ) { aboutLabel = true }
                    }
                    if (aboutLabel) {
                        Column(Modifier.padding(start = 32.dp).selectableGroup()) {
                            EpisodeKind.entries.filter { it != judgement.kind }.forEach { kind ->
                                Choice(kindName(kind), selected = instead == kind) { instead = kind }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (judgement != null && aboutLabel) {
                    Text("Anyone can see what you send: it becomes a public issue on GitHub, with this post's address, the label it got, the one you pick and the app's version. Nothing else from your app is sent.")
                } else {
                    Text("Anyone can see what you send: it becomes a public issue on GitHub, with this article's address and the app's version.")
                    Spacer(Modifier.height(8.dp))
                    Text("Your browser opens GitHub's form, filled in. You'll need a GitHub account to send it, and you can add what looks wrong. The app's page is then checked against the original, and what it got wrong is fixed.")
                }
                if (noBrowser) {
                    Spacer(Modifier.height(8.dp))
                    Text("There's no browser on this device to open the form in.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            val picked = instead
            TextButton(
                // A wrong label is sent only once the reader has said what it is.
                enabled = judgement == null || !aboutLabel || picked != null,
                onClick = {
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                    val url = if (judgement != null && aboutLabel && picked != null) labelIssueUrl(link, judgement.kind, picked, version) else lookIssueUrl(link, version)
                    if (openInBrowser(context, url)) onDismiss() else noBrowser = true
                },
            ) { Text("Open GitHub") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One radio choice, the whole row its touch target. */
@Composable
private fun Choice(text: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}
