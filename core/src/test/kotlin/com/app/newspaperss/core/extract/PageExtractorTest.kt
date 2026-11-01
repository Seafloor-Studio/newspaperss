package com.app.newspaperss.core.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageExtractorTest {
    private val fixture = javaClass.getResource("/extract/article_page.html")!!.readText()
    private val url = "https://example.com/culture/slow"
    private val sentence = "The committee met again on Thursday to argue about the budget for the new library wing. "

    @Test
    fun extractsArticleWithMetadata() {
        val page = PageExtractor.extract(fixture, url)
        assertEquals("readability", page.extractor)
        assertEquals("The Quiet Joy of Reading Slowly", page.title)
        assertEquals("Jane Doe", page.author)
        assertTrue(page.wordCount > 350)
        assertTrue("particular pleasure in reading slowly" in page.html)
        assertTrue("a letter from a friend" in page.html)
        // Every article would carry the same id, which is invalid once several share a book.
        assertFalse("readability-page" in page.html)
    }

    /** Shaped like Al Jazeera's markup: screen-reader labels and a "Recommended Stories" box between paragraphs. */
    @Test
    fun screenReaderLabelsAndRecommendedStoriesDontReachTheEdition() {
        val paragraphs = (1..8).joinToString("") { "<p>$sentence</p>" }
        val html = """<html><head><title>Story</title></head><body><main><article>
            <h1>Story</h1><span class="screen-reader-text">Skip links</span>$paragraphs
            <section class="more-on"><h2 class="more-on__heading">Recommended Stories</h2>
              <span class="screen-reader-text">list of 2 items</span>
              <ul class="more-on__list">
                <li><span class="screen-reader-text">list 1 of 2</span><a href="/a">Another story entirely</a></li>
                <li><span class="screen-reader-text">list 2 of 2</span><a href="/b">And one more story</a></li>
              </ul><span class="screen-reader-text">end of list</span></section>
            $paragraphs</article></main></body></html>"""

        val content = PageExtractor.extract(html, url)
        val cleaned = HtmlCleaner.clean(content.html, url, content.title).html

        for (junk in listOf("list 1 of 2", "list of 2 items", "end of list", "Skip links", "Recommended Stories", "Another story entirely")) {
            assertFalse(junk, junk in cleaned)
        }
        assertTrue(sentence.trim() in cleaned)
    }

    /**
     * Shaped like an ASP.NET WebForms site (news-medical.net): the whole page is one `<form>`, with
     * share buttons, ads and a sidebar inside it, and only a "you might also like" strip outside.
     */
    @Test
    fun anArticleInsideAFormWrappingThePageIsKept() {
        val paragraphs = (1..12).joinToString("") { "<p>$sentence</p>" }
        val links = (1..30).joinToString("") { "<li><a href=\"/news/$it\">Another health story number $it</a></li>" }
        val html = """<html><head><title>Story</title></head><body>
            <form method="post" action="./story.aspx" id="aspnetForm">
              <input type="hidden" name="__VIEWSTATE" value="abc">
              <nav><ul>$links</ul></nav>
              <main><ul class="share-box"><li><a href="#">Download PDF</a></li><li><a href="#">Email</a></li></ul>
                <div class="item-body"><h1>Story</h1><div class="content"><div itemprop="articleBody">
                  <p><em>A head-to-head trial, in a sentence.</em></p>$paragraphs
                  <div class="related-content-embed"><a href="/news/1">Related story</a></div>$paragraphs
                </div></div></div></main>
              <aside><ul>$links</ul></aside>
            </form>
            <div id="suggested-content-slider"><p>You might also like...</p><a href="/news/2">A story to suggest</a></div>
            </body></html>"""

        val page = PageExtractor.extract(html, url)

        assertEquals("readability", page.extractor)
        assertTrue(page.wordCount >= 24 * 16)
        assertFalse("You might also like" in page.html)
    }

    /** A form within the article, such as a newsletter sign-up, is still the form Readability drops. */
    @Test
    fun aSmallFormInTheArticleStillGoes() {
        val paragraphs = (1..12).joinToString("") { "<p>$sentence</p>" }
        val html = """<html><head><title>Story</title></head><body><article><h1>Story</h1>$paragraphs
            <form action="/subscribe"><label>Get our newsletter every morning</label><input type="email" name="e"><button>Sign up</button></form>
            $paragraphs</article></body></html>"""

        val page = PageExtractor.extract(html, url)

        assertTrue(sentence.trim() in page.html)
        assertFalse("Get our newsletter" in page.html)
    }

    /** A sign-in box after a short paywalled teaser is much of the page, but not the page's wrapper. */
    @Test
    fun aLongSignInFormAfterATeaserStaysOut() {
        val terms = (1..9).joinToString("") { "<p>By signing in you agree to our terms of service and privacy policy, number $it.</p>" }
        val html = """<html><head><title>Story</title></head><body><article><h1>Story</h1><p>$sentence</p>
            <form action="/login"><h2>Sign in to keep reading</h2><input type="email"><input type="password">$terms
            <p>Not a member? Subscribe for full access.</p></form></article></body></html>"""

        assertFalse("terms of service" in PageExtractor.extract(html, url).html)
    }

    /** Some comment engines put the comments in the reply form; they aren't the article. */
    @Test
    fun commentsInsideTheirFormStayOut() {
        val paragraphs = (1..12).joinToString("") { "<p>$sentence</p>" }
        val comments = (1..25).joinToString("") { "<div><p><b>Reader $it</b> wrote:</p><p>I enjoyed this piece and shared it with my whole family this week.</p></div>" }
        val html = """<html><head><title>Story</title></head><body><article><h1>Story</h1>$paragraphs</article>
            <form action="/comment" method="post">$comments<textarea name="c"></textarea><button>Reply</button></form></body></html>"""

        val page = PageExtractor.extract(html, url)

        assertTrue(sentence.trim() in page.html)
        assertFalse("Reader 3" in page.html)
    }

    /** Some paywalls hide the article body with a screen-reader class; on a page heavy with navigation it's a small share of the page. */
    @Test
    fun aHiddenArticleBodyOnABusyPageIsKept() {
        val nav = (1..60).joinToString("") { "<li><a href=\"/s$it\">Section number $it of the site</a></li>" }
        val body = (1..4).joinToString("") { "<p>$sentence</p>" }
        val html = """<html><head><title>Story</title></head><body><nav><ul>$nav</ul></nav>
            <article><h1>Story</h1><div class="visually-hidden">$body</div></article></body></html>"""

        assertTrue(sentence.trim() in PageExtractor.extract(html, url).html)
    }

    @Test
    fun cookieBannersAndDialogsAreRemovedBeforeExtraction() {
        val modal = "<div aria-modal=\"true\"><p>${"Subscribe to our newsletter for more great content today. ".repeat(3)}</p></div>"
        val page = PageExtractor.extract(fixture.replace("</main>", "</main>$modal"), url)
        val cleaned = HtmlCleaner.clean(page.html, url, page.title).html
        assertTrue("particular pleasure" in cleaned)
        assertFalse("technical storage" in cleaned)
        assertFalse("Subscribe to our newsletter" in cleaned)
    }

    @Test
    fun overlayMarkersNeverRemoveMostOfThePage() {
        val html = "<html><body><div class=\"cookie-recipes\"><p>${sentence.repeat(20)}</p></div><p>footer</p></body></html>"
        assertTrue(PageExtractor.extract(html, "https://example.com/recipes").wordCount > 250)
    }

    @Test
    fun jsonLdArticleBodyWhenThePageHasNoText() {
        val body = "Para one.\\n\\n" + sentence.repeat(20)
        val html = """<html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"NewsArticle","articleBody":"$body"}]}
            </script></head><body><div id="app"></div><p>Subscribe to read</p></body></html>"""
        val page = PageExtractor.extract(html, "https://example.com/a")
        assertEquals("json-ld", page.extractor)
        assertTrue(page.html.startsWith("<p>Para one.</p><p>The committee"))
    }

    @Test
    fun jsonLdWinsWhenReadabilityOnlySawAPaywallPreview() {
        val preview = (1..3).joinToString("") { "<p>${sentence.repeat(4)}</p>" }
        val full = sentence.repeat(60).trim()
        val html = """<html><head><script type="application/ld+json">{"@type":"Article","articleBody":"$full"}</script></head>
            <body><article>$preview<div class="paywall-prompt">Subscribe to keep reading</div></article></body></html>"""
        val page = PageExtractor.extract(html, "https://example.com/a")
        assertEquals("json-ld", page.extractor)
        assertTrue(page.wordCount > 900)
    }

    @Test
    fun fallsBackToTheBodyWhenNothingElseWorks() {
        val page = PageExtractor.extract("<html><body><span>only this</span></body></html>", "https://example.com/a")
        assertTrue("only this" in HtmlCleaner.clean(page.html, "https://example.com/a").html)
    }

    @Test
    fun cleanTitleRemovesATrailingSiteName() {
        assertEquals("E-reader", PageExtractor.cleanTitle("E-reader - Wikipedia", url = "https://en.wikipedia.org/wiki/E-reader"))
        assertEquals("Slow reading", PageExtractor.cleanTitle("Slow reading | The Example Review", siteName = "The Example Review"))
        assertEquals("Slow reading", PageExtractor.cleanTitle("Slow reading — Jane Doe", author = "Jane Doe"))
        // A separator that isn't followed by the site's name is part of the title.
        assertEquals("Review: The Long Goodbye", PageExtractor.cleanTitle("Review: The Long Goodbye", url = "https://example.com/r"))
        assertEquals("Pros - and cons of tea", PageExtractor.cleanTitle(" Pros - and\ncons of tea ", url = "https://example.com/x"))
    }

    @Test
    fun rejectsJunkAuthors() {
        assertEquals("Jane Doe", PageExtractor.cleanAuthor("  Jane\n Doe "))
        assertNull(PageExtractor.cleanAuthor("Jane Doe is a writer who lives in Lisbon with her two cats and a large collection of plants"))
        assertNull(PageExtractor.cleanAuthor("x".repeat(81)))
        assertNull(PageExtractor.cleanAuthor(" "))
    }

    /** Shaped like New Left Review: notes in the article's <footer>, each mostly its link back, which Readability drops. */
    @Test
    fun footnotesInTheArticlesFooterAllCome() {
        val refs = (1..12).joinToString("") { "<p>$sentence<a href=\"#note-$it\" id=\"reference-$it\"><sup>$it</sup></a></p>" }
        val notes = (1..12).joinToString("") { "<div id=\"note-$it\"><a href=\"#reference-$it\">$it</a> Raymond Williams, <em>Culture and Society</em>, London 1958, p. $it.</div>" }
        val html = """<html><head><title>Story</title></head><body><article><h1>Story</h1><div class="article-body">$refs</div>
            <footer class="article-footnotes">$notes</footer></article><footer id="site-footer"><p>About us</p></footer></body></html>"""

        val page = PageExtractor.extract(html, url)
        for (n in 1..12) assertTrue("note $n", "id=\"note-$n\"" in page.html)
    }

    /** A magazine's own paywall: the free part, then a sign-in form inside the article. */
    @Test
    fun aSignInFormInTheArticleIsAPaywall() {
        val text = (1..8).joinToString("") { "<p>$sentence</p>" }
        val form = """<form action="/session"><input type="email"><input type="password"></form>"""
        fun page(body: String) = "<html><head><title>Story</title></head><body>$body</body></html>"

        val offer = "<ul><li><a href=\"/subscriptions/new\">Subscribe for instant access</a></li><li><a href=\"/buy\">Buy this article</a></li></ul>"
        assertTrue(PageExtractor.extract(page("<article>$text<div id=\"access-options\"><div>$form</div>$offer</div></article>"), url).paywalled)
        assertFalse("a form to log in and comment", PageExtractor.extract(page("<article>$text<div><h3>Log in to comment</h3>$form</div></article>"), url).paywalled)
        assertFalse("a sign-in form in the site's header", PageExtractor.extract(page("<header>$form</header><article>$text</article>"), url).paywalled)
        assertFalse("one of several articles, as on an index", PageExtractor.extract(page("<article>$text$form</article><article>$text</article>"), url).paywalled)
    }

    /** Shaped like Stratechery: a sentence free, a pricing box, and a podcast list in the header Readability prefers once the box is gone. */
    @Test
    fun theFreePartIsWhatSharesThePaywallsBox() {
        val podcasts = (1..6).joinToString("") { "<li><a href=\"/p$it\"><h4>Episode $it, about the week in technology and media</h4></a><p>Podcast | Oct $it</p></li>" }
        val pricing = (1..10).joinToString("") { "<p>$sentence</p>" }
        val html = """<html><head><title>Update</title></head><body><header><ul>$podcasts</ul></header><main>
            <div class="entry-content"><p>Games are being decompiled, but the real risk is new games.</p>
            <div class="passport-marketing-page">$pricing</div></div></main></body></html>"""

        val page = PageExtractor.extract(html, url)
        assertTrue(page.paywalled)
        assertTrue(page.html, "Games are being decompiled" in page.html)
        assertFalse(page.html, "Episode" in page.html || "committee" in page.html)

        // MemberPress's box holds just the pitch and a login form; the free part is outside, and Readability's pick stays.
        val intro = (1..10).joinToString("") { "<p>$sentence</p>" }
        val member = """<html><head><title>Members</title></head><body><main><div class="entry-content">$intro<div class="mp_wrapper">
            <div class="mepr-unauthorized-message"><p>This premium article is available to paid subscribers.</p></div>
            <div class="mepr-login-form-wrap"><form><label>Remember Me</label><input type="password"><a href="/login/?action=forgot_password">Forgot Password</a></form></div>
            </div></div></main></body></html>"""
        assertTrue(HtmlCleaner.countWords(PageExtractor.extract(member, url).html) >= 150)
    }

    /** A page of empty blocks: nested past 512 levels, jsoup lays them side by side too. */
    private fun pageOf(blocks: String) = """<html><head><title>Story</title></head><body><article><h1>Story</h1>
        ${(1..8).joinToString("") { "<p>$sentence</p>" }}$blocks</article></body></html>"""

    private fun secondsToExtract(html: String): Double {
        val started = System.nanoTime()
        val page = PageExtractor.extract(html, url)
        assertTrue(page.html, "argue about the budget" in page.html)
        return (System.nanoTime() - started) / 1e9
    }

    @Test
    fun aPageNestedThousandsDeepIsReadInSeconds() {
        // 120 KB, with little else on the page for Readability to choose: 105 s, as it removed the
        // empty blocks one at a time.
        val html = "<html><body><article><p>Hello there.</p>" + "<div>".repeat(20_000) + "Down here." + "</div>".repeat(20_000) + "</article></body></html>"
        val started = System.nanoTime()
        val page = PageExtractor.extract(html, url)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("$seconds s", seconds < 10)
        assertTrue(page.html, "Down here." in page.html)
    }

    @Test
    fun aPageOfAHundredThousandEmptyBlocksIsReadInSeconds() {
        // 1.1 MB, a fifth of what a page may be. Readability removing them one at a time: 53 s.
        val seconds = secondsToExtract(pageOf("<div></div>".repeat(100_000)))
        assertTrue("$seconds s", seconds < 10)
    }
}
