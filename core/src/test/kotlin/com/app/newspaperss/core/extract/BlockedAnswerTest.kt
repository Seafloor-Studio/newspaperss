package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a site said when it turned the app away: enough to name the blocker, nothing of the reader's. */
class BlockedAnswerTest {
    private val blocked = HttpResponse(
        403, "https://news.example/2026/11/13/story.html", "text/html",
        "<html><body><h1>Access blocked</h1><p>Your IP 203.0.113.7 and 2001:db8::42 look automated. Ref 9f2c-11.</p></body></html>",
        headers = listOf(
            "Server" to "envoy",
            "X-DataDome" to "protected",
            "Set-Cookie" to "datadome=Zm9vYmFyc2VjcmV0; Max-Age=31536000; Domain=.news.example",
            "Set-Cookie" to "nyt-a=1a2b3c; Path=/",
            "X-Forwarded-For" to "203.0.113.7",
            "Date" to "Fri, 13 Nov 2026 08:55:19 GMT",
        ),
    )

    @Test
    fun itNamesTheBlockerFromItsHeadersAndPage() {
        val answer = BlockedAnswer.of(blocked)
        assertTrue(answer, answer.startsWith("HTTP 403\n"))
        assertTrue(answer, "x-datadome: protected" in answer)
        assertTrue(answer, "server: envoy" in answer)
        assertTrue(answer, "cookies set: datadome, nyt-a" in answer)
        assertEquals(
            "every header named, values or not",
            "headers: date, server, set-cookie, x-datadome, x-forwarded-for",
            answer.lines().single { it.startsWith("headers: ") },
        )
        assertTrue(answer, "page: Access blocked Your IP [address] and [address] look automated." in answer)
    }

    /** The issue it goes in is public: no cookie's value, and no network address, the reader's least of all. */
    @Test
    fun itKeepsNoCookieValuesOrAddresses() {
        val answer = BlockedAnswer.of(blocked)
        assertFalse(answer, "Zm9vYmFyc2VjcmV0" in answer)
        assertFalse(answer, "1a2b3c" in answer)
        assertFalse(answer, "203.0.113.7" in answer)
        assertFalse(answer, "2001:db8" in answer)
    }

    /**
     * Values that can name the reader's network or city, or be traced to their request, are left
     * out, their names kept; an address is found beside letters, in a reverse-DNS name, and at the
     * cut, where cutting first would leave part of it.
     */
    @Test
    fun itKeepsNothingThatPointsAtTheReader() {
        val page = "Seen from ip_198.51.100.9, host 198-51-100-9.dsl.isp.example."
        val answer = BlockedAnswer.of(
            HttpResponse(
                403, "https://a.example/", "text/html", "<p>$page</p>",
                headers = listOf(
                    "Via" to "1.1 proxy3.corp.example (squid)",
                    "X-Cache" to "MISS from proxy3.corp.example",
                    "X-Served-By" to "cache-sjc10043-SJC",
                    "CF-Ray" to "8a1b2c3d4e5f6789-SJC",
                    "Set-Cookie" to "sessiontoken",
                ),
            ),
        )
        listOf("proxy3", "SJC", "8a1b2c3d", "sessiontoken", "198.51", "198-51").forEach { assertFalse("$it in $answer", it in answer) }
        assertTrue(answer, "page: Seen from ip_[address], host [address].dsl.isp.example." in answer)

        // At the cut: redacted whole first, so no part of it is left.
        val atTheCut = BlockedAnswer.of(HttpResponse(403, "https://a.example/", "text/html", "<p>${"x".repeat(185)} Your IP: 203.0.113.77</p>"))
        assertFalse(atTheCut, "203.0" in atTheCut)
        assertTrue(answer, "cookies set: (unnamed)" in answer)
        assertTrue(answer, "headers: cf-ray, set-cookie, via, x-cache, x-served-by" in answer)
    }

    /** Untrusted, so held to a size: hundreds of headers and cookies don't make it longer. */
    @Test
    fun manyHeadersAreCutShort() {
        val name = "x".repeat(60)
        val many = HttpResponse(
            403, "https://a.example/", "text/html", "<p>${"word ".repeat(100)}</p>",
            headers = (1..500).map { "$name-$it" to "v" } + (1..500).map { "Set-Cookie" to "$name$it=v" },
        )
        assertEquals(1_500, BlockedAnswer.of(many).length)
    }

    @Test
    fun aLongPageIsCutShort() {
        val long = HttpResponse(403, "https://a.example/", "text/html", "<p>${"word ".repeat(500)}</p>")
        val page = BlockedAnswer.of(long).lines().single { it.startsWith("page: ") }
        assertTrue(page.length <= "page: ".length + 200)
    }
}
