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
import com.app.newspaperss.ui.sources.AddState
import com.app.newspaperss.ui.sources.UNCATEGORIZED
import com.app.newspaperss.ui.sources.alreadyLine
import com.app.newspaperss.ui.sources.UNCATEGORIZED_ID
import com.app.newspaperss.ui.sources.pickerCategories
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

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
        // Saved before the slow part: Android may end the app while the feed is looked for.
        val saved = if (choice == ShareChoice.BOTH) readingList.save(url, title) else null
        val subscribed = try {
            subscribe(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Something unforeseen (the database, a page no parser expected) mustn't lose the share.
            Subscribed("Couldn't subscribe to ${SourceRepository.hostOf(url)}.", saveInstead = true)
        }
        return when {
            saved != null -> "${subscribed.line} ${saveLine(saved)}."
            subscribed.saveInstead -> "${subscribed.line} ${insteadLine(readingList.save(url, title))}"
            else -> subscribed.line
        }
    }

    /** [saveInstead]: there's nothing to follow, so the page is saved whatever was picked. */
    private data class Subscribed(val line: String, val saveInstead: Boolean = false)

    private suspend fun subscribe(url: String): Subscribed {
        val host = SourceRepository.hostOf(url)
        val found = finder.find(url)
        // Its reason, not just "no feed": offline, or a site refusing the app, is worth trying again.
        if (found is FindResult.NotFound) return Subscribed(found.reason, saveInstead = true)
        val feed = (found as FindResult.Found).feeds.let(FeedFinder::siteFeed) ?: return Subscribed("No feed found at $host.", saveInstead = true)
        val name = feed.title?.takeIf { it.isNotBlank() } ?: host
        return if (toServer()) subscribeInTtrss(feed.url, name) else subscribeHere(feed.url, feed.title, name)
    }

    private suspend fun subscribeHere(feedUrl: String, title: String?, name: String): Subscribed {
        if (sources.observe().first().any { it.kind == SourceKind.FEED && sameFeed(it.url, feedUrl) }) return Subscribed("$name is already in your sites.")
        sources.addFeed(feedUrl, title)
        // Added either way: a failure to schedule the sync mustn't read as a failed subscribe.
        runCatching { onSourcesChanged() }
        return Subscribed("Added $name to your sites.")
    }

    /** In the category last picked in Add, else the one the paper takes articles from, else Uncategorized, as Add offers. */
    private suspend fun subscribeInTtrss(feedUrl: String, name: String): Subscribed {
        ttrss.feedAt(feedUrl)?.let { known -> return Subscribed(alreadyLine(AddState.AlreadyIn(known.title ?: name, known.category, known.leftOut, known.outsideCategory))) }
        val loaded = (ttrss.categories(includeEmpty = true) as? TtrssRepository.Categories.Loaded)?.categories.orEmpty()
        val categories = pickerCategories(loaded)
        val account = sources.observe().first().firstOrNull { it.kind == SourceKind.TTRSS }
        val chosen = listOfNotNull(settings.current().lastCategoryId, account?.ttrssCategoryId).firstOrNull { id -> categories.any { it.id == id } } ?: UNCATEGORIZED_ID
        val category = categories.first { it.id == chosen }
        val request = TtrssSubscriptions.Request(feedUrl, name, category)
        return when (val answer = subscriptions.subscribeAndWait(request)) {
            // Being added, or taken out by an Undo, from Sources just now: which isn't known here.
            null -> Subscribed("tt-rss is busy with $name just now.", saveInstead = true)
            is TtrssRepository.Subscribed.Added -> {
                val outside = account?.ttrssCategoryTitle?.takeIf { answer.outsidePaper }
                Subscribed("Added $name to your tt-rss, in ${answer.category ?: UNCATEGORIZED}." + outside?.let { " Your paper takes articles from $it only." }.orEmpty())
            }
            is TtrssRepository.Subscribed.Already -> {
                val feed = answer.feed
                Subscribed(alreadyLine(AddState.AlreadyIn(feed?.title ?: name, feed?.category, feed?.leftOut == true, feed?.outsideCategory == true)))
            }
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
