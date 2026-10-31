package com.app.newspaperss.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EpubImage
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.writeEpub
import com.app.newspaperss.ui.components.EpisodeJudgement
import com.app.newspaperss.ui.edition.ArticleLink
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import com.app.newspaperss.ui.edition.EpubPages
import com.app.newspaperss.ui.edition.fillsWidth
import com.app.newspaperss.ui.edition.lookIssueUrl
import com.app.newspaperss.ui.edition.quoteOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.app.newspaperss.ui.edition.withNextLine
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** The preview, drawn by the app from the book's page: the article, its pictures and its "Next" line. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ArticlePreviewScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private fun oneArticleEdition(url: String = "https://a.example/"): File {
        val file = tmp.newFile("z.epub")
        val article = EditionArticle(title = "A story", sourceTitle = "S", url = url, bodyHtml = "<p>x</p>", minutes = 1.0)
        file.writeEpub(listOf(article))
        return file
    }

    /** Two articles, each long enough to end with the book's own "Next" line. */
    private fun twoArticleEdition(vararg titles: String = arrayOf("One", "Two"), body: String = "<p>x</p>"): File {
        val file = tmp.newFile("two.epub")
        file.writeEpub(titles.map { EditionArticle(title = it, sourceTitle = "S", url = "https://a.example/$it", bodyHtml = body, minutes = 30.0) })
        return file
    }

    /** Waits for an article's page to be drawn: every one ends in the book's end mark. */
    private fun pageShown() = compose.idleUntil { compose.onAllNodes(hasText("■"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Follows the "Next" line, as a tap on it or TalkBack's action does. */
    private fun next() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next:", substring = true))
        val actions = compose.onNodeWithText("Next:", substring = true).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label.startsWith("Go to ") }.action() }
    }

    /** Waits for the article titled [title]: in the top bar and at the head of the page. */
    private fun onArticle(title: String) = compose.idleUntil { compose.onAllNodesWithText(title).fetchSemanticsNodes().size == 2 }

    /** How the paragraph reading [text] is laid out. */
    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    @Test
    fun anEditionOnDiskIsShownNotReportedMissing() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        pageShown()
        compose.onNodeWithText("x").assertIsDisplayed()
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()
    }

    @Test
    fun theArticleIsDrawnAsWrittenWithItsByline() {
        val file = tmp.newFile("f.epub")
        file.writeEpub(listOf(EditionArticle(title = "A story", sourceTitle = "Quanta", url = "https://a.example/", author = "Erica Klarreich", bodyHtml = "<p>Plain <b>bold</b>.</p><ol start=\"3\"><li>Three</li></ol>", minutes = 1.0)))
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        pageShown()
        // The source in capitals, as the book has it, but read as written.
        compose.onNodeWithText("QUANTA", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Quanta", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("By Erica Klarreich", substring = true).assertIsDisplayed()
        val plain = compose.onNodeWithText("Plain bold.", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(listOf("bold"), plain.spanStyles.filter { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold }.map { plain.text.substring(it.start, it.end) })
        compose.onNodeWithContentDescription("3.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Read the original at", substring = true).assertExists()
    }

    private fun png(width: Int, height: Int): ByteArray =
        java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun onlyLargePicturesStandingAloneFillTheWidth() {
        val file = tmp.newFile("i.epub")
        val sizes = mapOf("strip" to (300 to 1000), "wide" to (600 to 200), "headshot" to (120 to 120), "a" to (600 to 400), "b" to (600 to 400))
        val images = sizes.map { (name, size) -> EpubImage("images/$name.png", "image/png", png(size.first, size.second)) }
        val body = "<figure><img src=\"images/strip.png\" alt=\"\"/></figure>" +
            "<figure><img src=\"images/headshot.png\" alt=\"\"/></figure>" +
            "<figure><img src=\"images/a.png\" alt=\"\"/><img src=\"images/b.png\" alt=\"\"/></figure>" +
            "<p>Inline <img src=\"images/wide.png\" alt=\"\"/> in a line.</p>"
        val articles = listOf(
            EditionArticle(title = "One", sourceTitle = "S", url = "https://a.example/1", bodyHtml = body, minutes = 1.0, images = images),
            EditionArticle(title = "Two", sourceTitle = "S", url = "https://a.example/2", bodyHtml = "<img src=\"images/solo.png\" alt=\"\"/>", minutes = 1.0, images = listOf(EpubImage("images/solo.png", "image/png", png(600, 200)))),
        )
        file.writeEpub(articles)
        val all = sizes + ("solo" to (600 to 200))
        EpubPages(file).use { pages ->
            fun filled(position: Int) = ListenScript.parse(pages.article(position)!!).blocks.filterIsInstance<ListenScript.Block.Image>()
                .filter { fillsWidth(it, all[it.src.removePrefix("images/").removeSuffix(".png")]) }.map { it.src }
            assertEquals("a headshot, a row of pictures and an inline one keep their size", listOf("images/strip.png"), filled(0))
            assertEquals("an article that is just the picture", listOf("images/solo.png"), filled(1))
        }
    }

    @Test
    fun theTextSizeFromSettingsIsAppliedAndLeftAlignsFromLarger() {
        val file = oneArticleEdition()
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size) }
        pageShown()
        val base = layoutOf("x").layoutInput.style.fontSize.value
        assertEquals(TextAlign.Justify, layoutOf("x").layoutInput.style.textAlign)

        size = PreviewTextSize.LARGE
        compose.waitForIdle()
        assertEquals(base * 1.2f, layoutOf("x").layoutInput.style.fontSize.value, 0.01f)

        size = PreviewTextSize.LARGEST
        compose.waitForIdle()
        assertEquals(base * 1.75f, layoutOf("x").layoutInput.style.fontSize.value, 0.01f)
        assertTrue("justified text opens wide gaps at this size", layoutOf("x").layoutInput.style.textAlign != TextAlign.Justify)

        size = PreviewTextSize.DEFAULT
        compose.waitForIdle()
        assertEquals(TextAlign.Justify, layoutOf("x").layoutInput.style.textAlign)
    }

    @Test
    @Config(fontScale = 1.5f)
    fun aLargeFontSizeFromAndroidLeftAlignsToo() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = PreviewTextSize.DEFAULT) }
        pageShown()
        assertTrue("as large as Larger, so left-aligned", layoutOf("x").layoutInput.style.textAlign != TextAlign.Justify)
    }

    @Test
    fun aSizePickedWithAaIsSavedAndMarked() {
        val file = oneArticleEdition()
        var size by mutableStateOf(PreviewTextSize.DEFAULT)
        val picked = mutableListOf<PreviewTextSize>()
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size, onTextSize = { picked += it; size = it })
        }
        pageShown()
        val base = layoutOf("x").layoutInput.style.fontSize.value

        compose.onNodeWithContentDescription("Text size").performClick()
        compose.onNodeWithText("Largest").performClick()
        compose.waitForIdle()

        assertEquals(listOf(PreviewTextSize.LARGEST), picked)
        assertEquals(base * 1.75f, layoutOf("x").layoutInput.style.fontSize.value, 0.01f)
        // The menu marks the current size.
        compose.onNodeWithContentDescription("Text size").performClick()
        compose.onNode(hasText("Largest") and hasContentDescription("Current size")).assertExists()
        compose.onNode(hasText("Default") and hasContentDescription("Current size")).assertDoesNotExist()
    }

    @Test
    fun thePageWaitsForTheStoredTextSizeRatherThanLayingOutTwice() {
        val file = oneArticleEdition()
        var size by mutableStateOf<PreviewTextSize?>(null)
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, textSize = size) }
        // Share shows once the article has been read: from then on only the size is missing.
        compose.idleUntil { compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Opening…").assertExists()
        compose.onNodeWithText("x").assertDoesNotExist()

        size = PreviewTextSize.LARGEST
        pageShown()
        compose.onNodeWithText("Opening…").assertDoesNotExist()
    }

    @Test
    fun theNextLineTurnsToTheNextArticleAndTheLineAboutAnEpisodeFollowsIt() {
        val file = twoArticleEdition("Episode 12", "An essay")
        val note = "Episode notes. Listen reads them, then plays the episode after the edition."
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "Episode 12", onBack = {}, episodeFor = { if (it == 0) EpisodeJudgement(EpisodeKind.NOTES) else null })
        }
        pageShown()
        compose.onNodeWithText(note, substring = true).assertIsDisplayed()

        next()
        onArticle("An essay")
        compose.onNodeWithText(note, substring = true).assertDoesNotExist()
        // The last says the paper ends, with nowhere further to go.
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("That's all for today.", substring = true))
    }

    /** The whole "Next" line is the link, a finger tall, not only its words: a tap at its far end turns too. */
    @Test
    fun theWholeNextLineTurnsThePage() {
        val file = twoArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}) }
        pageShown()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Next:", substring = true))
        val line = compose.onNode(hasText("Next:", substring = true) and hasClickAction())
        assertTrue(line.fetchSemanticsNode().size.height >= with(compose.density) { 48.dp.roundToPx() })
        line.performTouchInput { click(centerRight.copy(x = right - 2f)) }
        onArticle("Two")
    }

    @Test
    fun aQuoteKeepsAListItemsNumberWithItsWords() {
        assertEquals("Before.\n1. First item\n2. Second item", quoteOf(listOf("Before.", "1. ", "First item", "2. ", "Second item")))
        assertEquals("Crossings\nKnots", quoteOf(listOf("Crossings", "Knots")))
    }

    @Test
    fun aFootnotesLinkGoesToItsNote() {
        val file = tmp.newFile("fn.epub")
        val filler = "<p>A paragraph that takes room on the page.</p>".repeat(40)
        file.writeEpub(listOf(EditionArticle(title = "A story", sourceTitle = "S", url = "https://a.example/", bodyHtml = "<p>A claim.<sup><a href=\"#fn1\">1</a></sup></p>$filler<p id=\"fn1\">The source of the claim.</p>", minutes = 1.0)))
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        compose.idleUntil { compose.onAllNodesWithText("A claim.1").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(compose.onNode(hasText("The source of the claim.")).isDisplayed())

        val actions = compose.onNodeWithText("A claim.1").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.single { it.label == "Go to note 1" }.action() }
        compose.idleUntil { compose.onAllNodesWithText("The source of the claim.").fetchSemanticsNodes().isNotEmpty() && compose.onNode(hasText("The source of the claim.")).isDisplayed() }
    }

    @Test
    fun theArticleOnScreenSurvivesTheScreenBeingRecreated() {
        val file = twoArticleEdition()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}) }
        pageShown()
        next()
        onArticle("Two")

        restoration.emulateSavedInstanceStateRestore()
        compose.idleUntil { compose.onAllNodes(hasText("Two")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("One").assertDoesNotExist()
    }

    @Test
    fun anArticleWithoutAWebLinkHasNoShareButton() {
        val file = oneArticleEdition(url = "")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        pageShown()
        compose.onNodeWithContentDescription("Text size").assertExists()
        compose.onNodeWithContentDescription("Share link").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").assertDoesNotExist()
    }

    private fun askForALook() {
        compose.idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Send for a look…").performClick()
    }

    @Test
    fun afterARotationTheLabelSentIsTheArticleOnScreens() {
        val file = twoArticleEdition("An essay", "Episode 12")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "An essay", onBack = {}, episodeFor = { if (it == 1) EpisodeJudgement(EpisodeKind.NOTES) else null })
        }
        pageShown()
        next()
        compose.idleUntil { compose.onAllNodesWithText("Episode notes", substring = true).fetchSemanticsNodes().isNotEmpty() }

        restoration.emulateSavedInstanceStateRestore()
        askForALook()
        compose.onNode(hasText("Episode notes", substring = true) and hasAnyAncestor(isDialog())).assertExists()
    }

    private fun sharedLink(): Pair<String?, String?> {
        compose.onNodeWithContentDescription("Share link").performClick()
        @Suppress("DEPRECATION")
        val send = shadowOf(compose.activity).nextStartedActivity.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        return send.getStringExtra(Intent.EXTRA_SUBJECT) to send.getStringExtra(Intent.EXTRA_TEXT)
    }

    @Test
    fun listenFromHereStartsTheArticleOnScreen() {
        val file = twoArticleEdition()
        val asked = mutableListOf<Int>()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}, onListen = { asked += it }) }
        compose.idleUntil { compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Listen from here").performClick()

        // Followed "Next" to the second article: Listen starts there, not where the preview opened.
        next()
        onArticle("Two")
        compose.onNodeWithContentDescription("Listen from here").performClick()
        assertEquals(listOf(0, 1), asked)
    }

    @Test
    fun withoutListeningThereIsNoListenButton() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}) }
        pageShown()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Listen from here")).fetchSemanticsNodes().size)
    }

    @Test
    fun sharingFollowsThePageOnScreen() {
        val file = twoArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}) }
        pageShown()
        assertEquals("One" to "https://a.example/One", sharedLink())

        next()
        onArticle("Two")
        assertEquals("Two" to "https://a.example/Two", sharedLink())
        compose.onNodeWithText("One").assertDoesNotExist()
    }

    @Test
    fun goToTheSourceNamesWhereThePageOnScreenCameFromAndOpensIt() {
        val file = twoArticleEdition()
        val names = listOf("Quanta Magazine", "Longreads")
        val opened = mutableListOf<Int>()
        compose.setContent {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = "One", onBack = {}, sourceAt = { names.getOrNull(it) }, onOpenSource = { opened += it })
        }
        compose.idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Quanta Magazine").performClick()
        assertEquals(listOf(0), opened)

        // Followed "Next" to the second article: it names that one's source, as Share and Listen do.
        pageShown()
        next()
        onArticle("Two")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Longreads").performClick()
        assertEquals(listOf(0, 1), opened)
    }

    @Test
    fun withNeitherALinkNorASourceThereIsNoMenuAtAll() {
        val file = tmp.newFile("e.epub")
        file.writeEpub(listOf(EditionArticle(title = "A story", sourceTitle = "S", url = "urn:uuid:5b1f3c8e-0000-4000-8000-000000000002", bodyHtml = "<p>x</p>", minutes = 2.0)))
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, sourceAt = { null }, onOpenSource = {}) }
        pageShown()

        // An empty ⋮ would open onto nothing, so there is no ⋮.
        assertEquals(0, compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().size)
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

        compose.idleUntil { compose.onAllNodesWithText("A transcript").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A transcript").assertIsSelected()
        compose.onNodeWithText("Open GitHub").assertIsEnabled()
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

        compose.idleUntil { compose.onAllNodesWithText("public issue on GitHub", substring = true).fetchSemanticsNodes().isNotEmpty() }
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

    @Test
    fun shareSendsTheOriginalsLinkToTheShareSheet() {
        val file = oneArticleEdition(url = "https://www.a.example/2026/a-story?ref=feed")
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "Shown title", onBack = {}) }
        compose.idleUntil { compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().isNotEmpty() }

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
    fun theScreenShowsBeforeTheEditionIsReadAndSaysSoIfItsFileIsGone() {
        val file = CompletableDeferred<File?>()
        compose.setContent { ArticlePreviewScreen(loadFile = { file.await() }, position = 0, title = "A story", onBack = {}) }

        // Still reading: the tap has visibly done something, and it isn't reported as missing yet.
        compose.onNodeWithText("A story").assertIsDisplayed()
        compose.onNodeWithText("file is gone", substring = true).assertDoesNotExist()

        file.complete(null)
        compose.idleUntil { compose.onAllNodesWithText("file is gone", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("file is gone", substring = true).assertIsDisplayed()
    }

    @Test
    fun withNoSourceToGoToTheMenuLeavesGoToOut() {
        val file = oneArticleEdition()
        compose.setContent { ArticlePreviewScreen(loadFile = { file }, position = 0, title = "A story", onBack = {}, sourceAt = { null }, onOpenSource = {}) }
        compose.idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
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
        compose.idleUntil { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("nothing to share", 0, compose.onAllNodes(hasContentDescription("Share link")).fetchSemanticsNodes().size)

        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Go to Quanta Magazine").performClick()
        assertEquals(listOf(0), opened)
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
            EpubWriter.write(EditionDoc("T", LocalDate.of(2026, 9, 29), "urn:uuid:5", first + second), it)
        }
        EpubPages(file).use { pages ->
            fun served(position: Int) = withNextLine(pages.article(position)!!, EpubPages.articleHref(position), pages)
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
                // Still XHTML: the page is read with an XML parser.
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(served(position).byteInputStream())
            }

            val contents = pages.entry("OEBPS/contents.xhtml")!!.toString(Charsets.UTF_8)
            assertEquals("not an article: left as it is", contents, withNextLine(contents, "contents.xhtml", pages))
        }
    }

}
