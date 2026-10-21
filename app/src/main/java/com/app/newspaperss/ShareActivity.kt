package com.app.newspaperss

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.MarkdownChecklist
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.ui.share.ShareChoice
import com.app.newspaperss.ui.share.ShareChoicesDialog
import com.app.newspaperss.ui.share.SharedLinks
import com.app.newspaperss.ui.theme.NewspaperssTheme
import com.app.newspaperss.work.SyncWorker
import kotlinx.coroutines.launch

/**
 * The share-sheet target: asks whether to save the shared link, subscribe to its site, or both,
 * then does it without opening the app. Nothing is kept on screen, so rotating it or Android
 * ending it just asks again: nothing happens until a choice is tapped.
 */
class ShareActivity : ComponentActivity() {
    private var chosen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val url = MarkdownChecklist.firstUrl(text)
        if (url == null) {
            Toast.makeText(this, "There's no link to save in that.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // The subject is usually the page title; some apps put the URL there instead.
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeUnless { it.contains("://") || it.isBlank() }
        setContent {
            NewspaperssTheme {
                ShareChoicesDialog(title, SourceRepository.hostOf(url), FeedFinder.isFrontPage(url), onChoose = { choose(url, title, it) }, onCancel = ::finish)
            }
        }
    }

    private fun choose(url: String, title: String?, choice: ShareChoice) {
        // A second tap before the activity is gone would save or subscribe twice.
        if (chosen) return
        chosen = true
        val container = application.container
        val appContext = applicationContext
        val links = SharedLinks(
            container.readingList, container.sources, container.feedFinder, container.settings, container.ttrss, container.ttrssSubscriptions,
        ) { SyncWorker.syncNow(appContext) }
        // Finding a feed can take a while, and the screen is gone by then: say something now.
        if (choice != ShareChoice.SAVE) Toast.makeText(appContext, "Looking for ${SourceRepository.hostOf(url)}'s feed…", Toast.LENGTH_SHORT).show()
        container.appScope.launch {
            val said = links.act(url, title, choice)
            Toast.makeText(appContext, said, if (choice == ShareChoice.SAVE) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
