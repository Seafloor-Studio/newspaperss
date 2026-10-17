package com.app.newspaperss.ui.edition

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.newspaperss.ui.components.openInBrowser

private const val NEW_ISSUE = "https://github.com/Seafloor-Studio/newspaperss/issues/new"

// GitHub's form takes its fields from the address, and refuses one much past 8,000 characters
// once encoded: a CJK character takes 9, and an address already percent-encoded triples.
private const val MAX_TITLE = 120
private const val MAX_FORM = 8_000

/**
 * The address of GitHub's new-issue form, filled in for the daily issue check to compare the
 * article in the book with its page. The `extraction` label is asked for too, though GitHub keeps
 * it only for people who can label this repository's issues; the title's "Extraction:" is what
 * marks it for everyone else. An article address that would make the form too long is left for
 * the reader to paste.
 */
internal fun lookIssueUrl(link: ArticleLink, appVersion: String): String {
    val title = link.title.ifBlank { Uri.parse(link.url).host.orEmpty() }.let { t ->
        // By whole characters, so an emoji isn't cut in half.
        if (t.codePointCount(0, t.length) > MAX_TITLE) t.substring(0, t.offsetByCodePoints(0, MAX_TITLE - 1)).trimEnd() + "…" else t
    }
    fun form(address: String) = Uri.parse(NEW_ISSUE).buildUpon()
        .appendQueryParameter("title", "Extraction: $title")
        .appendQueryParameter("labels", "extraction")
        .appendQueryParameter("body", """
            |Article: $address
            |App version: $appVersion
            |
            |What looks wrong (optional; a missing picture, text that shouldn't be there, the article cut short):
            |
        """.trimMargin())
        .build().toString()
    return form(link.url).takeIf { it.length <= MAX_FORM } ?: form("(too long to fill in: please paste it here)")
}

/**
 * Asks before opening [lookIssueUrl] in the browser, saying first that the issue is public. Kept
 * across rotation, as is its answer when no browser could take the form.
 */
@Composable
internal fun SendForALookDialog(link: ArticleLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var noBrowser by rememberSaveable(link.url) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send this article for a look?") },
        text = {
            Column {
                Text("Anyone can see what you send: it becomes a public issue on GitHub, with this article's address and the app's version.")
                Spacer(Modifier.height(8.dp))
                Text("Your browser opens GitHub's form, filled in. You'll need a GitHub account to send it, and you can add what looks wrong. The app's page is then checked against the original, and what it got wrong is fixed.")
                if (noBrowser) {
                    Spacer(Modifier.height(8.dp))
                    Text("There's no browser on this device to open the form in.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
                if (openInBrowser(context, lookIssueUrl(link, version))) onDismiss() else noBrowser = true
            }) { Text("Open GitHub") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
