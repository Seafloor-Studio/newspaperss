package com.app.newspaperss.data

import com.app.newspaperss.core.extract.ArticleExtractor
import com.app.newspaperss.core.extract.PageTitle
import com.app.newspaperss.core.extract.PageWords
import com.app.newspaperss.core.net.HttpClient
import java.io.IOException

/**
 * Looks up saved links' pages before they reach an edition: the title of one
 * that arrived without one, so the reading list shows a headline instead of a
 * bare domain, and every one's length, for its reading time. Curated lists'
 * untitled links go through here too, for their titles.
 */
class ReadingListTitles(private val db: AppDatabase, private val http: HttpClient) {
    /** Returns false if a page couldn't be reached, so the lookup is worth trying again later. */
    suspend fun fetch(articleIds: List<Long>): Boolean {
        var unreachable = false
        for (id in articleIds) {
            val article = db.articles().byId(id) ?: continue
            // One marked unreadable is looked at again: saving it again asks for that.
            if (article.state != ArticleState.NEW || (article.title.isNotEmpty() && article.pageWords != null && article.unreadable == null)) continue
            val readingList = db.sources().byId(article.sourceId)?.kind == SourceKind.READING_LIST
            val page = try {
                http.get(article.url)
            } catch (e: IOException) {
                val why = ArticleExtractor.permanentFailure(e)
                if (why == null) unreachable = true else if (readingList && why != article.unreadable) db.articles().setUnreadable(id, why)
                continue
            }
            // A PDF, a video or a page gone for good: said in the reading list now, rather than
            // found out by an edition and then waiting for ever.
            val why = ArticleExtractor.permanentFailure(page)
            if (readingList && why != article.unreadable) db.articles().setUnreadable(id, why)
            if (why != null) continue
            if (!page.isSuccessful || page.contentType?.contains("html", ignoreCase = true) == false) continue
            // Its title ("Client Challenge") would stick as the headline, and the edition prefers a stored title.
            if (ArticleExtractor.isBotCheck(page.body)) continue
            // One page the extractor can't handle mustn't fail the batch: the worker's failure
            // would cancel the batches queued behind it.
            val measured = try {
                PageWords.of(page.body, page.finalUrl)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            measured?.let { db.articles().setPageWords(id, it) }
            val title = PageTitle.of(page.body, page.finalUrl) ?: continue
            // Checked again in the update: an edition may have taken the article while the page loaded.
            db.articles().setTitleIfUntitled(id, RowLimits.cut(title, RowLimits.MAX_TITLE))
        }
        return !unreachable
    }
}
