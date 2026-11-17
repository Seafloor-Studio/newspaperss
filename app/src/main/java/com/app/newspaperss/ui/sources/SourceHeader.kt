package com.app.newspaperss.ui.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.PaperRecord
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.ui.components.dayOf
import com.app.newspaperss.ui.components.editionDay
import com.app.newspaperss.ui.components.isWebAddress
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A small line under the paper line. [tone] INK is an answer rather than a detail; ERROR is part of a problem. */
internal data class HeaderNote(val text: String, val tone: Tone = Tone.MUTED) {
    enum class Tone { MUTED, INK, ERROR }
}

/** The one thing the reader can do from the page to undo a stopped or failing state. */
internal sealed interface HeaderAction {
    data object StartAgain : HeaderAction
    data object BringBack : HeaderAction
    data class TtrssSettings(val url: String) : HeaderAction
}

/**
 * What a source's page says about it between its link and its settings: the [paper] line (or the
 * problem, when [problem]), small notes, and at most one action. [saysNoArticles]: the paper line
 * already tells the reader there's nothing to list, so the list adds nothing.
 */
internal data class HeaderStatus(
    val paper: String,
    val problem: Boolean = false,
    val notes: List<HeaderNote> = emptyList(),
    val action: HeaderAction? = null,
    val saysNoArticles: Boolean = false,
)

internal const val PAUSED_LINE = "Paused · nothing new comes in"
internal const val ACCOUNT_PAUSED_LINE = "Your tt-rss is paused · nothing new comes in"
private const val UNTIL_CLEARED = "Nothing new comes from it until this clears."
internal const val NO_ARTICLES_YET = "No articles yet"

/** A phone source's page: a feed, a curated list, or a tt-rss account's own. */
internal fun phoneStatus(
    source: SourceEntity,
    learned: PublicationEntity?,
    lastNew: Instant?,
    record: PaperRecord,
    hasArticles: Boolean,
    episodes: String?,
    locale: Locale,
    is24Hour: Boolean,
    now: Instant,
    zone: ZoneId,
): HeaderStatus {
    val fact = paperFact(record, now, zone, locale)
    val checked = lastCheckedLine(source.lastFetchedAt, locale, is24Hour, now, zone)
    val summaries = summariesNote(learned).takeIf { source.kind == SourceKind.FEED }
    if (source.paused) return HeaderStatus(PAUSED_LINE, notes = listOfNotNull(fact?.let(::HeaderNote)), action = HeaderAction.StartAgain)
    if (hasProblem(source)) {
        return failing(source, fact, checked, listOfNotNull(summaries, episodes?.let(::HeaderNote)), locale, now, zone)
    }
    if (source.lastFetchedAt == null) return HeaderStatus("Checking…", saysNoArticles = true)
    // "Last checked today" is the app saying it's working: only worth a line when the reader may doubt it.
    val checkedToday = source.lastFetchedAt.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    val doubt = checked.takeIf { !checkedToday || !hasArticles }
    return healthy(record, hasArticles, freshnessNote(lastNew, lastNew, now, zone), doubt, summaries, episodes, now, zone, locale)
}

/**
 * A source failing, or with a note from tt-rss: the problem, its last delivery, how long, and what
 * it means for the paper. A note about marking read stops nothing, so it has no meaning line.
 */
private fun failing(source: SourceEntity, fact: String?, checked: String?, more: List<HeaderNote>, locale: Locale, now: Instant, zone: ZoneId) = HeaderStatus(
    (source.lastError ?: source.serverNote)!!,
    problem = true,
    notes = listOfNotNull(
        fact?.let(::HeaderNote),
        failingLine(source.failingSince, locale, now, zone)?.let { HeaderNote(it, HeaderNote.Tone.ERROR) },
        listOfNotNull(UNTIL_CLEARED.takeIf { source.lastError != null }, checked).joinToString(" ").ifEmpty { null }?.let(::HeaderNote),
    ) + more,
)

/**
 * One of a tt-rss account's feeds, on its own page. Left out comes first, being the feed's own
 * choice and meaning it isn't fetched whatever the account does; then the account's own state,
 * paused or failing, which stops every feed in it.
 */
internal fun feedStatus(
    publication: PublicationEntity?,
    account: SourceEntity?,
    lastNew: Instant?,
    record: PaperRecord,
    hasArticles: Boolean,
    episodes: String?,
    locale: Locale,
    is24Hour: Boolean,
    now: Instant,
    zone: ZoneId,
): HeaderStatus {
    val settings = account?.let { a -> publication?.let { ttrssFeedSettings(a.url, it.key) } }?.let(HeaderAction::TtrssSettings)
    val summaries = summariesNote(publication)
    // Not a fetch error for a feed left out: it isn't fetched for the paper, so its fetching doesn't matter here.
    return when {
        publication?.leftOut == true -> HeaderStatus(
            "Left out of your paper",
            notes = listOf(HeaderNote("It stays in your tt-rss and isn't fetched. Starred articles from it still go in.")),
            action = HeaderAction.BringBack,
        )
        account?.paused == true -> HeaderStatus(
            ACCOUNT_PAUSED_LINE,
            notes = listOfNotNull(paperFact(record, now, zone, locale)?.let(::HeaderNote)),
            action = HeaderAction.StartAgain,
        )
        account?.lastError != null ->
            failing(account, paperFact(record, now, zone, locale), lastCheckedLine(account.lastFetchedAt, locale, is24Hour, now, zone), emptyList(), locale, now, zone)
        // tt-rss's own words, which can be technical ("HTTP Code: 404"), come after what they mean.
        publication?.serverError != null -> HeaderStatus(
            SERVER_CANT_FETCH,
            problem = true,
            notes = listOfNotNull(
                paperFact(record, now, zone, locale)?.let(::HeaderNote),
                HeaderNote("If this stays, nothing new comes from it: the feed may have moved or closed."),
                HeaderNote("tt-rss says: ${publication.serverError}"),
                summaries,
                episodes?.let(::HeaderNote),
            ),
            action = settings,
        )
        neverFetched(publication, account?.feedsListedAt) -> HeaderStatus(
            NEVER_FETCHED,
            problem = true,
            notes = listOf(HeaderNote("Nothing comes from it. Its updates are probably turned off there: set its update interval to Default.")),
            action = settings,
            saysNoArticles = true,
        )
        publication?.awaitingFirstFetch == true -> HeaderStatus(
            WAITING_FOR_FIRST_FETCH,
            notes = listOf(HeaderNote("Its first articles come once tt-rss has fetched it, usually within the hour.")),
            saysNoArticles = true,
        )
        else -> {
            // tt-rss deletes old articles, so a feed with no post seen goes quiet from when the app started watching.
            val quietSince = listOfNotNull(publication?.newestPostAt ?: publication?.watchedSince, lastNew).maxOrNull()
            val freshness = freshnessNote(publication?.newestPostAt ?: lastNew, quietSince, now, zone)
            healthy(record, hasArticles, freshness, null, summaries, episodes, now, zone, locale)
        }
    }
}

private fun healthy(
    record: PaperRecord,
    hasArticles: Boolean,
    freshness: String?,
    checked: String?,
    summaries: HeaderNote?,
    episodes: String?,
    now: Instant,
    zone: ZoneId,
    locale: Locale,
): HeaderStatus {
    val paper = paperLine(record, hasArticles, now, zone, locale)
    return HeaderStatus(
        paper,
        notes = listOfNotNull(
            listOfNotNull(freshness, checked).joinToString(" · ").ifEmpty { null }?.let(::HeaderNote),
            summaries,
            episodes?.let(::HeaderNote),
        ),
        saysNoArticles = paper == NO_ARTICLES_YET,
    )
}

/**
 * What the source has done for the paper, in the rows' own words: the first that applies of an
 * unsent edition holding some of it, its last delivery, something waiting, and nothing yet. Never
 * how many wait: that would be an unread count.
 */
internal fun paperLine(record: PaperRecord, hasArticles: Boolean, now: Instant, zone: ZoneId, locale: Locale): String =
    paperFact(record, now, zone, locale) ?: when {
        record.waiting -> "Waiting for your next edition"
        hasArticles -> "Nothing sent from here yet"
        else -> NO_ARTICLES_YET
    }

/** An unsent edition holding some of it, or else its last delivery: null if neither. */
private fun paperFact(record: PaperRecord, now: Instant, zone: ZoneId, locale: Locale): String? {
    record.unsent?.let { unsent ->
        val edition = editionDay(unsent.title)?.let { "$it's edition" } ?: "an edition"
        return "${plural(unsent.count, "article")} in $edition, not sent yet"
    }
    val last = record.sent.firstOrNull() ?: return null
    // Every delivery that day counts: a morning and an evening edition are one day's paper.
    val day = last.sentAt.atZone(zone).toLocalDate()
    val count = record.sent.filter { it.sentAt.atZone(zone).toLocalDate() == day }.sumOf { it.count }
    return "Last sent ${dayOf(last.sentAt, now, zone, locale)} · ${plural(count, "article")}"
}

/** How recently it posted: "New articles today", or past three weeks of nothing, how long it's been quiet. */
private fun freshnessNote(lastNew: Instant?, quietSince: Instant?, now: Instant, zone: ZoneId): String? =
    quietSince?.let { quietLine(it, now) } ?: lastNew?.let { freshness(it, now, zone) }

/**
 * Only summaries reach the paper, when the automatic check found so; the reader's own choice of
 * text isn't news to them.
 */
private fun summariesNote(publication: PublicationEntity?): HeaderNote? {
    if (publication?.chosenMode.let { it != null && it != ContentMode.AUTO }) return null
    return when (textFound(publication)) {
        TextFound.SUMMARIES -> HeaderNote("Only summaries: the site doesn't send full articles.", HeaderNote.Tone.INK)
        TextFound.BLOCKED -> HeaderNote("Only summaries: the site blocks fetching the full page.", HeaderNote.Tone.INK)
        else -> null
    }
}

/**
 * The address under the title, as the source's byline: a feed's site, or its front page until its
 * articles name one. Never one that isn't a web address where a web one is known, as a hostile
 * feed's site may not be.
 */
internal fun siteLink(source: SourceEntity, publication: PublicationEntity?, isFeed: Boolean): String? {
    if (isFeed) {
        val feed = publication?.feedUrl
        return publication?.siteUrl?.takeIf { isWebAddress(it) && it != feed } ?: feed?.let(::frontPage)
    }
    return if (source.kind == SourceKind.FEED) {
        source.siteUrl?.takeIf { isWebAddress(it) && it != source.url } ?: frontPage(source.url)
    } else {
        listOfNotNull(source.siteUrl, source.url).firstOrNull(::isWebAddress) ?: source.siteUrl ?: source.url
    }
}

/** The feed's own address, for ⋮ Open feed: a web address the site link doesn't already open. */
internal fun feedLink(source: SourceEntity, publication: PublicationEntity?, isFeed: Boolean): String? {
    val feed = if (isFeed) publication?.feedUrl else source.url.takeIf { source.kind == SourceKind.FEED }
    return feed?.takeIf { isWebAddress(it) && it.trim() != siteLink(source, publication, isFeed)?.trim() }
}

/**
 * The source's link, then [status] in one TalkBack node, then its action. The same for a phone
 * source and a tt-rss feed, so the two can't drift apart.
 */
@Composable
internal fun SourceHeader(site: String?, status: HeaderStatus, onCopied: (String) -> Unit, onAction: (HeaderAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        site?.let { AddressLink(it, SourceRepository.hostOf(it), MaterialTheme.typography.bodyMedium, onCopied) }
        // One node, so one swipe reads the whole answer. Each line says itself with commas for " · ",
        // which TalkBack reads badly.
        Column(Modifier.padding(top = if (site != null) 8.dp else 0.dp).semantics(mergeDescendants = true) {}) {
            val paperStyle = MaterialTheme.typography.bodyLarge
            Row(verticalAlignment = Alignment.Top) {
                // Three cues, not colour alone, so a problem still reads on e-ink: the mark, the weight and the words.
                if (status.problem) {
                    val size = with(LocalDensity.current) { paperStyle.fontSize.toDp() * 1.2f }
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = "Problem", tint = colors.error, modifier = Modifier.padding(end = 6.dp).size(size))
                }
                Text(
                    status.paper,
                    style = paperStyle,
                    color = if (status.problem) colors.error else colors.onSurface,
                    fontWeight = if (status.problem) FontWeight.Medium else null,
                    modifier = Modifier.spoken(status.paper),
                )
            }
            status.notes.forEach { note ->
                Text(
                    note.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (note.tone) {
                        HeaderNote.Tone.MUTED -> colors.onSurfaceVariant
                        HeaderNote.Tone.INK -> colors.onSurface
                        HeaderNote.Tone.ERROR -> colors.error
                    },
                    modifier = Modifier.padding(top = 2.dp).spoken(note.text),
                )
            }
        }
        when (val action = status.action) {
            null -> {}
            HeaderAction.StartAgain -> OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.padding(top = 8.dp)) { Text("Start again") }
            HeaderAction.BringBack -> OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.padding(top = 8.dp)) { Text("Bring back") }
            is HeaderAction.TtrssSettings ->
                AddressLink(action.url, "Its settings in tt-rss", MaterialTheme.typography.bodyMedium, onCopied, Modifier.padding(top = 8.dp), words = true)
        }
    }
}

private fun Modifier.spoken(text: String) = semantics { contentDescription = text.replace(" · ", ", ") }
