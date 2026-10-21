package com.app.newspaperss.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The choices in the order a share offers them: the likely one first, Both last. */
fun shareChoices(frontPage: Boolean): List<ShareChoice> =
    if (frontPage) listOf(ShareChoice.SUBSCRIBE, ShareChoice.SAVE, ShareChoice.BOTH) else listOf(ShareChoice.SAVE, ShareChoice.SUBSCRIBE, ShareChoice.BOTH)

fun ShareChoice.label() = when (this) {
    ShareChoice.SAVE -> "Save to your reading list"
    ShareChoice.SUBSCRIBE -> "Subscribe to this site"
    ShareChoice.BOTH -> "Both"
}

/**
 * What a shared link is for, asked at once: nothing is fetched until a choice is made. A site's
 * front page leads with Subscribe, anything else with Save. [title] is what the sharing app
 * called the page, if anything; [host] says where it's from.
 */
@Composable
fun ShareChoicesDialog(title: String?, host: String, frontPage: Boolean, onChoose: (ShareChoice) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title ?: host, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title != null) Text(host, style = MaterialTheme.typography.bodyMedium)
                shareChoices(frontPage).forEachIndexed { i, choice ->
                    // The likely answer filled, so it's the one an eye (and a thumb) lands on.
                    if (i == 0) {
                        Button(onClick = { onChoose(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice.label()) }
                    } else {
                        OutlinedButton(onClick = { onChoose(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choice.label()) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
