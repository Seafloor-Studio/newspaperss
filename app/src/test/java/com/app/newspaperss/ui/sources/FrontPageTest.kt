package com.app.newspaperss.ui.sources

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A feed's site until its articles name one: its host's front page, and only its host. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FrontPageTest {
    @Test
    fun theFeedsHostAndPortNothingElse() {
        assertEquals("https://example.com/", frontPage("https://example.com/weekly/rss.xml?x=1#y"))
        assertEquals("https://example.com:8443/", frontPage("HTTPS://example.com:8443/feed"))
        assertEquals("http://[::1]:8080/", frontPage("http://[::1]:8080/feed"))
        assertEquals("https://example.com/", frontPage("  https://example.com/feed  "))
    }

    /** What's shown is what opens: never a user name before an "@". */
    @Test
    fun noUserName() {
        assertEquals("https://good.example/", frontPage("https://evil@good.example/feed"))
        assertEquals("https://evil.example/", frontPage("https://good.example@evil.example/feed"))
    }

    /** A host java.net.URI can't name still has a front page, an international one in its ASCII form. */
    @Test
    fun hostsTheParserDoesntName() {
        assertEquals("https://xn--bcher-kva.example/", frontPage("https://bücher.example/feed"))
        assertEquals("https://my_blog.example.com/", frontPage("https://my_blog.example.com/feed"))
    }

    @Test
    fun notAWebAddressHasNone() {
        assertNull(frontPage("feed://example.com/rss"))
        assertNull(frontPage("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(frontPage("https:///feed"))
    }
}
