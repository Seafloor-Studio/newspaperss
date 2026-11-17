package com.app.newspaperss.ui.edition

import android.content.Intent
import androidx.core.content.IntentCompat

/** A share of [quote], selected on the page, with [link]'s address under it so it says where it's from. */
internal fun quoteIntent(quote: String, link: ArticleLink?): Intent =
    withSource(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote), null), link)

/**
 * [intent] with [link]'s address on its own line under the text, if it shares text, directly or
 * through a chooser; anything else, such as a link opened in the browser, goes out unchanged.
 */
internal fun withSource(intent: Intent, link: ArticleLink?): Intent {
    if (link == null) return intent
    val chooser = intent.action == Intent.ACTION_CHOOSER
    val send = if (chooser) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) else intent
    if (send?.action != Intent.ACTION_SEND) return intent
    val text = send.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return intent
    if (link.url in text) return intent
    send.putExtra(Intent.EXTRA_TEXT, "${text.trimEnd()}\n\n${link.url}")
    // An email's subject, where the app shared to has one.
    if (link.title.isNotEmpty() && !send.hasExtra(Intent.EXTRA_SUBJECT)) send.putExtra(Intent.EXTRA_SUBJECT, link.title)
    if (chooser) intent.putExtra(Intent.EXTRA_INTENT, send)
    return intent
}
