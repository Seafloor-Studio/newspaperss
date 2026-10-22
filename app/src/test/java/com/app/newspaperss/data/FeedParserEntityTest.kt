package com.app.newspaperss.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedParser
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Android's own XML parser, as FeedParser sets it up, doesn't expand an entity a feed declares:
 * what makes a nested one ("billion laughs") harmless even past FeedParser's check. With
 * FEATURE_PROCESS_DOCDECL on, it reads all 100,000 characters. Under Robolectric, as that parser
 * is Android's, not the JVM's kxml2 that :core's tests get.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class FeedParserEntityTest {
    private fun textOf(xml: String): String {
        val parser = FeedParser.newParser()
        parser.setInput(StringReader(xml))
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.TEXT || parser.eventType == XmlPullParser.ENTITY_REF) text.append(parser.text.orEmpty())
        }
        return text.toString()
    }

    // Five levels of ten: 100,000 characters if expanded.
    private val nested = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY a \"aaaaaaaaaa\">" +
        "<!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;\"><!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;\">" +
        "<!ENTITY d \"&c;&c;&c;&c;&c;&c;&c;&c;&c;&c;\"><!ENTITY e \"&d;&d;&d;&d;&d;&d;&d;&d;&d;&d;\">]>" +
        "<rss><channel><title>&e;</title></channel></rss>"

    @Test
    fun aDeclaredEntityIsNotExpanded() {
        val text = textOf(nested)
        assertTrue("${text.length} characters: ${text.take(40)}", text.length < 100)
    }
}
