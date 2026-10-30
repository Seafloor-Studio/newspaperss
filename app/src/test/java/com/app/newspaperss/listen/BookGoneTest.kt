package com.app.newspaperss.listen

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * An edition's book going while it's listened to, through the app's own wiring: deleted from its
 * page, or cleared out with the older editions after a run. Listen lets it go, and its natural
 * voice's audio and its recording choices go with it, freeing their space.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class BookGoneTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container get() = app.container
    private val player get() = container.listen
    private val dir = File(app.filesDir, "editions").apply { mkdirs() }

    /** A sent edition with its book, made [daysAgo]; returns its id. */
    private fun edition(name: String, daysAgo: Long = 0): Long = runBlocking {
        val article = EditionArticle("Knots", "Quanta", "https://example.com/k", "<p>One. Two. Three.</p>", 1.0)
        File(dir, "$name.epub").outputStream().use {
            EpubWriter.write(EditionDoc(name, LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000009", listOf(EditionSection(null, listOf(article)))), it)
        }
        val made = Instant.parse("2026-10-30T06:00:00Z").minusSeconds(daysAgo * 86_400)
        val id = container.db.editions().insert(EditionEntity(title = name, createdAt = made, status = EditionStatus.DELIVERED, fileName = "$name.epub", articleCount = 1, minutes = 1.0))
        container.db.editions().insertArticles(listOf(EditionArticleEntity(editionId = id, articleId = null, position = 0, title = "Knots", sourceTitle = "Quanta", minutes = 1.0)))
        id
    }

    /** Listening to [id], with audio made for it and its first page turned back to the voice. */
    private fun listeningTo(id: Long) {
        val store = container.podcastStore
        store.want(id, PodcastVoice.HEART)
        store.keep(id, 0, 0, store.scratch(id, 0, 0).apply { writeBytes(ByteArray(2_000_000)) }, listOf(0.0))
        StoredReadingChoices(app).setVoiceInstead(id, 0, true)
        player.start(id)
        idleUntil { player.state.value.script != null }
        assertEquals(id, player.state.value.editionId)
        assertTrue(store.bytes(id) > 0)
    }

    private fun assertLetGo(id: Long) {
        idleUntil { player.state.value.editionId == null }
        idleUntil { container.podcastStore.voice(id) == null && container.podcastStore.bytes(id) == 0L }
        idleUntil { !StoredReadingChoices(app).voiceInstead(id, 0) }
    }

    @Test
    fun deletingTheEditionBeingListenedToLetsItGoAndFreesItsAudio() {
        val id = edition("Thursday")
        listeningTo(id)

        assertTrue(runBlocking { container.editions.delete(id) })

        assertLetGo(id)
        assertFalse(File(dir, "Thursday.epub").exists())
    }

    @Test
    fun anEditionClearedOutAfterARunIsLetGoTooButANewerOneIsnt() {
        // Fifteen sent editions: the oldest is past the fourteen whose books are kept.
        val oldest = edition("Oldest", daysAgo = 20)
        val newer = (1..14).map { edition("Day$it", daysAgo = it.toLong()) }
        listeningTo(oldest)
        container.podcastStore.want(newer.first(), PodcastVoice.HEART)

        runBlocking { container.editions.pruneFiles() }

        assertLetGo(oldest)
        assertNotNull("a kept edition's audio stays", container.podcastStore.voice(newer.first()))
    }
}
