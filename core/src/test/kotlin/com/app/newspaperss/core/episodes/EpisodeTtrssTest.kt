package com.app.newspaperss.core.episodes

import com.app.newspaperss.core.feed.FeedItem
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import com.app.newspaperss.core.ttrss.TtrssClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * The episode corpus as tt-rss passes it on. For some of its feeds, ttrss.json.gz holds what a
 * real tt-rss answered to getHeadlines, asked as the app asks (with content and attachments),
 * scrambled like the feed snapshot ([com.app.newspaperss.core.tools.EpisodeSnapshot]). tt-rss's
 * sanitizer strips players, videos and Substack's uploaded-audio player, so a post known only by
 * those is an article here; but every post with an audio file must be judged as it is from the
 * feed, since those are the ones Listen can hand over to.
 */
class EpisodeTtrssTest {
    private class Judged(val feed: String, val title: String, val label: EpisodeKind, val detected: EpisodeKind, val audio: Boolean)

    private val root = File(EpisodeTtrssTest::class.java.getResource("/episodes")!!.toURI())

    private val judged: List<Judged> by lazy {
        root.listFiles().orEmpty().filter { File(it, "ttrss.json.gz").exists() }.sortedBy { it.name }.flatMap { dir ->
            val labels = Json.parseToJsonElement(File(dir, "labels.json").readText()).jsonObject.getValue("items").jsonObject
            val snapshot = FeedParser.parse(read(File(dir, "feed.xml.gz")), File(dir, "source.txt").readText().trim())
            val labelOf = snapshot.items.associate { it.url to EpisodeKind.of(labels.getValue(it.guid).jsonObject.getValue("kind").jsonPrimitive.content)!! }
            val headlines = runBlocking { TtrssClient(Recorded(read(File(dir, "ttrss.json.gz"))), API, "reader", "secret").unreadHeadlines() }
            // Oldest first, a few at a time, each judged beside those already seen: tt-rss sends a
            // few unread posts from each feed per sync, and sync judges them beside the source's
            // recent articles (FeedSync.episodes).
            val items = headlines.sortedBy { it.updated ?: 0 }.map { FeedItem(it.link, it.link, it.title, it.content, null, null, it.attachments) }
            val seen = mutableListOf<FeedItem>()
            val kinds = mutableMapOf<String, EpisodeKind>()
            for (batch in items.chunked(PER_SYNC)) {
                val context = seen.takeLast(CONTEXT).reversed()
                val verdicts = EpisodeDetector.classify(EpisodeFeed.of(batch + context))
                batch.forEach { kinds[it.guid] = verdicts.getValue(it.guid).kind }
                // As FeedSync: an earlier post can turn out an article, or be paired with a new one.
                context.forEach { old ->
                    val v = verdicts.getValue(old.guid)
                    if (v.pair != null && v.pair in batch.map { it.guid } || v.kind == EpisodeKind.ARTICLE) kinds[old.guid] = v.kind
                }
                seen += batch
            }
            items.map { Judged(dir.name, it.title, labelOf.getValue(it.url), kinds.getValue(it.guid), EpisodeDetector.audioOf(it.enclosures) != null) }
        }
    }

    @Test
    fun noArticleIsClassedAsAnythingElse() {
        assertTrue("no tt-rss answers in the corpus", judged.isNotEmpty())
        val wrong = judged.filter { it.label == EpisodeKind.ARTICLE && it.detected != EpisodeKind.ARTICLE }
        assertTrue("articles misclassed through tt-rss:\n" + wrong.joinToString("\n") { line(it) }, wrong.isEmpty())
    }

    @Test
    fun everyPostWithAudioIsJudgedAsItIsFromTheFeed() {
        val withAudio = judged.filter { it.audio }
        assertTrue("no post with audio", withAudio.isNotEmpty())
        val wrong = withAudio.filter { it.detected != it.label }
        assertTrue("posts with audio judged wrongly through tt-rss:\n" + wrong.joinToString("\n") { line(it) }, wrong.isEmpty())
    }

    private fun line(j: Judged) = "  ${j.feed} \"${j.title.take(50)}\": labelled ${j.label.label}, detected ${j.detected.label}"

    /** A tt-rss that logs in and answers getHeadlines with the recorded reply. */
    private class Recorded(private val headlines: String) : HttpClient {
        override suspend fun postJson(url: String, body: String): HttpResponse = HttpResponse(
            200, url, "application/json",
            if ("\"login\"" in body) """{"seq":0,"status":0,"content":{"session_id":"s","api_level":18}}""" else """{"seq":0,"status":0,"content":$headlines}""",
        )
        override suspend fun get(url: String): HttpResponse = throw IOException("not used")
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("not used")
    }

    private companion object {
        const val API = "https://rss.example.com/tt-rss/api/"
        /** As FeedSync: unread posts taken from each feed per sync, and recent articles judged beside them. */
        const val PER_SYNC = 5
        const val CONTEXT = 30
        fun read(f: File) = GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
