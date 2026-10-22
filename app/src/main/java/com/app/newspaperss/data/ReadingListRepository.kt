package com.app.newspaperss.data

import androidx.room.withTransaction
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.feed.ChecklistItem
import com.app.newspaperss.core.feed.MarkdownChecklist
import com.app.newspaperss.core.feed.ReadLaterImport
import com.app.newspaperss.core.feed.ReadingListFile
import com.app.newspaperss.core.net.withoutTracking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/**
 * Links saved to read later. The list is a source of its own, so it takes
 * turns with the feeds and gets its own slot in each edition, and its items
 * never expire.
 *
 * [onUntitled] is given the ids of links to look up in the background (see
 * [ReadingListTitles]): each link saved, for its length, and imported links
 * without a title.
 */
class ReadingListRepository(
    private val db: AppDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val onUntitled: (articleIds: List<Long>) -> Unit = {},
) {
    suspend fun sourceId(): Long {
        db.sources().byUrl(URL)?.let { return it.id }
        val id = db.sources().insert(
            SourceEntity(
                kind = SourceKind.READING_LIST, url = URL, title = "Your reading list",
                // Saved links are pages; there's no feed text to fall back on.
                contentMode = ContentMode.PAGE,
                // First in line, so a saved link doesn't wait behind every feed.
                position = -1,
            ),
        )
        return if (id == -1L) db.sources().byUrl(URL)!!.id else id
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<ArticleEntity>> = flow { emit(sourceId()) }.flatMapLatest { db.articles().observeAllForSource(it) }

    /** How many saved links are still waiting for an edition: read or archived ones aren't, unless starred. */
    fun observeWaiting(): Flow<Int> = observe().map { list -> list.count(::isWaiting) }

    /**
     * Returns false if the link was already on the list. One there that couldn't be read
     * ([ArticleEntity.unreadable]) is looked at again too: saving it again is the way to say "try
     * again", and the lookup clears the mark if the page reads now.
     */
    suspend fun save(shared: String, title: String? = null): Boolean {
        // As a feed's links are stored: shared with tracking, then found in the site's feed without,
        // the same story would be two to the edition (Share's Both does both at once).
        val url = withoutTracking(shared)
        // The title is whatever subject the sharing app gave.
        val article = ArticleEntity(sourceId = sourceId(), guid = url, url = url, title = RowLimits.cut(title?.trim().orEmpty(), RowLimits.MAX_TITLE), discoveredAt = clock.instant())
        val articleId = db.articles().insertIgnoring(article)
        if (articleId == -1L) {
            db.articles().allForSource(article.sourceId).firstOrNull { it.guid == url && it.unreadable != null && it.state == ArticleState.NEW }
                ?.let { onUntitled(listOf(it.id)) }
            return false
        }
        onUntitled(listOf(articleId))
        return true
    }

    suspend fun remove(article: ArticleEntity) = db.articles().delete(article.id)

    /** @property unread how many of the [added] links are waiting for an edition (archived ones aren't). */
    data class Imported(val format: ReadingListFile.Format, val added: Int, val unread: Int = added)

    /**
     * Adds the links from a markdown checklist or a Pocket or Instapaper export.
     * Ticked or archived ones arrive as already read, and a link still waiting
     * here that the file marks read (read elsewhere) is marked read too.
     */
    suspend fun import(text: String): Imported {
        val file = ReadLaterImport.parse(text)
        val id = sourceId()
        val now = clock.instant()
        val added = db.withTransaction {
            file.items.mapNotNull { item ->
                // As save keeps them, so a link imported and the same link shared are one.
                val url = withoutTracking(item.url)
                val article = ArticleEntity(
                    sourceId = id, guid = url, url = url, title = item.title.orEmpty(), discoveredAt = now,
                    state = if (item.done) ArticleState.DELIVERED else ArticleState.NEW,
                )
                val storable = RowLimits.storable(article) ?: return@mapNotNull null
                db.articles().insertIgnoring(storable).takeIf { it != -1L }?.let { storable.copy(id = it) }
            }
        }
        val done = file.items.filter { it.done }.map { withoutTracking(it.url) }.toSet()
        val readElsewhere = db.articles().allForSource(id).filter { it.state == ArticleState.NEW && it.url in done }
        if (readElsewhere.isNotEmpty()) db.articles().setState(readElsewhere.map { it.id }, ArticleState.DELIVERED)
        val untitled = added.filter { it.title.isEmpty() && it.state == ArticleState.NEW }.map { it.id }
        if (untitled.isNotEmpty()) onUntitled(untitled)
        return Imported(file.format, added.size, added.count { it.state == ArticleState.NEW })
    }

    /** The list as an rss-to-e-reader checklist: anything no longer waiting or in an unsent edition is ticked. */
    suspend fun exportMarkdown(): String = MarkdownChecklist.write(
        // A link that can't be read isn't read either: it stays unticked, for whatever reads the list next.
        db.articles().allForSource(sourceId()).map { ChecklistItem(it.url, done = !unsent(it) && it.state != ArticleState.IN_EDITION) },
    )

    /** A star brings a delivered link back, so it's waiting again. */
    private fun unsent(article: ArticleEntity) =
        article.state == ArticleState.NEW || (article.starredAt != null && article.state != ArticleState.IN_EDITION)

    /** [unsent] and readable, so an edition will take it ([ArticleEntity.unreadable]). */
    private fun isWaiting(article: ArticleEntity) = article.unreadable == null && unsent(article)

    companion object {
        /** Not a web address, so it can never collide with a real feed's. */
        const val URL = "newspaperss:reading-list"
    }
}
