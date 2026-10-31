package com.app.newspaperss.ui.sources

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.PaidOnlyCount
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.app.newspaperss.ui.components.showWhileStarted
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.plural
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceKind
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.ui.components.BUILDING_NOTE
import com.app.newspaperss.ui.components.LocalClock
import com.app.newspaperss.ui.components.historyLine
import com.app.newspaperss.ui.components.isWebAddress
import com.app.newspaperss.ui.settings.SettingsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One source's health and its recent articles, so the reader can see what it has been sending. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * @param onGone leaves the screen once the source is removed, from here or elsewhere. It runs
 *   after the removal finishes, when the reader may already have left, so it mustn't just go up.
 */
fun SourceDetailScreen(viewModel: SourceDetailViewModel, onBack: () -> Unit, onGone: () -> Unit = onBack) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val source = detail?.source
    val locale = LocalConfiguration.current.locales[0]
    val clock = LocalClock.current
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    // Saved: an open confirmation survives a rotation.
    var removing by rememberSaveable { mutableStateOf(false) }
    var choosingMode by rememberSaveable { mutableStateOf(false) }
    var leavingOut by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val gone = detail != null && source == null
    LaunchedEffect(gone) { if (gone) onGone() }
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val undoOffer by viewModel.undoOffer.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val building by viewModel.building.collectAsStateWithLifecycle()
    LaunchedEffect(undoOffer) {
        val offer = undoOffer ?: return@LaunchedEffect
        // Ended however the snackbar goes, including the screen leaving or rotating, so a stale
        // Undo never comes back. Missed, starring still brings the article back.
        try {
            // Long: on e-ink the reader may not see it straight away.
            val result = snackbar.showWhileStarted(lifecycle, undoMessage(offer.change), actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) viewModel.undo(offer)
        } finally {
            viewModel.undoOfferEnded(offer)
        }
    }
    LaunchedEffect(notice) {
        val text = notice ?: return@LaunchedEffect
        try {
            snackbar.showWhileStarted(lifecycle, text, duration = SnackbarDuration.Long)
        } finally {
            viewModel.noticeShown()
        }
    }
    // Ids rather than articles, so a selected article that changes state underneath is counted
    // as it is now; saved so a rotation keeps the selection.
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(stateSaver = IdSetSaver) { mutableStateOf(emptySet<Long>()) }
    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }
    BackHandler(enabled = selecting) { stopSelecting() }
    LaunchedEffect(detail) {
        // Only once loaded: straight after a process restart the list is briefly empty.
        val ids = detail?.articles?.mapTo(HashSet()) { it.id } ?: return@LaunchedEffect
        if (ids.isEmpty()) stopSelecting() else if (!ids.containsAll(selected)) selected = selected intersect ids
    }
    val articles = detail?.articles.orEmpty()
    val publication = detail?.text
    val episodes = episodesLine(articles)
    val offered = offeredSettings(viewModel, source, publication, detail?.paidOnly, articles)
    val knownTitle = publication?.title ?: articles.firstNotNullOfOrNull { it.originTitle }
    val feedTitle = knownTitle ?: "A feed"
    val scope = rememberCoroutineScope()
    // One at a time: tapping again replaces the last, rather than queueing in front of an Undo.
    var copiedNote by remember { mutableStateOf<Job?>(null) }
    val onCopied: (String) -> Unit = { message ->
        copiedNote?.cancel()
        copiedNote = scope.launch { snackbar.showSnackbar(message, duration = SnackbarDuration.Long) }
    }
    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) },
                    navigationIcon = { IconButton(onClick = ::stopSelecting) { Icon(Icons.Default.Close, contentDescription = "Stop selecting") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.semantics { paneTitle = "Selecting. Tap articles to choose them." },
                )
            } else {
                TopAppBar(
                    title = { Text(if (viewModel.isFeed) feedTitle else source?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    // Removing is rare and destructive: in the menu, as on an edition's page, not
                    // beside Pause. A tt-rss account is left from Settings, as the server setup.
                    actions = {
                        if (source != null && !viewModel.isFeed && source.kind != SourceKind.TTRSS) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Remove source") }, onClick = { menu = false; removing = true })
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (source == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        // The selection bar lies over the list instead of being the Scaffold's bottom bar, and the
        // room reserved for it at the end of the list only ever grows: either change in the
        // list's bottom padding would scroll a list that's at its end, moving every row (a full
        // redraw on e-ink) on the way in or out of selection mode.
        val density = LocalDensity.current
        var barRoom by remember { mutableStateOf(0.dp) }
        var barHeight by remember { mutableStateOf(0.dp) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + barRoom)) {
                item {
                    if (viewModel.isFeed) {
                        FeedHeader(publication, episodes, onCopied)
                    } else {
                        Health(source, publication, articles.maxOfOrNull { it.discoveredAt }, episodes, locale, is24Hour, onCopied)
                    }
                    SettingsRule()
                    SettingsRow(settingsSummary(offered, source, publication), nounFor(source), onClick = { settingsOpen = true })
                    SettingsRule()
                    ArticlesHeading(articles, selecting, onSelect = { selecting = true })
                    if (building && articles.isNotEmpty()) {
                        Text(BUILDING_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                }
                items(articles, key = { it.id }) { article ->
                    RecentArticle(
                        article,
                        historyLine(
                            article,
                            detail?.history?.get(article.id),
                            // A curated list expires by count (its newest 12), so no day count there.
                            expires = source.kind == SourceKind.FEED || source.kind == SourceKind.TTRSS,
                            paused = source.paused,
                            now = clock.instant(),
                            zone = clock.zone,
                        ),
                        locale,
                        building,
                        selection = if (selecting) article.id in selected else null,
                        onStar = { viewModel.setStarred(article.id, it) },
                        onToggleRead = { viewModel.toggleRead(article.id) },
                        onSelect = { selected = if (article.id in selected) selected - article.id else selected + article.id },
                        onStartSelecting = {
                            selecting = true
                            selected = selected + article.id
                        },
                        pairs = detail?.episodePairs.orEmpty(),
                    )
                    // Inset: full-width rules chopped the list into boxes to track across.
                    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (selecting) {
                SelectionBar(
                    chosen = articles.filter { it.id in selected },
                    building = building,
                    onStar = { ids, starred -> viewModel.setStarred(ids, starred); stopSelecting() },
                    onMarkRead = { ids -> viewModel.markRead(ids); stopSelecting() },
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged {
                        val height = with(density) { it.height.toDp() }
                        barHeight = height
                        if (height > barRoom) barRoom = height
                    },
                )
            }
            // Here rather than in the Scaffold, so it sits above the bar instead of over its buttons.
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (selecting) barHeight else 0.dp))
        }
    }
    if (settingsOpen && source != null) {
        SettingsSheet(
            offered, source, publication, detail,
            viewModel = viewModel,
            onChooseMode = { choosingMode = true },
            onLeaveOut = { leavingOut = true },
            onBringBack = { viewModel.setInPaper(knownTitle, true) },
            onDismiss = { settingsOpen = false },
        )
    }
    if (choosingMode && source != null) {
        ContentModeDialog(
            detail?.text?.chosenMode ?: ContentMode.AUTO,
            onChoose = { choosingMode = false; viewModel.chooseContentMode(it) },
            onDismiss = { choosingMode = false },
            automatic = if (viewModel.isFeed) "Automatic: learned for this feed" else "Automatic",
        )
    }
    if (leavingOut) {
        LeaveOutDialog(feedTitle, onConfirm = { leavingOut = false; viewModel.setInPaper(knownTitle, false) }, onDismiss = { leavingOut = false })
    }
    if (removing && source != null) {
        RemoveSourceDialog(source, onConfirm = { removing = false; viewModel.remove() }, onDismiss = { removing = false })
    }
}

/**
 * A tt-rss feed's address, then whether it's left out, failing or waiting, then the details: its account
 * and category, and where its article text comes from.
 */
@Composable
private fun FeedHeader(publication: PublicationEntity?, episodes: String?, onCopied: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val details = MaterialTheme.typography.bodySmall
    Column(Modifier.padding(16.dp)) {
        publication?.feedUrl?.let { url ->
            AddressLink(url, SourceRepository.hostOf(url), MaterialTheme.typography.bodyMedium, onCopied, Modifier.padding(bottom = 12.dp))
        }
        // Not for a feed left out: it isn't fetched for the paper, so its fetching doesn't matter here.
        val serverError = publication?.serverError?.takeIf { !publication.leftOut }
        val leftOut = publication?.leftOut == true
        if (leftOut || serverError != null || publication?.awaitingFirstFetch == true) Column(Modifier.padding(bottom = 12.dp)) {
            when {
                leftOut ->
                    Text("Left out of the paper. It stays in your tt-rss and isn't fetched. Starred articles from it still go in.", style = MaterialTheme.typography.bodyLarge)
                serverError != null -> {
                    // tt-rss's own words, which can be technical ("HTTP Code: 404"): what to do comes first.
                    Text("$SERVER_CANT_FETCH. If this stays, nothing new comes from it: the feed may have moved or closed, so check its address in tt-rss.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Text("tt-rss says: $serverError", style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                else ->
                    Text("$WAITING_FOR_FIRST_FETCH. Its first articles come once tt-rss has fetched it, usually within the hour.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listOfNotNull("In your tt-rss", publication?.category?.let { "category $it" }).joinToString(" · "), style = details, color = muted)
            if (!leftOut) Text(textLine(publication), style = details, color = muted)
            episodes?.let { Text(it, style = details, color = muted) }
        }
    }
}

@Composable
private fun LeaveOutDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave $title out?") },
        text = {
            Text("It stays in your tt-rss, so you can bring it back. It isn't fetched for the paper, and its waiting articles go, except ones you starred.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Leave out") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A source's site, then how it's doing, then the details. A feed that's failing or has sent
 * nothing yet also shows its own address, since that's what to check, not the site's front page.
 */
@Composable
private fun Health(source: SourceEntity, learned: PublicationEntity?, lastNew: Instant?, episodes: String?, locale: Locale, is24Hour: Boolean, onCopied: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val details = MaterialTheme.typography.bodySmall
    val clock = LocalClock.current
    // The feed's own address when the site's isn't one to open, as a hostile feed's may not be.
    val site = listOfNotNull(source.siteUrl, source.url).firstOrNull(::isWebAddress) ?: source.siteUrl ?: source.url
    Column(Modifier.padding(16.dp)) {
        AddressLink(site, SourceRepository.hostOf(site), MaterialTheme.typography.bodyMedium, onCopied)
        Column(Modifier.padding(top = 12.dp)) {
            // Paused here, first on the page, as well as in the settings: it's the one that stops the site altogether.
            val status = if (source.paused) "Paused · nothing new comes in" else statusLine(source, lastNew, clock.instant(), clock.zone)
            Text(status, style = MaterialTheme.typography.bodyLarge, color = if (hasProblem(source)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (!source.paused) failingLine(source.failingSince, locale, clock.instant(), clock.zone)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lastCheckedLine(source.lastFetchedAt, locale, is24Hour, clock.instant(), clock.zone)?.let { Text(it, style = details, color = muted) }
            if (source.kind == SourceKind.FEED) {
                Text(textLine(learned), style = details, color = muted)
                if (hasProblem(source) || lastNew == null) AddressLink(source.url, shownAddress(source.url), details, onCopied, label = "Feed:")
            }
            episodes?.let { Text(it, style = details, color = muted) }
        }
    }
}

/**
 * Which of the site's settings its page offers. A tt-rss feed is left out or brought back
 * rather than paused (pausing is its account's), and while left out it offers nothing else.
 */
internal data class SiteSettings(
    val pause: Boolean = false,
    val leaveOut: Boolean = false,
    val mode: Boolean = false,
    val cap: Boolean = false,
    val paidPosts: Boolean = false,
    val recordings: Boolean = false,
)

private fun offeredSettings(
    viewModel: SourceDetailViewModel,
    source: SourceEntity?,
    publication: PublicationEntity?,
    paidOnly: PaidOnlyCount?,
    articles: List<ArticleEntity>,
): SiteSettings {
    source ?: return SiteSettings()
    val leftOut = viewModel.isFeed && publication?.leftOut == true
    // Each tt-rss feed has its own posts, on its page; the account's page has none.
    val ownsPosts = source.kind != SourceKind.READING_LIST && (source.kind != SourceKind.TTRSS || viewModel.isFeed) && !leftOut
    val skip = publication?.skipPaidPosts == true
    val off = publication?.recordingsOff == true
    return SiteSettings(
        pause = !viewModel.isFeed,
        leaveOut = viewModel.isFeed,
        mode = !leftOut && (viewModel.isFeed || source.kind == SourceKind.FEED),
        cap = !leftOut && (viewModel.isFeed || source.kind != SourceKind.TTRSS),
        // Only once the publication has had one: most never do. Kept while it's on, so it can be turned off.
        paidPosts = ownsPosts && paidOnly != null && (skip || paidOnly.found > 0),
        // Only on a source with a post that could play one in Listen (see Readings), as for paid posts.
        recordings = ownsPosts && viewModel.recordings && (off || articles.any(::mayPlayARecording)),
    )
}

/** A reading, its audio version, or a transcript with an episode: notes' episodes don't play in place. */
private fun mayPlayARecording(article: ArticleEntity) = when (article.episodeKind) {
    EpisodeKind.ARTICLE, EpisodeKind.AUDIO_VERSION -> article.audioUrl != null
    EpisodeKind.TRANSCRIPT -> article.audioUrl != null || article.episodePair != null
    else -> false
}

/**
 * What's set differently for this site from the defaults, in the sheet's order; "Default
 * settings" when nothing is. Most sites keep the defaults, so most rows stay quiet.
 */
internal fun settingsSummary(offered: SiteSettings, source: SourceEntity?, publication: PublicationEntity?): String =
    listOfNotNull(
        "Paused".takeIf { offered.pause && source?.paused == true },
        "Left out".takeIf { offered.leaveOut && publication?.leftOut == true },
        publication?.chosenMode?.takeIf { offered.mode && it != ContentMode.AUTO }?.let { modeName(publication) },
        publication?.maxArticles?.takeIf { offered.cap }?.let { "At most ${plural(it, "article")}" },
        "Skips posts with nothing free".takeIf { offered.paidPosts && publication?.skipPaidPosts == true },
        "Recordings off".takeIf { offered.recordings && publication?.recordingsOff == true },
    ).joinToString(" · ").ifEmpty { "Default settings" }

/** One line that says how the site is set and opens its settings; no heading, the icon says what it is. */
@Composable
private fun SettingsRow(summary: String, noun: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "${settingsTitle(noun)}: $summary"
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A pale rule around the settings and between them: lighter than the list's, so the settings stay quiet. */
@Composable
private fun SettingsRule() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest)

private fun settingsTitle(noun: String) = "This $noun's settings"

/** What the page's settings belong to: a curated list and the reading list aren't one site. */
private fun nounFor(source: SourceEntity?) = if (source?.kind == SourceKind.LIST || source?.kind == SourceKind.READING_LIST) "list" else "site"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    offered: SiteSettings,
    source: SourceEntity,
    publication: PublicationEntity?,
    detail: SourceDetail?,
    viewModel: SourceDetailViewModel,
    onChooseMode: () -> Unit,
    onLeaveOut: () -> Unit,
    onBringBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open at once: half open would hide the settings below the article limit.
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                settingsTitle(nounFor(source)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).semantics { heading() },
            )
            val rows = buildList<@Composable () -> Unit> {
                if (offered.pause) add {
                    SwitchRow("Pause this ${nounFor(source)}", "Nothing new comes from it until you start it again.", source.paused, onChange = { viewModel.togglePaused() })
                }
                if (offered.leaveOut) add {
                    if (publication?.leftOut == true) {
                        ChoiceRow("Bring back", "Fetch it for the paper again.", onClick = onBringBack, chevron = false)
                    } else {
                        ChoiceRow("Leave out of the paper", "It stays in your tt-rss and isn't fetched.", onClick = onLeaveOut)
                    }
                }
                if (offered.mode) add { ChoiceRow("Article text", modeName(publication), onClick = onChooseMode) }
                if (offered.cap) add {
                    ArticleCap(publication?.maxArticles, detail?.defaultMax ?: 1, viewModel::stepMaxArticles, viewModel::followEditionMax)
                }
                if (offered.paidPosts) add {
                    PaidPostsOption(publication?.skipPaidPosts == true, detail?.paidOnly?.skipped ?: 0, viewModel::setSkipPaidPosts)
                }
                if (offered.recordings) add {
                    SwitchRow(
                        "Its recordings in Listen",
                        "Readings and episodes play in place of the voice, from the next time you open an edition in Listen.",
                        publication?.recordingsOff != true,
                        onChange = viewModel::setRecordingsOn,
                    )
                }
            }
            rows.forEachIndexed { i, row ->
                if (i > 0) SettingsRule()
                row()
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String, onClick: () -> Unit, chevron: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PaidPostsOption(skip: Boolean, skipped: Int, onChange: (Boolean) -> Unit) {
    SwitchRow(
        "Skip posts with nothing free",
        when {
            skip && skipped > 0 -> "${plural(skipped, "post")} skipped so far: a title and a picture, nothing to read."
            skip -> "A post that's only a title and a picture won't take a place."
            else -> "Some of its posts are for subscribers, with only a title and a picture free."
        },
        skip,
        onChange,
    )
}

/** The font scale from which rows stack rather than squeeze. */
internal const val LARGE_TEXT = 1.3f

@Composable
private fun ArticleCap(own: Int?, default: Int, onStep: (Int) -> Unit, onFollowDefault: () -> Unit) {
    val max = own ?: default
    // The edition's number gives way when there's room; a site's own number is a hard limit.
    val words: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            Text(
                if (own == null) "${plural(default, "article")} from this site, then more if there's room"
                else "At most ${plural(own, "article")} from this site in each edition",
            )
            Text(
                if (own == null) "Your edition setting. Choose fewer or more to give this site a firm limit."
                else "Your edition setting: ${plural(default, "article")} from each source, then more if there's room",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val buttons: @Composable () -> Unit = {
        OutlinedButton(onClick = { onStep(-1) }, enabled = own == null || own > 1, modifier = Modifier.semantics { contentDescription = "Fewer articles from this site" }) {
            Text("−")
        }
        OutlinedButton(
            onClick = { onStep(1) },
            enabled = max < SettingsViewModel.MAX_PER_SOURCE,
            modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = "More articles from this site" },
        ) { Text("+") }
    }
    val padding = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (own == null) 12.dp else 0.dp)
    // At large text the buttons get their own line: beside the words they squeezed them to a few
    // words a line.
    if (LocalDensity.current.fontScale >= LARGE_TEXT) {
        Column(padding) {
            words(Modifier)
            Row(Modifier.padding(top = 8.dp)) { buttons() }
        }
    } else {
        Row(padding, verticalAlignment = Alignment.CenterVertically) {
            words(Modifier.weight(1f))
            buttons()
        }
    }
    if (own != null) {
        TextButton(onClick = onFollowDefault, modifier = Modifier.padding(horizontal = 4.dp)) { Text("Use your edition setting") }
    }
}

/** The article-text setting in a word or two, for its button. */
private fun modeName(publication: PublicationEntity?) = when (publication?.chosenMode) {
    ContentMode.FEED -> "Feed's text"
    ContentMode.PAGE -> "Full page"
    else -> "Automatic"
}

/** Where a publication's text comes from, including while the automatic check is still deciding. */
private fun textLine(publication: PublicationEntity?): String =
    fullTextLine(publication) ?: "Still working out whether this site sends full articles"

/** Null until a failure has lasted past the day it started: one bad sync isn't worth a second line. */
internal fun failingLine(since: Instant?, locale: Locale, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (since == null) return null
    val days = ChronoUnit.DAYS.between(since.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    return when {
        days <= 0 -> null
        days == 1L -> "Failing since yesterday"
        else -> "Failing for $days days, since ${shortDate(since, locale, now, zone)}"
    }
}

internal fun lastCheckedLine(at: Instant?, locale: Locale, is24Hour: Boolean, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
    if (at == null) return null
    val date = at.atZone(zone)
    return if (date.toLocalDate() == now.atZone(zone).toLocalDate()) {
        val pattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hma")
        "Last checked today at ${DateTimeFormatter.ofPattern(pattern, locale).format(date)}"
    } else {
        "Last checked ${shortDate(at, locale, now, zone)}"
    }
}

/** Month and day, and the year too when it isn't this one. */
internal fun shortDate(at: Instant, locale: Locale, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val date = at.atZone(zone)
    val skeleton = if (date.year == now.atZone(zone).year) "MMMd" else "yMMMd"
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
}
