package com.app.newspaperss.listen

import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.ListenTime
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.ui.edition.EpubPages
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * One edition to listen to: its articles in book order, then its closing page, each read out of
 * the book when it's reached; then, past the edition's end, the episodes its notes posts are about
 * (debug builds, see [EndEpisode]). After the closing page so that every page of the book keeps
 * its number, which the natural voice's audio is kept by.
 */
class ListenBook(
    val editionId: Long,
    val title: String,
    val pages: List<ListenPage>,
    private val read: suspend (index: Int) -> ListenScript?,
    private val onClose: () -> Unit = {},
) : Closeable {
    suspend fun script(index: Int): ListenScript? = read(index)


    override fun close() = onClose()

    companion object {
        /** The edition's book, or null if the edition or its file is gone; [readings] learns which of its pages have a recording. */
        suspend fun open(editions: EditionRepository, editionId: Long, readings: Readings? = null): ListenBook? {
            val edition = editions.byId(editionId) ?: return null
            val file = editions.fileOf(edition) ?: return null
            val contents = editions.observeContents(editionId).first().sortedBy { it.entry.position }
            val book = EpubPages(file)
            // Known once the recordings are found, which reads the articles' pages first.
            var ending = emptyList<EndEpisode>()
            val read: suspend (Int) -> ListenScript? = { index ->
                when {
                    index < contents.size -> withContext(Dispatchers.IO) { book.article(contents[index].entry.position) }?.let(ListenScript::parse)
                    index == contents.size -> withContext(Dispatchers.IO) { book.entry("OEBPS/end.xhtml")?.toString(Charsets.UTF_8) }?.let(ListenScript::parse)
                    else -> ending.getOrNull(index - contents.size - 1)?.script
                }
            }
            // Best effort: without its recordings, the edition is read by the voice.
            try {
                readings?.load(editionId, contents, read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ListenBook", "Couldn't find the recordings: ${e.javaClass.name}")
            }
            ending = readings?.ending(editionId).orEmpty()
            // A page that plays a recording takes as long as it: an episode can be half or twice
            // its transcript read aloud.
            val pages = contents.mapIndexed { i, it ->
                val recording = readings?.at(editionId, i)?.seconds?.let { s -> s / 60.0 }
                ListenPage(it.entry.title, it.entry.sourceTitle, recording ?: ListenTime.fromReading(it.entry.minutes))
            } + ListenPage(EpubWriter.END_TITLE, null, 0.0, end = true) +
                ending.map { ListenPage(it.title, it.source, (it.reading.seconds ?: 0) / 60.0, episode = true) }
            return ListenBook(editionId, edition.title, pages, read = read, onClose = book::close)
        }
    }
}

/**
 * @param minutes about how long listening to it takes.
 * @param end the closing page, "That's all for today".
 * @param episode an episode played after the closing page, not part of the edition's own length.
 */
data class ListenPage(val title: String, val source: String?, val minutes: Double, val end: Boolean = false, val episode: Boolean = false)
