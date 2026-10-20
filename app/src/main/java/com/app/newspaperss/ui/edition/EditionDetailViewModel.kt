package com.app.newspaperss.ui.edition

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.delivery.KindleSend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class EditionDetail(
    /** Null if the edition doesn't exist (any more). */
    val edition: EditionEntity?,
    val contents: List<EditionContent>,
    /** The EPUB, or null if it was never written or has been deleted. */
    val file: File?,
) {
    /**
     * A delivered edition's articles can be starred to bring them back. Not one already in an
     * unsent edition, which is going out anyway, nor one whose source was removed.
     */
    fun canStar(content: EditionContent): Boolean =
        edition?.status == EditionStatus.DELIVERED && content.entry.articleId != null &&
            content.state != null && content.state != ArticleState.IN_EDITION

    fun isStarred(content: EditionContent): Boolean = canStar(content) && content.starredAt != null
}

class EditionDetailViewModel(
    private val editions: EditionRepository,
    private val id: Long,
    sentToKindle: Flow<Map<Long, KindleSend>> = flowOf(emptyMap()),
    /** Takes down this edition's notification once it's deleted. */
    private val dismissNotification: (Long) -> Unit,
) : ViewModel() {
    /** How it was just sent to a Kindle, if it was: Amazon can take a few minutes to deliver it. */
    val sentToKindle: StateFlow<KindleSend?> = sentToKindle.map { it[id] }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val detail: StateFlow<EditionDetail?> = combine(editions.observe(id), editions.observeContents(id)) { edition, contents ->
        EditionDetail(edition, contents, edition?.let(editions::fileOf))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** An edition is being made: unstarring waits (see [com.app.newspaperss.data.BUILD_HOLD]). */
    val building: StateFlow<Boolean> = editions.observeBuilding().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setStarred(articleId: Long, starred: Boolean) {
        viewModelScope.launch { editions.setStarred(articleId, starred) }
    }

    private val _deleted = MutableStateFlow(false)
    /**
     * True once [delete] has deleted it, for the screen to leave. A state, not a callback: the
     * screen may have been recreated (a rotation) while it was deleting.
     */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    fun delete() {
        viewModelScope.launch {
            if (editions.delete(id)) {
                dismissNotification(id)
                _deleted.value = true
            }
        }
    }

    fun markSent() {
        viewModelScope.launch { editions.markSentByHand(id) }
    }

    /** The mail app was opened to email this edition to the reader's Kindle, which counts as sent. */
    fun markEmailed() {
        viewModelScope.launch { editions.markEmailedToKindle(id) }
    }

    fun markNotSent() {
        viewModelScope.launch { editions.markNotSent(id) }
    }

    private val _copied = MutableStateFlow<String?>(null)
    /** What became of the last Save a copy, to say once; [copySaid] clears it. */
    val copied: StateFlow<String?> = _copied.asStateFlow()

    fun copySaid() {
        _copied.value = null
    }

    /**
     * Copies the edition's book to [uri], a file the reader made with the system's save dialog.
     * In the view model, so turning the phone mid-copy doesn't leave half a book.
     */
    fun saveCopy(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            // Not [detail]: after Android ends the app while the save dialog is open, the answer
            // comes before the edition has loaded again.
            val file = editions.observe(id).first()?.let(editions::fileOf)
            _copied.value = try {
                withContext(Dispatchers.IO) {
                    if (file == null) throw IOException("the book is gone")
                    resolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException("no stream")
                }
                "Saved a copy."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The save dialog has already made the file: an empty or half a book would fail to
                // open on whatever reads it next.
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                "Couldn't save the copy."
            }
        }
    }
}
