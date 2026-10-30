package com.app.newspaperss

import androidx.navigation.NavBackStackEntry
import com.app.newspaperss.data.ArticleSource
import com.app.newspaperss.data.EditionContent
import com.app.newspaperss.data.FeedMoves
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceKind
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri

import com.app.newspaperss.settings.offersOpen
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import android.content.Intent
import com.app.newspaperss.listen.KokoroSupport
import com.app.newspaperss.listen.ListenPosition
import com.app.newspaperss.ui.components.episodeJudgement
import com.app.newspaperss.ui.listen.ListenScreen
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.app.newspaperss.ui.sources.LeftOutScreen
import com.app.newspaperss.ui.sources.NotInPaperScreen
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.theme.NewspaperssTheme
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.work.Connectivity
import com.app.newspaperss.work.EditionWorker
import com.app.newspaperss.work.EditionScheduler
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.readinglist.ReadingListScreen
import com.app.newspaperss.ui.readinglist.ReadingListViewModel
import com.app.newspaperss.ui.settings.FeedsFromViewModel
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.NaturalVoiceScreen
import com.app.newspaperss.ui.listen.ListenBar
import com.app.newspaperss.ui.listen.showsListenBar
import com.app.newspaperss.ui.settings.SettingsScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.work.SyncWorker
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TODAY("today", "Today", Icons.Default.Newspaper),
    SOURCES("sources", "Sources", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

private const val READING_LIST = "reading-list"
private const val EDITION = "edition/{id}"
private const val ARTICLE = "edition/{id}/article/{position}"
private const val SOURCE = "source/{id}"
/** One of a tt-rss account's feeds: the account's source id and the feed's id there. */
private const val FEED = "source/{id}/feed/{key}"
private const val LEFT_OUT = "source/{id}/left-out"
private const val NOT_IN_PAPER = "source/{id}/not-in-paper"
private const val SETTINGS_PAGE = "settings/{page}"
private const val NATURAL_VOICE = "settings/listening/natural-voice"
private const val LISTEN = "listen"
private val FEEDS_FROM = "settings/${SettingsPage.FEEDS.slug}"

class MainActivity : ComponentActivity() {
    @Volatile private var settingsLoaded = false
    /** What the intent that opened (or reopened) the app asks to show, until it's shown. */
    private val opening = mutableStateOf<String?>(null)

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        opening.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !settingsLoaded }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = application.container
        // Only a fresh start at the root of its task has no picker below it left to answer. A start
        // on top of an existing task, as a launcher tap gets after a notification opened the app,
        // may have one.
        if (savedInstanceState == null && isTaskRoot) {
            AwaitedAnswers(this).forget()
        }
        // Not when reopened from recents, which hands back the intent that first opened it.
        if (savedInstanceState == null && intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            opening.value = intent?.getStringExtra(EXTRA_OPEN)
        }
        setContent {
            NewspaperssTheme {
                val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
                if (settings != null) settingsLoaded = true
                when (settings?.onboarded) {
                    null -> {}
                    false -> {
                        val context = LocalContext.current.applicationContext
                        val vm = viewModel {
                            OnboardingViewModel(
                                container.settings, container.sources, container.feedFinder, container.ttrss, createSavedStateHandle(),
                                savedLinks = container.readingList.observeWaiting(),
                            ) { saved ->
                                container.appScope.launch { EditionScheduler.reschedule(context, saved) }
                                EditionWorker.buildNow(context)
                            }
                        }
                        val sources = viewModel { SourcesViewModel(container.sources, container.feedFinder) { SyncWorker.syncNow(context) } }
                        val readingList = viewModel { ReadingListViewModel(container.readingList) }
                        OnboardingScreen(vm, sources, readingList)
                    }
                    true -> App(
                        container,
                        preferOpen = settings?.device == com.app.newspaperss.settings.Device.BOOX,
                        offerOpen = settings?.device.offersOpen,
                        kindleReader = settings?.device == com.app.newspaperss.settings.Device.KINDLE,
                        kindleEmail = settings?.kindleEmailTarget,
                        textSize = settings?.previewTextSize ?: com.app.newspaperss.settings.PreviewTextSize.DEFAULT,
                        opening = opening.value,
                        onOpened = { opening.value = null },
                    )
                }
            }
        }
    }

    // The result contracts launch through these, so they see every picker (permission requests
    // go another way, and a lost permission answer is only asked again). A plain startActivity
    // comes through here too, with -1, and nothing answers it: not held.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (requestCode < 0) return super.startActivityForResult(intent, requestCode, options)
        val awaited = AwaitedAnswers(this)
        awaited.sent(requestCode)
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (e: RuntimeException) {
            awaited.answered(requestCode)
            throw e
        }
    }

    // Before the callback, which may open another picker that must hold in turn.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        AwaitedAnswers(this).answered(requestCode)
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onStart() {
        super.onStart()
        // Each time the app comes to the front, not just on a cold start: it can sit in recents for days.
        val updates = application.container.debugUpdates
        application.container.appScope.launch { updates.checkIfDue() }
        application.container.selfUpdates.refresh()
    }

    companion object {
        /** An intent extra: a screen to open, such as [OPEN_LISTENING] from the player's notification. */
        const val EXTRA_OPEN = "open"
        const val OPEN_LISTENING = "listening"
    }
}

@Composable
private fun App(
    container: AppContainer,
    preferOpen: Boolean,
    offerOpen: Boolean,
    kindleReader: Boolean,
    kindleEmail: com.app.newspaperss.settings.KindleEmail?,
    /** Settings › Article text size, known here before any screen draws. */
    textSize: com.app.newspaperss.settings.PreviewTextSize,
    opening: String? = null,
    onOpened: () -> Unit = {},
) {
    val nav = rememberNavController()
    LaunchedEffect(opening) {
        if (opening == MainActivity.OPEN_LISTENING) nav.navigate(LISTEN) { launchSingleTop = true }
        if (opening != null) onOpened()
    }
    val current by nav.currentBackStackEntryAsState()
    val listenNow by container.listenNow.collectAsStateWithLifecycle()
    Scaffold(
        bottomBar = {
            Column {
                // Not over the playing screen, which is the same and more, nor an article of the edition
                // it's playing, which has the player under the page.
                val onItsArticle = current?.destination?.route == ARTICLE && current?.arguments?.getLong("id") == listenNow?.editionId
                listenNow?.takeIf { showsListenBar(it) && current?.destination?.route != LISTEN && !onItsArticle }?.let { state ->
                    ListenBar(state, onOpen = { nav.navigate(LISTEN) { launchSingleTop = true } }, onToggle = container.listening::toggle, onClose = container.listen::stop)
                }
                // The tab whose stack the screen is on: switching tabs pops back to Today, the start,
                // and pushes the other tab's top, so at most one other tab's root is on the stack. Not
                // by the screen's route: the player, say, opens from the bar on any tab.
                val tabNow = current?.let { Tab.entries.lastOrNull { tab -> runCatching { nav.getBackStackEntry(tab.route) }.isSuccess } } ?: Tab.TODAY
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val inTab = tab == tabNow
                        NavigationBarItem(
                            selected = inTab,
                            onClick = {
                                // Tapping the tab you're in goes back to its top screen; restoring
                                // saved state would otherwise reopen the detail screen you're on.
                                // The player isn't kept in the tab left: coming back, the tab shows itself.
                                if (!inTab && current?.destination?.route == LISTEN) nav.popBackStack()
                                if (inTab) nav.popBackStack(tab.route, inclusive = false) else nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // No transitions: animations smear on e-ink readers, and a calm app doesn't need them.
        NavHost(
            nav,
            startDestination = Tab.TODAY.route,
            // Consumed as well as applied: otherwise each screen's own Scaffold and top bar pad
            // for the status and navigation bars a second time.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(Tab.TODAY.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel { TodayViewModel(container.editions, EditionWorker.observe(context), container.settings.settings, online = Connectivity.online(context), lastDue = { EditionScheduler.lastDue(context) }, lastStart = { EditionScheduler.lastStart(context) }, sentToKindle = container.kindleSends.recent) { EditionWorker.buildNow(context) } }
                val newerBuild by container.debugUpdates.newer.collectAsStateWithLifecycle()
                val selfUpdate by container.selfUpdates.state.collectAsStateWithLifecycle(SelfUpdate.None)
                TodayScreen(
                    vm, newerBuild = newerBuild, selfUpdate = selfUpdate, onInstall = container.selfUpdates::install,
                    onOpenEdition = { nav.navigate("edition/$it") { launchSingleTop = true } },
                )
            }
            composable(EDITION, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.idArg
                val vm = viewModel { EditionDetailViewModel(container.editions, id, container.kindleSends.recent, container.notifier::dismissFor) }
                EditionDetailScreen(
                    vm,
                    preferOpen = preferOpen,
                    offerOpen = offerOpen,
                    kindleReader = kindleReader,
                    kindleEmail = kindleEmail,
                    listening = container.listening,
                    onOpenPlayer = { nav.navigate(LISTEN) { launchSingleTop = true } },
                    onBack = { nav.navigateUp() },
                    onReadArticle = { position -> nav.navigate("edition/$id/article/$position") { launchSingleTop = true } },
                )
            }
            composable(
                ARTICLE,
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("position") { type = NavType.IntType }),
            ) { entry ->
                val id = entry.idArg
                val position = entry.arguments?.getInt("position") ?: 0
                val contents by remember(id) { container.editions.observeContents(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
                // A phone feed moved to tt-rss is kept, paused, while editions still take from it,
                // but Sources hides it: its page would offer settings nothing reads any more, and a
                // Resume that starts it fetching beside the tt-rss feed it became.
                val moving by remember { container.feedMoves.state }.collectAsStateWithLifecycle(initialValue = FeedMoves.State())
                val editionTitle by produceState<String?>(null, id) { value = container.editions.byId(id)?.title }
                val textSize by remember { container.settings.settings.map { it.previewTextSize } }.collectAsStateWithLifecycle(initialValue = null)
                val recordings by remember { container.settings.settings.map { it.recordings } }.collectAsStateWithLifecycle(initialValue = com.app.newspaperss.settings.Recordings())
                ArticlePreviewScreen(
                    loadFile = { container.editions.byId(id)?.let(container.editions::fileOf) },
                    position = position,
                    title = contents.getOrNull(position)?.entry?.title ?: editionTitle.orEmpty(),
                    onBack = { nav.navigateUp() },
                    textSize = textSize,
                    onTextSize = { size -> container.appScope.launch { container.settings.update { it.copy(previewTextSize = size) } } },
                    // The player comes up under the page: reading and listening are one view.
                    onListen = { article -> container.listening.start(id, ListenPosition(article, 0)) },
                    sourceAt = { position -> goTo(contents, moving, position)?.title },
                    onOpenSource = { position ->
                        goTo(contents, moving, position)?.let { nav.navigate(sourceRoute(it)) { launchSingleTop = true } }
                    },
                    episodeFor = { article -> contents.getOrNull(article)?.let { episodeJudgement(it, recordings) } },
                    player = container.listen,
                    editionId = id,
                    podcastVoice = container.podcastPlaying,
                    instead = container.podcastInstead,
                    reading = container.readingNow,
                    onVoiceInstead = container::useVoiceInstead,
                )
            }
            composable(LISTEN) {
                ListenScreen(
                    container.listen, onBack = { nav.navigateUp() }, podcastVoice = container.podcastPlaying, instead = container.podcastInstead,
                    reading = container.readingNow, onVoiceInstead = container::useVoiceInstead,
                    textSize = textSize,
                )
            }
            composable(Tab.SOURCES.route) {
                val context = LocalContext.current.applicationContext
                val vm = viewModel {
                    SourcesViewModel(
                        container.sources, container.feedFinder, container.ttrss, saveToReadingList = { container.readingList.save(it) },
                        settings = container.settings, subscriptions = container.ttrssSubscriptions, moves = container.feedMoves,
                        saved = createSavedStateHandle(),
                    ) {
                        SyncWorker.syncNow(context)
                    }
                }
                SourcesScreen(
                    vm,
                    onOpenReadingList = { nav.navigate(READING_LIST) },
                    onOpenSource = { nav.navigate("source/$it") { launchSingleTop = true } },
                    onOpenFeed = { id, key -> nav.navigate(feedRoute(id, key)) { launchSingleTop = true } },
                    onOpenLeftOut = { nav.navigate("source/$it/left-out") { launchSingleTop = true } },
                    onOpenNotInPaper = { nav.navigate("source/$it/not-in-paper") { launchSingleTop = true } },
                    onOpenAccount = { nav.navigate(FEEDS_FROM) { launchSingleTop = true } },
                )
            }
            composable(FEED, arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("key") { type = NavType.StringType })) { entry ->
                val id = entry.idArg
                val key = entry.arguments?.getString("key").orEmpty()
                val context = LocalContext.current.applicationContext
                val vm = viewModel {
                    sourceDetail(container, id, key) { SyncWorker.syncNow(context) }
                }
                SourceDetailScreen(vm, onBack = { nav.navigateUp() }, onGone = { nav.popBackStack(FEED, inclusive = true) })
            }
            composable(LEFT_OUT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.idArg
                val vm = viewModel { sourceDetail(container, id) }
                LeftOutScreen(vm, onBack = { nav.navigateUp() }, onOpenFeed = { nav.navigate(feedRoute(id, it)) { launchSingleTop = true } })
            }
            composable(NOT_IN_PAPER, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.idArg
                val vm = viewModel { sourceDetail(container, id) }
                NotInPaperScreen(vm, onBack = { nav.navigateUp() }, onOpenAccount = { nav.navigate(FEEDS_FROM) { launchSingleTop = true } })
            }
            composable(SOURCE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val id = entry.idArg
                val context = LocalContext.current.applicationContext
                val vm = viewModel { sourceDetail(container, id) { SyncWorker.syncNow(context) } }
                SourceDetailScreen(vm, onBack = { nav.navigateUp() }, onGone = { nav.popBackStack(SOURCE, inclusive = true) })
            }
            composable(READING_LIST) {
                val vm = viewModel { ReadingListViewModel(container.readingList) }
                ReadingListScreen(vm, onBack = { nav.navigateUp() })
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(settingsViewModel(container), onOpen = { nav.navigate("settings/${it.slug}") { launchSingleTop = true } })
            }
            composable(SETTINGS_PAGE, arguments = listOf(navArgument("page") { type = NavType.StringType })) { entry ->
                val page = SettingsPage.of(entry.arguments?.getString("page")) ?: SettingsPage.EDITION
                val context = LocalContext.current.applicationContext
                val feedsFrom = if (page != SettingsPage.FEEDS) null else viewModel { FeedsFromViewModel(container.settings, container.ttrss, container.feedMoves, container.sources, createSavedStateHandle()) { SyncWorker.syncNow(context) } }
                SettingsPageScreen(
                    settingsViewModel(container), page, onBack = { nav.navigateUp() }, feedsFrom = feedsFrom,
                    onOpenNaturalVoice = { nav.navigate(NATURAL_VOICE) { launchSingleTop = true } },
                )
            }
            composable(NATURAL_VOICE) {
                // Leaving by itself, it pops only its own page: a Back pressed meanwhile already has.
                NaturalVoiceScreen(settingsViewModel(container), onBack = { nav.navigateUp() }, onGone = { nav.popBackStack(NATURAL_VOICE, inclusive = true) })
            }
        }
    }
}

@Composable
private fun settingsViewModel(container: AppContainer): SettingsViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel {
        SettingsViewModel(
            container.settings, container.ttrss.observeStatus(),
            hear = { text, speed -> container.listen.sample(text, speed) },
            // Debug builds only until a real morning has been tried with it on a phone (see docs/BACKLOG.md).
            podcast = container.podcastSetup.takeIf { KokoroSupport.inThisBuild(context) },
        ) {
            container.appScope.launch { EditionScheduler.reschedule(context, it) }
        }
    }
}

/** A destination's source or edition id. */
private val NavBackStackEntry.idArg: Long get() = arguments?.getLong("id") ?: 0L

private fun feedRoute(sourceId: Long, key: String) = "source/$sourceId/feed/${Uri.encode(key)}"

/** Where the article at [position] came from, if there's a page of its own to go to. */
internal fun goTo(contents: List<EditionContent>, moving: FeedMoves.State, position: Int): ArticleSource? =
    contents.getOrNull(position)?.source?.takeIf { !moving.hides(it.id, it.paused) }

/**
 * The page for where an article came from: a tt-rss account's feed within it, the reading list's
 * own screen, else the source's page.
 */
private fun sourceRoute(source: ArticleSource) = when {
    source.kind == SourceKind.READING_LIST -> READING_LIST
    source.feedKey != null -> feedRoute(source.id, source.feedKey)
    else -> "source/${source.id}"
}

/** A source's page, or one of a tt-rss account's feeds by its [key]. */
private fun sourceDetail(container: AppContainer, id: Long, key: String = PublicationEntity.OWN, sync: () -> Unit = {}) =
    SourceDetailViewModel(container.sources, id, container.settings.settings.map { it.edition.maxPerSource }, key, sync)
