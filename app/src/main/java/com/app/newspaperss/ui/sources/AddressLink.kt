package com.app.newspaperss.ui.sources

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.app.newspaperss.ui.components.isWebAddress
import com.app.newspaperss.ui.components.openInBrowser

/**
 * A web address on a source's page, underlined, with an "opens elsewhere" mark. A tap
 * opens it in the browser; with no browser to take it (some e-readers), or on a long press, it's
 * copied instead and [onCopied] gets the message to show. Anything but a web address is plain
 * muted text, so a hostile feed's `intent:` link is neither opened nor offered.
 *
 * @param words [shownAs] is the app's own words for the link, not an address, so it keeps its spaces.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddressLink(
    url: String,
    shownAs: String,
    textStyle: TextStyle,
    onCopied: (String) -> Unit,
    modifier: Modifier = Modifier,
    words: Boolean = false,
) {
    val shown = if (words) shownAs else readable(shownAs)
    val copiedAs = if (words) shownAddress(url) else shown
    // An address reads left to right even when it starts with a right-to-left letter.
    val style = textStyle.copy(textDirection = TextDirection.Ltr)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!isWebAddress(url)) {
        Text(shown, style = style, color = muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val context = LocalContext.current
    // Ink and an underline, not the accent: the brick-red accent next to a failing source's red
    // status reads as part of the error, and on e-ink a tint alone is too faint to see.
    val ink = MaterialTheme.colorScheme.onSurface
    val copy = { noBrowser: Boolean -> copyAddress(context, url, copiedAs, noBrowser, onCopied) }
    // No padding for a 48dp target: Compose already gives a smaller clickable a 48dp touch area,
    // one that loses to a direct hit, so the lines around it keep their own taps.
    Row(
        modifier.combinedClickable(
            role = Role.Button,
            onClickLabel = "open in browser",
            onLongClickLabel = "copy the address",
            onLongClick = { copy(false) },
        ) { if (!openInBrowser(context, url)) copy(true) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(shown) }
            },
            style = style,
            color = ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        // Sized to the text, so it grows with the reader's font size.
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.padding(start = 4.dp).size(with(LocalDensity.current) { style.fontSize.toDp() }),
        )
    }
}

/** Opens [url] in the browser; with none to take it, copies it instead and says so through [onCopied]. */
internal fun openAddress(context: Context, url: String, onCopied: (String) -> Unit) {
    if (!openInBrowser(context, url)) copyAddress(context, url, shownAddress(url), noBrowser = true, onCopied)
}

private fun copyAddress(context: Context, url: String, shownAs: String, noBrowser: Boolean, onCopied: (String) -> Unit) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Address", url.trim()))
    onCopied(if (noBrowser) "No browser here. Copied $shownAs" else "Copied $shownAs")
}

/**
 * The front page of [url]'s site ("https://example.com/" for "https://example.com/feed.xml"), or
 * null if it isn't a web address: a feed's site until its articles have named one. Its host only,
 * never a user name before an "@", and an international name in its ASCII form, so what's shown
 * is what opens.
 */
internal fun frontPage(url: String): String? {
    if (!isWebAddress(url)) return null
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
    // java.net.URI has no host for a name it can't parse ("bücher.example", "my_blog.example"):
    // the authority then, after any user name.
    val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
    val (host, port) = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]" to authority.substringAfter(']').removePrefix(":")
    } else {
        authority.substringBeforeLast(':', authority) to authority.substringAfterLast(':', "")
    }
    val ascii = runCatching { if (host.startsWith("[")) host else java.net.IDN.toASCII(java.net.URLDecoder.decode(host, "UTF-8")) }.getOrNull()
    if (ascii.isNullOrEmpty() || ascii.any { it.isWhitespace() || it == '/' || it == '\\' }) return null
    return "${uri.scheme.lowercase()}://$ascii" + (port.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { ":$it" } ?: "") + "/"
}

/** [url] for reading: [readable], without the scheme or a lone trailing slash. */
internal fun shownAddress(url: String): String =
    readable(url).replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        .let { if (it.indexOf('/') == it.length - 1) it.dropLast(1) else it }

/**
 * [text] without whitespace, control characters or the invisible characters that reorder text.
 * A hostile feed could otherwise show one address and open another: browsers drop the newlines
 * in "https://good.example\n\n.evil.example", which on screen reads as just the first line.
 */
internal fun readable(text: String): String = text.filterNot { it.isWhitespace() || it.isISOControl() || it in BIDI_CONTROLS }

private const val BIDI_CONTROLS = "\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"
