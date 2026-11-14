package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpResponse
import org.jsoup.Jsoup

/**
 * What a site said when it turned the app away, short enough for a public issue and safe to put
 * in one: which bot blocker answered is in its headers' names and a few of their values, and in
 * the block page's first words. Cookies' values are left out, and so is anything shaped like a
 * network address: block pages often show the reader's own ("Your IP: …").
 */
object BlockedAnswer {
    /** Headers whose values name a blocker or the cache it sits behind, and nothing of the reader's. */
    private val KEPT = setOf(
        "server", "via", "x-cache", "x-served-by", "cf-ray", "cf-mitigated", "cf-cache-status",
        "x-datadome", "x-dd-b", "x-amzn-waf-action", "x-akamai-transformed", "akamai-grn",
        "x-sucuri-id", "x-sucuri-block", "x-iinfo", "retry-after", "content-type", "x-nyt-route",
    )

    private const val PAGE_CHARS = 200
    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val IPV6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{1,4}\b|\b(?:[0-9a-fA-F]{1,4}:){1,7}:(?:[0-9a-fA-F]{1,4}\b)?""")

    fun of(response: HttpResponse): String {
        val names = response.headers.map { it.first.lowercase() }.distinct().sorted()
        val kept = response.headers.filter { it.first.lowercase() in KEPT }.map { (name, value) -> "${name.lowercase()}: ${value.take(80)}" }
        // Only which cookies: their values can be a session, the reader's or the blocker's.
        val cookies = response.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
            .map { it.second.substringBefore('=').trim() }.distinct()
        val page = Jsoup.parse(response.body).text().replace(Regex("""\s+"""), " ").trim().take(PAGE_CHARS)
        return redacted(
            buildString {
                appendLine("HTTP ${response.code}")
                kept.forEach { appendLine(it) }
                if (cookies.isNotEmpty()) appendLine("cookies set: ${cookies.joinToString()}")
                appendLine("headers: ${names.joinToString()}")
                if (page.isNotEmpty()) append("page: $page")
            }.trimEnd(),
        )
    }

    private fun redacted(text: String) = text.replace(IPV4, "[address]").replace(IPV6, "[address]")
}
