package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpBytes
import com.app.newspaperss.core.net.HttpClient
import com.app.newspaperss.core.net.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.io.IOException

class ZzThrowawayFreewallTest {
    private class Http(val pages: Map<String, String>) : HttpClient {
        override suspend fun get(url: String): HttpResponse =
            pages[url]?.let { HttpResponse(200, url, "text/html; charset=utf-8", it) } ?: throw IOException("no route")
        override suspend fun getBytes(url: String, headers: Map<String, String>): HttpBytes = throw IOException("no")
        override suspend fun postJson(url: String, body: String): HttpResponse = throw IOException("no")
    }
    private val dir = "/tmp/claude-0/-home-user/391d58c4-3a8f-5e52-8caa-1bced6cebee2/scratchpad/dl/"

    private fun run(name: String, url: String, page: String, feed: String?) = runTest {
        val a = ArticleExtractor(Http(mapOf(url to File(page).readText()))).extract(
            ExtractInput(url, "T", feed?.let { File(it).readText() }, null),
        )
        val pc = PageExtractor.extract(File(page).readText(), url)
        println("=== $name paid=${a.paidPost} nothingFree=${a.nothingFree} words=${a.wordCount} usedFeed=${a.usedFeedContent} note=${a.note} pageExtractor=${pc.extractor} pagePaywalled=${pc.paywalled}")
        val text = org.jsoup.Jsoup.parse(a.html).text()
        println("TAIL: " + text.takeLast(400))
        for (k in listOf("Subscribe for free", "continue reading", "Enter your email", "Subscribe", "paid subscribers")) if (k in text) println("CONTAINS: $k")
    }

    @Test fun cows() = run("cows", "https://jessicadefino.substack.com/p/look-at-these-cows-on-the-victorias", "/tmp/claude-0/p409/page.html", dir + "cows-feed.html")
    @Test fun cowsNoFeed() = run("cows-nofeed", "https://jessicadefino.substack.com/p/look-at-these-cows-on-the-victorias", "/tmp/claude-0/p409/page.html", null)
    @Test fun gmSub() = run("gm-sub", "https://garymarcus.substack.com/p/all-the-kings-horses-and-all-the", dir + "gm-sub.html", dir + "gm-sub-feed.html")
    @Test fun gmPaid() = run("gm-paid", "https://garymarcus.substack.com/p/what-anthropic-left-out-of-their", dir + "gm-paid.html", dir + "gm-paid-feed.html")
}
