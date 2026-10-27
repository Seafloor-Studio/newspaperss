package com.app.newspaperss.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.ui.components.EpisodeJudgement
import com.app.newspaperss.testutil.writeEpub
import androidx.compose.ui.test.assertIsDisplayed
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.app.newspaperss.settings.PreviewTextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import com.app.newspaperss.ui.edition.EpubPages
import com.app.newspaperss.ui.edition.forPreview
import com.app.newspaperss.ui.edition.imageSizes
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.ui.edition.bookResponse
import com.app.newspaperss.ui.edition.BOOK_ORIGIN
import com.app.newspaperss.ui.edition.bookPage
import com.app.newspaperss.ui.edition.withNextLine
import com.app.newspaperss.ui.edition.lookIssueUrl
import com.app.newspaperss.ui.edition.ArticleLink
import androidx.compose.ui.test.junit4.StateRestorationTester
import android.content.Intent
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertTrue
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ArticlePreviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theScreenShowsBeforeTheEditionIsReadAndSaysSoIfItsFileIsGone() {
        val file = CompletableDeferred<File?>()
        compose.setContent { ArticlePreviewScreen(loadFile = { file.await() }, position = 0, title = "A story", onBack = {}) }

        // Still reading: the tap has visibly done something, and it isn't reported as missing yet.
        compose.onNodeWithText("A story").assertIsDisplayed()
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()

        file.complete(null)
        idleUntil { compose.onAllNodesWithText("file is gone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("file is gone", substring = true).assertIsDisplayed()
    }

    @Test
    fun anEditionOnDiskIsShownNotReportedMissing() {
        val file = tmp.newFile("e.epub")
        val article = EditionArticle(title = "A story", sourceTitle = "S", url = "https://a.example/", bodyHtml = "<p>x</p>", minutes = 1.0)
        file.writeEpub(listOf(article))
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }

        // Once the page is up, the file has been read.
        webView()
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()
    }

    @Test
    fun everyPageThePreviewShowsGetsTheMarginsAndColoursTheEReaderWouldAdd() {
        val file = tmp.newFile("m.epub")
        val articles = listOf("One", "Two").map {
            EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 1.0)
        }
        file.writeEpub(articles)
        val style = "body { margin: 0 5%; background: #1C1B1F; color: #E6E1E5; }"
        val dark = 0xFF1C1B1F.toInt() to 0xFFE6E1E5.toInt()
        EpubPages(file).use { pages ->
            val opened = forPreview(pages.article(0)!!, dark.first, dark.second)
            // After the book's stylesheet, so it wins over the book's own body rule.
            assertTrue(opened.indexOf("stylesheet") in 0 until opened.indexOf(style))
            // The second article is reached by the first one's "Next" link, served page by page.
            val next = bookResponse(BOOK_ORIGIN + EpubPages.articleHref(1), pages, dark.first, dark.second, justify = true).second.toString(Charsets.UTF_8)
            assertTrue(next.contains(style))
        }
    }

    private fun png(width: Int, height: Int): ByteArray =
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun onlyLargePicturesStandingAloneFillTheWidth() {
        val file = tmp.newFile("i.epub")
        val images = mapOf("strip" to png(300, 1000), "wide" to png(600, 200), "headshot" to png(120, 120), "a" to png(600, 400), "b" to png(600, 400))
            .map { (name, bytes) -> EpubImage("images/$name.png", "image/png", bytes) }
        val body = "<figure><img src=\"images/strip.png\" alt=\"\"/></figure>" +
            "<figure><img src=\"images/headshot.png\" alt=\"\"/></figure>" +
            "<figure><img src=\"images/a.png\" alt=\"\"/><img src=\"images/b.png\" alt=\"\"/></figure>" +
            "<p>Inline <img src=\"images/wide.png\" alt=\"\"/> in a line.</p>"
        val articles = listOf(
            EditionArticle(title = "One", sourceTitle = "S", url = "https://a.example/1", bodyHtml = body, minutes = 1.0, images = images),
            EditionArticle(title = "Two", sourceTitle = "S", url = "https://a.example/2", bodyHtml = "<img src=\"images/solo.png\" alt=\"\"/>", minutes = 1.0, images = listOf(EpubImage("images/solo.png", "image/png", png(600, 200)))),
        )
        file.writeEpub(articles)
        EpubPages(file).use { pages ->
            fun filled(xhtml: String): List<String> {
                // Still XHTML a strict parser takes: the WebView loads it as application/xhtml+xml.
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xhtml.byteInputStream())
                val imgs = doc.getElementsByTagName("img")
                return (0 until imgs.length).map { imgs.item(it) as org.w3c.dom.Element }
                    .filter { it.getAttribute("class") == "preview-fill" }.map { it.getAttribute("src") }
            }
            val first = forPreview(pages.article(0)!!, 0, 0, imageSizes(pages))
            assertEquals("a headshot, a row of pictures and an inline one keep their size", listOf("images/strip.png"), filled(first))
            // A page reached by "Next": an article that is just the picture.
            val next = bookResponse(BOOK_ORIGIN + EpubPages.articleHref(1), pages, 0, 0, justify = true).second.toString(Charsets.UTF_8)
            assertEquals(listOf("images/solo.png"), filled(next))
            assertTrue(next.contains("img.preview-fill { width: 100%; }"))
        }
    }

    private fun oneArticleEdition(url: String = "https://a.example/"): File {
        val file = tmp.newFile("z.epub")
        val article = EditionArticle(title = "A story", sourceTitle = "S", url = url, bodyHtml = "<p>x</p>", minutes = 1.0)
        file.writeEpub(listOf(article))
        return file
    }

    private fun findWebView(): WebView? {
        fun find(view: View): WebView? = view as? WebView ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
        return find(compose.activity.window.decorView)
    }

    private fun webView(): WebView {
        var found: WebView? = null
        // The edition is read in the background; its WebView appears in the next frame, which only
        // waitForIdle lets Compose draw.
        idleUntil { compose.waitForIdle(); found = findWebView(); found != null }
        return found!!
    }

    private val leftAligned = ".article-body p { text-align: start; }"

    @Test
    fun theTextSizeFromSettingsIsAppliedInPlaceAndLeftAlignsFromLarger() {
        val file = oneArticleEdition()
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size) }
        val view = webView()
        assertEquals(100, view.settings.textZoom)
        assertFalse(shadowOf(view).lastLoadDataWithBaseURL.data.contains(leftAligned))

        size = PreviewTextSize.LARGE
        compose.waitForIdle()
        assertEquals(120, view.settings.textZoom)
        assertSame("the same page, so the reader keeps their place", view, webView())

        size = PreviewTextSize.LARGEST
        compose.waitForIdle()
        assertSame("restyled in place, so Share still follows the page on screen", view, webView())
        assertEquals(175, view.settings.textZoom)
        assertTrue("justified text opens wide gaps at this size", shadowOf(view).lastLoadDataWithBaseURL.data.contains(leftAligned))

        size = PreviewTextSize.DEFAULT
        compose.waitForIdle()
        assertSame(view, webView())
        assertFalse(shadowOf(view).lastLoadDataWithBaseURL.data.contains(leftAligned))
    }

    @Test
    fun aNewAlignmentRestylesThePageOnScreenNotTheFirst() {
        val file = tmp.newFile("n.epub")
        val articles = listOf("One", "Two").map {
            EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 30.0)
        }
        file.writeEpub(articles)
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}, textSize = size) }
        val view = webView()
        val client = shadowOf(view).webViewClient
        val first = BOOK_ORIGIN + EpubPages.articleHref(0)

        // A real WebView reports the first page, loaded as data, as about:blank.
        client.doUpdateVisitedHistory(view, "about:blank", false)
        size = PreviewTextSize.LARGER
        compose.waitForIdle()
        assertEquals("the first page itself, not about:blank", first, shadowOf(view).lastLoadDataWithBaseURL.baseUrl)
        assertTrue(shadowOf(view).lastLoadDataWithBaseURL.data.contains(leftAligned))

        // "Next", then a footnote on that page: the page itself is served again, restyled.
        val second = BOOK_ORIGIN + EpubPages.articleHref(1)
        client.doUpdateVisitedHistory(view, second, false)
        client.doUpdateVisitedHistory(view, "$second#a2-fn1", false)
        size = PreviewTextSize.DEFAULT
        compose.waitForIdle()
        assertEquals(second, shadowOf(view).lastLoadedUrl)
        assertSame(view, webView())
    }

    @Test
    fun theLineAboutAnEpisodeFollowsTheArticleOnScreen() {
        val file = tmp.newFile("n.epub")
        file.writeEpub(listOf("Episode 12", "An essay").map { EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 3.0) })
        val note = "Episode notes. Listen reads them, then plays the episode after the edition."
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "Episode 12", onBack = {}, episodeFor = { if (it == 0) EpisodeJudgement(EpisodeKind.NOTES) else null })
        }
        val view = webView()
        compose.onNodeWithText(note, substring = true).assertIsDisplayed()

        // "Next" to an article that isn't an episode post.
        val client = shadowOf(view).webViewClient
        client.doUpdateVisitedHistory(view, BOOK_ORIGIN + EpubPages.articleHref(1), false)
        idleUntil { compose.onAllNodesWithText(note, substring = true).fetchSemanticsNodes().isEmpty() }
        client.doUpdateVisitedHistory(view, BOOK_ORIGIN + EpubPages.articleHref(0), false)
        idleUntil { compose.onAllNodesWithText(note, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aSizePickedWithAaIsSavedAndMarked() {
        val file = oneArticleEdition()
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        val picked = mutableListOf<PreviewTextSize>()
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size, onTextSize = { picked += it; size = it })
        }
        val view = webView()

        compose.onNodeWithContentDescription("Text size").performClick()
        compose.onNodeWithText("Largest").performClick()
        compose.waitForIdle()

        assertEquals(listOf(PreviewTextSize.LARGEST), picked)
        assertEquals(175, view.settings.textZoom)
        assertSame("the same page, so the reader keeps their place", view, webView())
        // The menu marks the current size.
        compose.onNodeWithContentDescription("Text size").performClick()
        compose.onNode(hasText("Largest") and hasContentDescription("Current size")).assertExists()
        compose.onNode(hasText("Default") and hasContentDescription("Current size")).assertDoesNotExist()
    }

    @Test
    fun aPageReachedByNextIsAlignedLikeTheFirst() {
        val file = oneArticleEdition()
        EpubPages(file).use { pages ->
            fun page(justify: Boolean) = bookResponse(BOOK_ORIGIN + EpubPages.articleHref(0), pages, 0, 0, justify).second.toString(Charsets.UTF_8)
            assertTrue(page(justify = false).contains(leftAligned))
            assertFalse(page(justify = true).contains(leftAligned))
        }
    }

    @Test
    fun thePageWaitsForTheStoredTextSizeRatherThanLayingOutTwice() {
        val file = oneArticleEdition()
        var size by mutableStateOf<PreviewTextSize?>(null)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size) }
        // Share shows once the article has been read: from then on only the size is missing.
        idleUntil { compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Opening…").assertExists()
        assertEquals(null, findWebView())

        size = PreviewTextSize.LARGEST
        compose.waitForIdle()
        assertEquals(175, webView().settings.textZoom)
        compose.onNodeWithText("Opening…").assertDoesNotExist()
    }

    @Test
    @Config(fontScale = 1.5f)
    fun theTextSizeFollowsAndroidsFontSize() {
        val file = oneArticleEdition()
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size) }
        val view = webView()
        assertEquals(150, view.settings.textZoom)
        assertTrue("as large as Larger, so left-aligned too", shadowOf(view).lastLoadDataWithBaseURL.data.contains(leftAligned))

        size = PreviewTextSize.LARGER
        compose.waitForIdle()
        assertEquals(218, view.settings.textZoom)
    }

    @Test
    fun thePreviewCanBePinchedToZoomWithoutButtonsOverThePage() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        val settings = webView().settings
        assertTrue(settings.builtInZoomControls)
        assertFalse(settings.displayZoomControls)
    }

    @Test
    fun shareSendsTheOriginalsLinkToTheShareSheet() {
        val file = oneArticleEdition(url = "https://www.a.example/2026/a-story?ref=feed")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "Shown title", onBack = {}) }
        idleUntil { compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription("Share link").performClick()

        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("the whole address, not the host the page shows", "https://www.a.example/2026/a-story?ref=feed", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("A story", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun selectedTextSharedFromThePageSaysWhereItsFrom() {
        val file = oneArticleEdition(url = "https://www.a.example/2026/a-story")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "Shown title", onBack = {}) }
        val view = webView()
        idleUntil { compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().isNotEmpty() }

        // What the WebView's selection menu does for Share: a share of just the text, started
        // through the context the WebView was made with.
        val quote = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "A line worth keeping.")
        view.context.startActivity(Intent.createChooser(quote, "Share"))

        @Suppress("DEPRECATION")
        val send = shadowOf(compose.activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("A line worth keeping.\n\nhttps://www.a.example/2026/a-story", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("A story", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun anArticleWithoutAWebLinkHasNoShareButton() {
        val file = oneArticleEdition(url = "")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        webView()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Text size").assertExists()
        compose.onNodeWithContentDescription("Share link").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
    }

    private fun askForALook() {
        idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Send for a look…").performClick()
    }

    @Test
    fun sendForALookSaysTheIssueIsPublicBeforeOpeningGithubsFormFilledIn() {
        val file = oneArticleEdition(url = "https://www.a.example/2026/a-story?ref=feed&x=1")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "Shown title", onBack = {}) }
        askForALook()

        compose.onNodeWithText("public issue on GitHub", substring = true).assertIsDisplayed()
        assertEquals("nothing opens before the reader agrees", null, shadowOf(compose.activity).peekNextStartedActivity())

        compose.onNodeWithText("Open GitHub").performClick()
        val opened = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, opened.action)
        val form = opened.data!!
        assertEquals("https://github.com/Seafloor-Studio/newspaperss/issues/new", "${form.scheme}://${form.host}${form.path}")
        assertEquals("Extraction: A story", form.getQueryParameter("title"))
        val body = form.getQueryParameter("body")!!
        assertTrue(body, body.contains("Article: https://www.a.example/2026/a-story?ref=feed&x=1\n"))
        assertTrue(body, body.contains("App version: "))
        compose.onNodeWithText("public issue on GitHub", substring = true).assertDoesNotExist()
    }

    @Test
    fun aWrongLabelIsSentWithWhatThePostIsOnceTheReaderHasSaid() {
        val file = oneArticleEdition(url = "https://a.example/episode-12")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, episodeFor = { EpisodeJudgement(EpisodeKind.NOTES) }) }
        askForALook()

        compose.onNodeWithText("It's labelled “Episode notes”, and it's…").performClick()
        compose.onNodeWithText("Episode notes").assertDoesNotExist()
        compose.onNodeWithText("Open GitHub").assertIsNotEnabled()
        compose.onNodeWithText("An article").performClick()
        compose.onNodeWithText("Open GitHub").performClick()

        val form = shadowOf(compose.activity).nextStartedActivity.data!!
        assertEquals("Episode label: A story", form.getQueryParameter("title"))
        assertEquals("episodes", form.getQueryParameter("labels"))
        val body = form.getQueryParameter("body")!!
        assertTrue(body, body.startsWith("Post: https://a.example/episode-12\nLabelled: notes\nShould be: article\nApp version: "))
    }

    @Test
    fun theArticleCanStillBeSentForALookWhenItsLabelIsRight() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, episodeFor = { EpisodeJudgement(EpisodeKind.TRANSCRIPT) }) }
        askForALook()
        compose.onNodeWithText("The article came out wrong").assertIsSelected()
        compose.onNodeWithText("Open GitHub").performClick()
        assertEquals("Extraction: A story", shadowOf(compose.activity).nextStartedActivity.data!!.getQueryParameter("title"))
    }

    @Test
    fun aPickedLabelSurvivesTheScreenBeingRecreated() {
        val file = oneArticleEdition()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, episodeFor = { EpisodeJudgement(EpisodeKind.ARTICLE) }) }
        askForALook()
        compose.onNodeWithText("It's taken for an ordinary article, but it's…").performClick()
        compose.onNodeWithText("A transcript").performClick()

        restoration.emulateSavedInstanceStateRestore()

        idleUntil { compose.onAllNodesWithText("A transcript").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A transcript").assertIsSelected()
        compose.onNodeWithText("Open GitHub").assertIsEnabled()
    }

    @Test
    fun afterARotationTheLabelSentIsTheLinkedArticlesNotTheOneLastOnScreen() {
        val file = tmp.newFile("n.epub")
        file.writeEpub(listOf("An essay", "Episode 12").map { EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 3.0) })
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "An essay", onBack = {}, episodeFor = { if (it == 1) EpisodeJudgement(EpisodeKind.NOTES) else null })
        }
        val view = webView()
        shadowOf(view).webViewClient.doUpdateVisitedHistory(view, BOOK_ORIGIN + EpubPages.articleHref(1), false)
        idleUntil { compose.onAllNodesWithText("Episode notes", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Restored, and asked before the page reports itself: the link is still the essay's.
        restoration.emulateSavedInstanceStateRestore()
        askForALook()

        compose.onNodeWithText("Send this article for a look?").assertIsDisplayed()
        compose.onNode(hasText("Episode notes", substring = true) and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }

    @Test
    fun cancellingSendForALookOpensNothing() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        askForALook()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("public issue on GitHub", substring = true).assertDoesNotExist()
        assertEquals(null, shadowOf(compose.activity).peekNextStartedActivity())
    }

    @Test
    fun theSendForALookQuestionSurvivesTheScreenBeingRecreated() {
        val file = oneArticleEdition()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        askForALook()

        restoration.emulateSavedInstanceStateRestore()

        idleUntil { compose.onAllNodesWithText("public issue on GitHub", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aVeryLongArticleAddressIsLeftForTheReaderToPasteRatherThanOverfillingTheForm() {
        val long = "https://a.example/" + "x".repeat(9_000)
        val form = android.net.Uri.parse(lookIssueUrl(ArticleLink("T".repeat(300), long), "1.0"))
        val body = form.getQueryParameter("body")!!
        assertFalse(body.contains(long))
        assertTrue(body.contains("please paste it here"))
        assertTrue(form.getQueryParameter("title")!!.length <= "Extraction: ".length + 120)
        assertTrue(form.toString().length <= 8_000)
    }

    @Test
    fun theFormsLimitIsOnItsEncodedLengthAndATitleIsCutOnWholeCharacters() {
        // Short enough written out, but each %XX becomes %25XX in the form's address.
        val encoded = "https://a.example/" + "%E6%97%A5".repeat(480)
        val title = "日本".repeat(59) + "😀🙂x"
        val form = android.net.Uri.parse(lookIssueUrl(ArticleLink(title, encoded), "1.0"))
        assertTrue(form.toString().length <= 8_000)
        assertTrue(form.getQueryParameter("body")!!.contains("please paste it here"))
        val shown = form.getQueryParameter("title")!!.removePrefix("Extraction: ")
        assertEquals("日本".repeat(59) + "😀…", shown)

        val short = android.net.Uri.parse(lookIssueUrl(ArticleLink("T", "https://a.example/" + "%E6%97%A5".repeat(10)), "1.0"))
        assertTrue("a short encoded address is kept", short.getQueryParameter("body")!!.contains("%E6%97%A5"))
    }

    private fun sharedLink(): Pair<String?, String?> {
        compose.onNodeWithContentDescription("Share link").performClick()
        @Suppress("DEPRECATION")
        val send = shadowOf(compose.activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        return send.getStringExtra(Intent.EXTRA_SUBJECT) to send.getStringExtra(Intent.EXTRA_TEXT)
    }

    @Test
    fun listenFromHereStartsTheArticleOnScreen() {
        val file = tmp.newFile("l.epub")
        val articles = listOf("One", "Two").map {
            EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 30.0)
        }
        file.writeEpub(articles)
        val asked = mutableListOf<Int>()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}, onListen = { asked += it }) }
        idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()

        // Followed "Next" to the second article: Listen starts there, not where the preview opened.
        val view = webView()
        val client = shadowOf(view).webViewClient
        client.doUpdateVisitedHistory(view, BOOK_ORIGIN + EpubPages.articleHref(1) + "#a2-fn1", false)
        idleUntil { compose.onNode(hasText("Two")).isDisplayed() }
        idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()
        assertEquals(listOf(0, 1), asked)

        fun offered() = compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty()
        // The paper's closing page isn't an article to listen from; nor is a page the book doesn't
        // have, which a link in a feed's article could point the preview at.
        for (page in listOf("end.xhtml", "article-999.xhtml", "x/article-002.xhtml", "article-2.xhtml")) {
            client.doUpdateVisitedHistory(view, BOOK_ORIGIN + page, false)
            compose.waitForIdle()
            idleUntil { !offered() }
        }
    }

    @Test
    fun withoutListeningThereIsNoListenButton() {
        val file = tmp.newFile("q.epub")
        file.writeEpub(listOf(EditionArticle(title = "One", sourceTitle = "S", url = "https://a.example/1", bodyHtml = "<p>x</p>", minutes = 2.0)))
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}) }
        webView()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().size)
    }

    @Test
    fun sharingFollowsThePageOnScreen() {
        val file = tmp.newFile("n.epub")
        // Long enough that the first ends with a "Next" link to the second.
        val articles = listOf("One", "Two").map {
            EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 30.0)
        }
        file.writeEpub(articles)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}) }
        val view = webView()
        val client = shadowOf(view).webViewClient
        val first = BOOK_ORIGIN + EpubPages.articleHref(0)
        assertEquals("loaded at its own address, so in-page links resolve to it", first, shadowOf(view).lastLoadDataWithBaseURL.baseUrl)

        // The first load, and a footnote on the same page, keep its link.
        client.doUpdateVisitedHistory(view, "about:blank", false)
        client.doUpdateVisitedHistory(view, "$first#a1-fn1", false)
        idleUntil { compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("One" to "https://a.example/One", sharedLink())

        client.doUpdateVisitedHistory(view, BOOK_ORIGIN + EpubPages.articleHref(1), false)
        // The new page's link is read off the main thread. This fails now and then in CI only, so
        // a timeout says what Share last gave rather than just that it wasn't the second page's.
        var last: Pair<String?, String?>? = null
        val polls = intArrayOf(0)
        runCatching { idleUntil { polls[0]++; sharedLink().also { last = it }.second == "https://a.example/Two" } }
            .onFailure { throw AssertionError("Share still gave $last after ${polls[0]} polls", it) }
        assertEquals("Two" to "https://a.example/Two", sharedLink())
        compose.onNodeWithText("Two").assertExists()
        compose.onNodeWithText("One").assertDoesNotExist()

        EpubPages(file).use { pages -> assertEquals(null, bookPage(BOOK_ORIGIN + "style.css", pages)) }
    }

    @Test
    fun everyArticleEndsInALineToTheNextAndTheLastSaysThePaperEnds() {
        val file = tmp.newFile("next.epub")
        // In two sections, so the line crosses from one to the next.
        val first = listOf(
            EditionArticle(title = "Short", sourceTitle = "S", url = "", bodyHtml = "<p>x</p>", minutes = 2.0),
            EditionArticle(title = "Long & slow", sourceTitle = "S", url = "", bodyHtml = "<p>x</p>", minutes = 30.0),
        )
        val second = listOf(
            EditionArticle(title = "שלום", sourceTitle = "T", url = "", bodyHtml = "<p>x</p>", minutes = 3.0, language = "he"),
            EditionArticle(title = "Untimed", sourceTitle = "", url = "", bodyHtml = "<p>x</p>", minutes = 0.0),
        )
        file.outputStream().use {
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:5", listOf(EditionSection("One", first), EditionSection("Two", second))), it)
        }
        EpubPages(file).use { pages ->
            fun served(position: Int) = bookResponse(BOOK_ORIGIN + EpubPages.articleHref(position), pages, 0, 0, justify = true).second.toString(Charsets.UTF_8)
            fun navLines(page: String) = Regex("<p class=\"article-nav\">.*?</p>").findAll(page).map { it.value }.toList()

            val short = navLines(served(0))
            assertEquals("a short article, which the book doesn't link on, gets one line", 1, short.size)
            assertTrue(short.single(), short.single().contains("href=\"${EpubPages.articleHref(1)}\""))
            assertTrue(short.single(), short.single().contains("Next: <span class=\"title\">Long &amp; slow</span>"))
            assertTrue("with the minutes, and an arrow forward", short.single().contains("· S · 30 min") && short.single().contains("&#8594;"))

            val long = navLines(served(1))
            assertEquals("the book's own line after a long article is replaced, not doubled", 1, long.size)
            assertTrue(long.single(), long.single().contains("<span class=\"title\" lang=\"he\" xml:lang=\"he\" dir=\"rtl\">שלום</span>"))

            assertTrue("no source or minutes: no dangling dot", navLines(served(2)).single().contains("<span class=\"title\">Untimed</span><span aria-hidden"))
            assertEquals(listOf("<p class=\"article-nav\">That's all for today.</p>"), navLines(served(3)))
            for (position in 0..3) {
                // Served as XHTML: one bad entity and the WebView shows an error instead of the page.
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(served(position).byteInputStream())
            }
            assertTrue("the first page, loaded directly, gets it too", navLines(withNextLine(pages.article(1)!!, EpubPages.articleHref(1), pages)).single().contains("שלום"))

            val contents = pages.entry("OEBPS/contents.xhtml")!!.toString(Charsets.UTF_8)
            assertEquals("not an article: left as it is", contents, withNextLine(contents, "contents.xhtml", pages))
        }
    }

    @Test
    fun goToTheSourceNamesWhereThePageOnScreenCameFromAndOpensIt() {
        val file = tmp.newFile("g.epub")
        // Long enough that the first article ends with a "Next" link to the second.
        val articles = listOf("One", "Two").map {
            EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = "<p>x</p>", minutes = 30.0)
        }
        file.writeEpub(articles)
        val names = listOf("Quanta Magazine", "Longreads")
        val opened = mutableListOf<Int>()
        compose.setContent {
            ArticlePreviewScreen(
                loadFile = { file },
                position = 0,
                title = "One",
                onBack = {},
                sourceAt = { names.getOrNull(it) },
                onOpenSource = { opened += it },
            )
        }
        idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Quanta Magazine").performClick()
        assertEquals(listOf(0), opened)

        // Followed "Next" to the second article: it names that one's source, as Share and Listen do.
        val view = webView()
        shadowOf(view).webViewClient.doUpdateVisitedHistory(view, BOOK_ORIGIN + EpubPages.articleHref(1), false)
        idleUntil { compose.onNode(hasText("Two")).isDisplayed() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Longreads").performClick()
        assertEquals(listOf(0, 1), opened)
    }

    @Test
    fun withNoSourceToGoToTheMenuLeavesGoToOut() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, sourceAt = { null }, onOpenSource = {}) }
        idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()

        assertEquals(0, compose.onAllNodesWithText("Go to", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("Send for a look…").assertIsDisplayed()
    }

    @Test
    fun anArticleWithNoWebLinkStillOffersGoToItsSource() {
        val file = tmp.newFile("w.epub")
        // A feed whose item links to something that isn't a web address: the book prints it as
        // plain text ("Original: urn:uuid:…") rather than a link, so there is nothing to share.
        file.writeEpub(listOf(EditionArticle(title = "A story", sourceTitle = "S", url = "urn:uuid:5b1f3c8e-0000-4000-8000-000000000001", bodyHtml = "<p>x</p>", minutes = 2.0)))
        val opened = mutableListOf<Int>()
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, sourceAt = { "Quanta Magazine" }, onOpenSource = { opened += it })
        }
        idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("nothing to share", 0, compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Quanta Magazine").performClick()
        assertEquals(listOf(0), opened)
    }

    @Test
    fun withNeitherALinkNorASourceThereIsNoMenuAtAll() {
        val file = tmp.newFile("e.epub")
        file.writeEpub(listOf(EditionArticle(title = "A story", sourceTitle = "S", url = "urn:uuid:5b1f3c8e-0000-4000-8000-000000000002", bodyHtml = "<p>x</p>", minutes = 2.0)))
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, sourceAt = { null }, onOpenSource = {}) }
        webView()

        // An empty ⋮ would open onto nothing, so there is no ⋮.
        assertEquals(0, compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().size)
    }
}
