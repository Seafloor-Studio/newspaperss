package com.app.newspaperss.core.extract

import com.app.newspaperss.core.net.HttpResponse
import org.jsoup.Jsoup

/**
 * What a site said when it turned the app away, short enough for a public issue and safe to put
 * in one: which bot blocker answered shows in its headers' names, a few values that name the
 * blocker and nothing else, and the block page's first words. Left out: cookies' values, values
 * that can name the reader's own network or city or be traced to their request (a proxy's `via`,
 * a CDN's `cf-ray` or `x-served-by`), and anything shaped like a network address, the reader's
 * own being shown on many block pages ("Your IP: …").
 */
object BlockedAnswer {
    /** Headers whose values name the blocker or what it decided, and nothing of the reader's. */
    private val KEPT = setOf(
        "server", "cf-mitigated", "cf-cache-status", "x-datadome", "x-amzn-waf-action",
        "x-sucuri-block", "retry-after", "content-type", "x-nyt-route",
    )

    private const val PAGE_CHARS = 200
    private const val VALUE_CHARS = 80
    private const val MOST_NAMES = 40
    private const val MOST_CHARS = 1_500

    // Not part of a longer run of digits and dots, so a letter or "_" beside it doesn't hide it;
    // dashes too, as an address appears in a reverse-DNS name ("203-0-113-7.isp.example").
    private val IPV4 = Regex("""(?<![0-9.])\d{1,3}(?:[.-]\d{1,3}){3}(?![0-9])""")
    private val IPV6 = Regex("""(?i)(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{1,4}(?![0-9a-f:])|(?<![0-9a-f:])(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?![0-9a-f:]))?""")

    fun of(response: HttpResponse): String {
        val names = response.headers.map { it.first.lowercase() }.distinct().sorted()
        // Each value redacted whole, then cut: cut first, an address at the cut would be left in part.
        val kept = response.headers.filter { it.first.lowercase() in KEPT }.distinctBy { it.first.lowercase() }
            .map { (name, value) -> "${name.lowercase()}: ${redacted(value).take(VALUE_CHARS)}" }
        // Only which cookies: their values can be a session, the reader's or the blocker's.
        val cookies = response.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
            .map { it.second.substringBefore('=', "").trim().take(40).ifEmpty { "(unnamed)" } }.distinct().take(MOST_NAMES)
        val page = redacted(Jsoup.parse(response.body).text().replace(Regex("""\s+"""), " ").trim()).take(PAGE_CHARS)
        return buildString {
            appendLine("HTTP ${response.code}")
            kept.forEach { appendLine(it) }
            if (cookies.isNotEmpty()) appendLine("cookies set: ${cookies.joinToString()}")
            appendLine("headers: ${names.take(MOST_NAMES).joinToString { it.take(40) }}" + if (names.size > MOST_NAMES) ", …" else "")
            if (page.isNotEmpty()) append("page: $page")
        }.trimEnd().take(MOST_CHARS)
    }

    /**
     * An article's address as the book links to it ("Read the original at …"), which is what Send
     * for a look has: an international name in punycode, a path percent-encoded.
     */
    fun key(url: String): String = HtmlCleaner.absoluteUrl(url, "", HtmlCleaner.LINK_SCHEMES) ?: url

    private fun redacted(text: String) = text.replace(IPV4, "[address]").replace(IPV6, "[address]")
}
