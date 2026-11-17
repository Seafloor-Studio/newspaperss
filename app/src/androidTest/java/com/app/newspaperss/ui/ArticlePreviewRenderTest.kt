package com.app.newspaperss.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.testutil.writeEpub
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The preview draws the edition's own EPUB, read back out of the file the app wrote. Robolectric
 * lays out text with no real fonts, so whether a real book draws on a real device's screen is only
 * answered here: the article's words are on screen, and the screen draws more than its background.
 */
@RunWith(AndroidJUnit4::class)
class ArticlePreviewRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val articles = listOf(
        EditionArticle(
            title = "A heron on the roof",
            sourceTitle = "The Example",
            url = "https://a.example/heron",
            bodyHtml = "<p>It stood there for an hour, unbothered.</p>".repeat(12),
            minutes = 2.0,
        ),
    )

    /** How many distinct colours the view draws: one means a blank page. */
    private fun View.coloursDrawn(): Int {
        if (width == 0 || height == 0) return 0
        var colours = emptySet<Int>()
        val done = CountDownLatch(1)
        compose.activity.runOnUiThread {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            colours = pixels.toSet()
            bitmap.recycle()
            done.countDown()
        }
        done.await(10, TimeUnit.SECONDS)
        return colours.size
    }

    @Test fun theBookTheAppWritesDrawsOnARealScreen() {
        val epub = File(compose.activity.cacheDir, "render-test.epub").apply { writeEpub(articles) }
        try {
            compose.setContent {
                ArticlePreviewScreen(loadFile = { epub }, position = 0, title = "A heron on the roof", onBack = {})
            }
            compose.waitUntil(timeoutMillis = 60_000) {
                compose.onAllNodesWithText("It stood there", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText("It stood there", substring = true).onFirst().assertIsDisplayed()
            val colours = compose.activity.window.decorView.coloursDrawn()
            assertTrue("the screen drew $colours colours: nothing was laid out", colours > 2)
        } finally {
            epub.delete()
        }
    }
}
