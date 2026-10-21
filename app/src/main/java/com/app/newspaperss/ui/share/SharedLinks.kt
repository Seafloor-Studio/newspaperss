package com.app.newspaperss.ui.share

import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.FindResult
import com.app.newspaperss.data.ReadingListRepository
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.data.TtrssSubscriptions
import com.app.newspaperss.data.sameFeed
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.ui.sources.UNCATEGORIZED
import com.app.newspaperss.ui.sources.UNCATEGORIZED_ID
import com.app.newspaperss.ui.sources.pickerCategories
import kotlinx.coroutines.flow.first

/** What to do with a shared link. */
enum class ShareChoice { SAVE, SUBSCRIBE, BOTH }

/**
 * A shared link saved, subscribed to, or both, the way Sources' Add would subscribe: on this phone
 * or in tt-rss, whichever the reader's feeds come from. A share is never lost: with no feed to
 * follow, the page is saved instead. Each call returns what to tell the reader.
 */
class SharedLinks(
    private val readingList: ReadingListRepository,
    private val sources: SourceRepository,
    private val finder: FeedFinder,
    private val settings: SettingsStore,
    private val ttrss: TtrssRepository,
    private val subscriptions: TtrssSubscriptions,
    private val onSourcesChanged: () -> Unit,
) {
    suspend fun act(url: String, title: String?, choice: ShareChoice): String {
        if (choice == ShareChoice.SAVE) return saveLine(readingList.save(url, title))
        val subscribed = subscribe(url)
        return when {
            subscribed.saveInstead -> "${subscribed.line} ${insteadLine(readingList.save(url, title))}"
            choice == ShareChoice.BOTH -> "${subscribed.line} ${saveLine(readingList.save(url, title))}."
            else -> subscribed.line
        }
    }

    /** [saveInstead]: there's nothing to follow, so the page is saved whatever was picked. */
    private data class Subscribed(val line: String, val saveInstead: Boolean = false)

    private suspend fun subscribe(url: String): Subscribed {
        val host = SourceRepository.hostOf(url)
        val found = finder.find(url)
        val feed = (found as? FindResult.Found)?.feeds?.let(FeedFinder::siteFeed)
            ?: return Subscribed("No feed found at $host.", saveInstead = true)
        val name = feed.title?.takeIf { it.isNotBlank() } ?: host
        return if (toServer()) subscribeInTtrss(feed.url, name) else subscribeHere(feed.url, feed.title, name)
    }

    private suspend fun subscribeHere(feedUrl: String, title: String?, name: String): Subscribed {
        if (sources.observe().first().any { it.kind == SourceKind.FEED && sameFeed(it.url, feedUrl) }) return Subscribed("$name is already in your sites.")
        sources.addFeed(feedUrl, title)
        onSourcesChanged()
        return Subscribed("Added $name to your sites.")
    }

    /** In the category last picked in Add, else the one the paper takes articles from, else Uncategorized, as Add offers. */
    private suspend fun subscribeInTtrss(feedUrl: String, name: String): Subscribed {
        ttrss.feedAt(feedUrl)?.let { known -> return Subscribed("${known.title ?: name} is already in your tt-rss, in ${known.category ?: UNCATEGORIZED}.") }
        val loaded = (ttrss.categories(includeEmpty = true) as? TtrssRepository.Categories.Loaded)?.categories.orEmpty()
        val categories = pickerCategories(loaded)
        val account = sources.observe().first().firstOrNull { it.kind == SourceKind.TTRSS }
        val chosen = listOfNotNull(settings.current().lastCategoryId, account?.ttrssCategoryId).firstOrNull { id -> categories.any { it.id == id } } ?: UNCATEGORIZED_ID
        val category = categories.first { it.id == chosen }
        val request = TtrssSubscriptions.Request(feedUrl, name, category)
        return when (val answer = subscriptions.subscribeAndWait(request)) {
            null -> Subscribed("$name is being added to your tt-rss already.")
            is TtrssRepository.Subscribed.Added -> Subscribed("Added $name to your tt-rss, in ${answer.category ?: UNCATEGORIZED}.")
            is TtrssRepository.Subscribed.Already -> Subscribed("$name is already in your tt-rss, in ${answer.feed?.category ?: UNCATEGORIZED}.")
            is TtrssRepository.Subscribed.Failed -> Subscribed("tt-rss didn't add $name. ${answer.reason}", saveInstead = true)
        }
    }

    /** As Sources decides it: the server setup sends sites to tt-rss. */
    private suspend fun toServer(): Boolean {
        val hasServer = sources.observe().first().any { it.kind == SourceKind.TTRSS }
        return settings.current().feedsFrom(hasServer) == FeedsFrom.SERVER
    }

    private fun saveLine(saved: Boolean) = if (saved) "Saved for your next edition" else "Already on your reading list"
    private fun insteadLine(saved: Boolean) = if (saved) "Saved the page for your next edition instead." else "The page is on your reading list already."
}
