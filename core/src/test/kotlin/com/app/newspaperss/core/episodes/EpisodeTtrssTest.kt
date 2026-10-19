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
            val labelOf = snapshot.items.associate { it.url to EpisodeKind.of(labels.getValue(it.guid).jsonObject.getValue("kind").jsonPrimitive.content) }
            val headlines = runBlocking { TtrssClient(Recorded(read(File(dir, "ttrss.json.gz"))), API, "reader", "secret").unreadHeadlines() }
            val items = headlines.map { FeedItem(it.link, it.link, it.title, it.content, null, null, it.attachments) }
            val verdicts = EpisodeDetector.classify(EpisodeFeed.of(items))
            items.map { Judged(dir.name, it.title, labelOf.getValue(it.url), verdicts.getValue(it.guid).kind, EpisodeDetector.audioOf(it.enclosures) != null) }
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
        fun read(f: File) = GZIPInputStream(f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
