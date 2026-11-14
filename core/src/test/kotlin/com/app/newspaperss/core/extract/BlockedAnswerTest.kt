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

    @Test
    fun aLongPageIsCutShort() {
        val long = HttpResponse(403, "https://a.example/", "text/html", "<p>${"word ".repeat(500)}</p>")
        val page = BlockedAnswer.of(long).lines().single { it.startsWith("page: ") }
        assertTrue(page.length <= "page: ".length + 200)
    }
}
