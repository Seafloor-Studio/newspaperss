package com.app.newspaperss.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.app.newspaperss.listen.ListenState
import com.app.newspaperss.listen.ListenPage
import com.app.newspaperss.ui.listen.ListenBar
import com.app.newspaperss.ui.components.EpisodeJudgement
import com.app.newspaperss.listen.Reading
import com.app.newspaperss.listen.Readings
import com.app.newspaperss.listen.ReadingSpeaker
import com.app.newspaperss.core.episodes.EpisodeKind
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import com.app.newspaperss.testutil.TestDataStores
import com.app.newspaperss.testutil.writeEpub
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isDialog
import com.app.newspaperss.ui.components.LocalClock
import com.app.newspaperss.ui.sources.LeftOutScreen
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.feed.FeedFinder
import com.app.newspaperss.core.feed.StarterPacks
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.PublicationEntity
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.data.SourceRepository
import com.app.newspaperss.delivery.EditionIntents
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.settings.Recordings
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installApp
import com.app.newspaperss.testutil.installVoice
import com.app.newspaperss.testutil.finishDownload
import com.app.newspaperss.testutil.kokoroInstall
import com.app.newspaperss.testutil.runningWork
import androidx.work.WorkInfo
import java.time.Clock
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import com.app.newspaperss.ui.edition.ArticlePreviewScreen
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.listen.KokoroInstall
import com.app.newspaperss.listen.KokoroState
import com.app.newspaperss.listen.ListenBook
import com.app.newspaperss.listen.ListenPlayer
import com.app.newspaperss.listen.ListenPosition
import com.app.newspaperss.listen.Listening
import com.app.newspaperss.listen.PodcastLog
import com.app.newspaperss.listen.PodcastSetup
import com.app.newspaperss.listen.PodcastSpeaker
import com.app.newspaperss.listen.PodcastStore
import com.app.newspaperss.listen.Podcasts
import com.app.newspaperss.listen.StoredListenProgress
import com.app.newspaperss.testutil.FakeSpeaker
import com.app.newspaperss.core.epub.EditionArticle
import com.app.newspaperss.core.epub.EditionDoc
import com.app.newspaperss.core.epub.EditionSection
import com.app.newspaperss.core.epub.EpubWriter
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.onboarding.OnboardingScreen
import com.app.newspaperss.ui.onboarding.OnboardingViewModel
import com.app.newspaperss.ui.onboarding.Step
import com.app.newspaperss.ui.onboarding.FeedsAnswer
import com.app.newspaperss.ui.onboarding.FileImport
import com.app.newspaperss.settings.FeedsFrom
import com.app.newspaperss.data.TtrssAccountStore
import com.app.newspaperss.data.TtrssRepository
import com.app.newspaperss.testutil.FakeTtrss
import com.app.newspaperss.testutil.testCipher
import com.app.newspaperss.ui.settings.FeedsFromViewModel
import com.app.newspaperss.ui.settings.NaturalVoiceScreen
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import com.app.newspaperss.ui.sources.SourcesScreen
import com.app.newspaperss.ui.sources.SourcesViewModel
import com.app.newspaperss.ui.sources.NotInPaperScreen
import com.app.newspaperss.ui.theme.NewspaperssTheme
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.app.newspaperss.data.TtrssSubscriptions
import com.app.newspaperss.data.FeedMoves
import com.app.newspaperss.ui.sources.PhoneFeeds
import kotlinx.coroutines.launch
import com.app.newspaperss.ui.sources.AddState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import androidx.compose.ui.test.onNodeWithContentDescription
import com.app.newspaperss.ui.sources.SourceDetailScreen
import com.app.newspaperss.ui.sources.SourceDetailViewModel
import com.app.newspaperss.core.extract.ContentMode
import com.app.newspaperss.core.extract.FullTextEvidence
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import org.junit.Assert.assertNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders each main screen with real graphics. It catches screens that crash
 * when drawn with realistic data, and leaves PNGs in build/screenshots for
 * reviewing layout changes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()
    @get:Rule(order = 3) val stores = TestDataStores()
    @get:Rule(order = 4) val viewModels = ViewModels()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val store by lazy { SettingsStore(stores.preferences("s")) }
    // Tuesday, just after the sample morning edition. The screens and repositories all read it,
    // so a shot is the same whatever the day or time it is rendered.
    private val clock = Clock.fixed(Instant.parse("2026-09-29T07:15:00Z"), ZoneOffset.UTC)
    private val out = File("build/screenshots").apply { mkdirs() }

    /**
     * [act] runs once [ready], before the capture, e.g. to enter a mode through the UI. With
     * [dialog], only the open dialog is captured; otherwise the screen, with any open menu on it.
     */
    private fun shoot(name: String, ready: () -> Boolean = { true }, act: () -> Unit = {}, dialog: Boolean = false, content: @Composable () -> Unit) {
        // Without ripples: the last tap's lingers, never quite the same twice, so two renders of a
        // screen with a menu opened or rows selected would differ.
        compose.setContent { NewspaperssTheme { CompositionLocalProvider(LocalClock provides clock, LocalRippleConfiguration provides null, content = content) } }
        // Recomposing on each check: some screens only start loading part of what [ready] waits
        // for once they're drawn (Sources' folded categories).
        idleUntil { compose.waitForIdle(); ready() }
        compose.waitForIdle()
        act()
        compose.waitForIdle()
        val bitmap = if (dialog) compose.onNode(isDialog()).captureToImage().asAndroidBitmap() else capture()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The screen with any open menu or dialog drawn over it where it sits. They are windows of their
     * own, and Robolectric captures another window's root as the screen's pixels, so each is drawn
     * from its view instead.
     */
    private fun capture(): Bitmap {
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        if (roots.size == 1) return compose.onRoot().captureToImage().asAndroidBitmap()
        val screen = roots.first()
        val bitmap = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val windows = ReflectionHelpers.getField<List<View>>(global, "mViews")
        roots.drop(1).zip(windows.takeLast(roots.size - 1)).forEach { (root, view) ->
            canvas.save()
            canvas.translate(root.positionOnScreen.x - screen.positionOnScreen.x, root.positionOnScreen.y - screen.positionOnScreen.y)
            view.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }

    /**
     * The store listing's 512×512 icon (Play, and fastlane for F-Droid), drawn from the launcher
     * icon's own layers so it can't drift from the app's. Full-bleed: the stores round it.
     */
    @Test
    fun storeIcon() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (layer in listOf(com.app.newspaperss.R.drawable.ic_launcher_background, com.app.newspaperss.R.drawable.ic_launcher_foreground)) {
            androidx.core.content.ContextCompat.getDrawable(context, layer)!!.apply { setBounds(0, 0, 512, 512); draw(canvas) }
        }
        File(out, "store-icon.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun onboarding() = OnboardingViewModel(store, SourceRepository(db, clock), FeedFinder(FakeHttp())) {}

    @Test
    fun onboardingWelcome() = shoot("01-onboarding-welcome") { OnboardingScreen(onboarding()) }

    @Test
    fun onboardingDevice() {
        val vm = onboarding().apply { next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com") }
        shoot("02-onboarding-device") { OnboardingScreen(vm) }
    }

    /** A Kindle reader sending with the Kindle app: the how-to, and when an edition counts as sent. */
    @Test
    fun onboardingDeviceKindleApp() {
        val vm = onboarding().apply { next(); chooseDevice(Device.KINDLE); useKindleApp() }
        shoot("02f-onboarding-device-kindle-app", act = { compose.onNodeWithText("counts as sent", substring = true).performScrollTo() }) { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingFeedsFrom() {
        val vm = onboarding().apply { next(); chooseDevice(Device.KOBO); next() }
        shoot("02b-onboarding-feeds-from") { OnboardingScreen(vm) }
    }

    /** At the import step, after the question was answered with another reader app. */
    private fun atTheImportStep(): OnboardingViewModel = onboarding().apply {
        next(); chooseDevice(Device.KOBO); next(); answer(FeedsAnswer.OTHER_APP)
        idleUntil { state.value.step == Step.IMPORT }
    }

    @Test
    fun onboardingImport() {
        val vm = atTheImportStep()
        shoot("02c-onboarding-import") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingImported() {
        val vm = atTheImportStep()
        val file = tmp.newFile("feeds.opml").apply {
            writeText(
                "<opml version=\"2.0\"><body>" +
                    StarterPacks.all.flatMap { it.feeds }.take(12).joinToString("") { "<outline type=\"rss\" text=\"${it.title}\" xmlUrl=\"${it.url}\"/>" } +
                    "</body></opml>",
            )
        }
        vm.importOpml(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, android.net.Uri.fromFile(file))
        shoot("02d-onboarding-imported", ready = { vm.state.value.fileImport is FileImport.Done && vm.state.value.phoneFeeds > 0 }) { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingImportFailed() {
        val vm = atTheImportStep()
        vm.importOpml(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, android.net.Uri.fromFile(File(tmp.root, "missing.opml")))
        shoot("02e-onboarding-import-failed", ready = { vm.state.value.fileImport == FileImport.Failed }) { OnboardingScreen(vm) }
    }

    /** Through the fork to this phone's sources step; saving the choice finishes off the main thread. */
    private fun onboardingOnThePhone(vm: OnboardingViewModel) = vm.apply {
        next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com"); next()
        answer(FeedsAnswer.SITES)
        idleUntil { state.value.step == Step.SOURCES }
    }

    /** A site with no feed: its page can go to the reading list instead. */
    @Test
    fun onboardingNoFeed() {
        val http = FakeHttp().apply { page("https://example.com/essay", "<html><head><title>An essay</title></head><body><p>Words.</p></body></html>") }
        val vm = onboardingOnThePhone(OnboardingViewModel(store, SourceRepository(db, clock), FeedFinder(http)) {}).apply {
            editPasted("example.com/essay"); findPasted()
        }
        val readingList = com.app.newspaperss.ui.readinglist.ReadingListViewModel(com.app.newspaperss.data.ReadingListRepository(db, clock))
        shoot("03f-onboarding-no-feed", ready = { vm.state.value.findPage != null }, act = {
            compose.onNodeWithText("Save this page to your reading list instead").performScrollTo()
        }) { OnboardingScreen(vm, readingList = readingList) }
    }

    @Test
    fun onboardingSources() {
        val vm = onboardingOnThePhone(onboarding()).apply { togglePack(StarterPacks.all[1].name) }
        shoot("03-onboarding-sources") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingSize() {
        val vm = onboardingOnThePhone(onboarding()).apply { toggleFeed(StarterPacks.all[0].feeds[0].url); next() }
        shoot("04-onboarding-size") { OnboardingScreen(vm) }
    }

    private val ttrssHttp = FakeHttp()
    private val ttrssAccounts by lazy { TtrssAccountStore(stores.preferences("ttrss"), testCipher()) }
    private val ttrss by lazy { TtrssRepository(db, ttrssHttp, ttrssAccounts, SourceRepository(db, clock), clock) }

    /** A sample tt-rss server: 58 feeds in 7 categories. */
    private fun sampleServer() = FakeTtrss(ttrssHttp).apply {
        val names = listOf("News", "Tech", "Science", "Essays", "Arts", "Local", "Comics")
        names.forEachIndexed { i, name -> categories[i + 1] = name }
        (1..58).forEach { feeds[it] = FakeTtrss.Feed("Feed $it", "https://feed$it.example/rss", categoryId = (it % 7) + 1) }
    }

    /** On the server path, at the sign-in step. */
    private fun onboardingOnTheServer(): OnboardingViewModel =
        OnboardingViewModel(store, SourceRepository(db, clock), FeedFinder(ttrssHttp), ttrss) {}.apply {
            next(); chooseDevice(Device.KINDLE); editKindleEmail("name_abc123@kindle.com"); next()
            answer(FeedsAnswer.SERVER)
            idleUntil { state.value.step == Step.SIGN_IN }
        }

    @Test
    fun onboardingSignIn() {
        val vm = onboardingOnTheServer().apply { editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = "reader")) }
        shoot("03b-onboarding-sign-in") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingSignInTooSoonAfterAWrongPassword() {
        val server = sampleServer().apply { lockoutClock = clock }
        val vm = onboardingOnTheServer().apply {
            editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = server.user, password = "wrong"))
            signIn()
        }
        idleUntil { vm.state.value.signIn.error != null }
        vm.editSignIn(vm.state.value.signIn.copy(password = server.password))
        vm.signIn()
        idleUntil { vm.state.value.signIn.error == TtrssRepository.REFUSED_SOON_AFTER }
        shoot("03b-onboarding-sign-in-too-soon") { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingFoundFeeds() {
        val server = sampleServer()
        val vm = onboardingOnTheServer().apply {
            editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = server.user, password = server.password))
            signIn()
        }
        shoot("03c-onboarding-found-feeds", ready = { vm.state.value.serverFound != null }) { OnboardingScreen(vm) }
    }

    @Test
    fun onboardingExtras() {
        val server = sampleServer()
        val vm = onboardingOnTheServer().apply {
            editSignIn(state.value.signIn.copy(address = "rss.example.com/tt-rss", user = server.user, password = server.password))
            signIn()
        }
        idleUntil { vm.state.value.signedIn }
        vm.next()
        val readingList = com.app.newspaperss.ui.readinglist.ReadingListViewModel(com.app.newspaperss.data.ReadingListRepository(db, clock))
        shoot("03d-onboarding-extras") { OnboardingScreen(vm, readingList = readingList) }
    }

    @Test
    fun today() {
        runBlocking {
            val now = Instant.parse("2026-09-29T06:30:00Z")
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = now.minusSeconds(86_400), status = EditionStatus.DELIVERED, articleCount = 7, minutes = 31.0))
            val latest = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.READY, fileName = "x.epub", articleCount = 8, minutes = 33.4))
            db.editions().insertArticles(sampleArticles(latest))
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Review"))
            (1..2).forEach { db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = "$it", url = "https://example.com/$it", title = "Starred $it", starredAt = now)) }
        }
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        val vm = viewModels.add(TodayViewModel(EditionRepository(db, files, clock), flowOf(null), now = { clock.instant().atZone(clock.zone) }) {})
        shoot("05-today", ready = { vm.state.value.editions?.isNotEmpty() == true }) { TodayScreen(vm) }
    }

    /** A tester's debug build with a newer one out, and how far it is with installing that itself. */
    private fun todayWithNewerBuild(name: String, selfUpdate: com.app.newspaperss.SelfUpdate = com.app.newspaperss.SelfUpdate.None) {
        runBlocking {
            val latest = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = Instant.parse("2026-09-29T06:30:00Z"), status = EditionStatus.DELIVERED, fileName = "x.epub", articleCount = 8, minutes = 33.4))
            db.editions().insertArticles(sampleArticles(latest))
        }
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        val vm = viewModels.add(TodayViewModel(EditionRepository(db, files, clock), flowOf(null), now = { clock.instant().atZone(clock.zone) }) {})
        shoot(name, ready = { vm.state.value.editions?.isNotEmpty() == true }) { TodayScreen(vm, newerBuild = 464, selfUpdate = selfUpdate) }
    }

    @Test
    fun todayWithANewerBuild() = todayWithNewerBuild("05i-today-newer-build")

    @Test
    fun todayDownloadingANewerBuild() = todayWithNewerBuild("05i-today-newer-build-downloading", com.app.newspaperss.SelfUpdate.Downloading(464, waitingForWifi = false))

    @Test
    fun todayWaitingForWifiForANewerBuild() = todayWithNewerBuild("05i-today-newer-build-on-wifi", com.app.newspaperss.SelfUpdate.Downloading(464, waitingForWifi = true))

    @Test
    fun todayWithANewerBuildReady() = todayWithNewerBuild("05i-today-newer-build-ready", com.app.newspaperss.SelfUpdate.Ready(464))

    @Test
    fun todayWithANewerBuildNeedingPermission() = todayWithNewerBuild("05i-today-newer-build-allow", com.app.newspaperss.SelfUpdate.NeedsPermission(464))

    /** The evening before, with the podcast in use on a Pixel 8: when the paper starts, and to leave the phone charging. */
    @Test
    fun todayWithThePodcast() {
        runBlocking {
            val latest = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = Instant.parse("2026-09-29T06:30:00Z"), status = EditionStatus.DELIVERED, fileName = "x.epub", articleCount = 8, minutes = 33.4))
            db.editions().insertArticles(sampleArticles(latest))
        }
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        val podcast = Settings(scheduleEnabled = true, listenVoice = ListenVoice.PODCAST, podcastPace = 1.2f)
        val evening = java.time.ZonedDateTime.of(2026, 9, 29, 21, 0, 0, 0, java.time.ZoneOffset.UTC)
        val vm = viewModels.add(TodayViewModel(EditionRepository(db, files, clock), flowOf(null), settings = flowOf(podcast), now = { evening }) {})
        shoot("05h-today-podcast", ready = { vm.state.value.next != null && vm.state.value.editions?.isNotEmpty() == true }) { TodayScreen(vm) }
    }

    /** Eight sample articles for an edition's card, the second one starred. */
    private fun sampleArticles(edition: Long) = listOf(
        "BBC News" to "Flooding forces thousands from their homes in northern Italy" to 3.0,
        "Quanta Magazine" to "The Mathematician Who Counted the Shapes of Knots" to 8.0,
        "Rest of World" to "Why Lagos's ride-hailing drivers built their own app" to 6.0,
        "Nautilus" to "What do octopuses dream about?" to 5.0,
        "ProPublica" to "The hidden cost of hospital price lists" to 6.0,
        "The Verge" to "A week with the e-reader that wants to be a notebook" to 2.0,
        "xkcd" to "Orbital Mechanics" to 1.0,
        "Ars Technica" to "Webb spots water vapour around a young star" to 2.4,
    ).mapIndexed { i, (sourceAndTitle, minutes) ->
        EditionArticleEntity(editionId = edition, articleId = null, position = i, title = sourceAndTitle.second, sourceTitle = sourceAndTitle.first, minutes = minutes, starred = i == 1)
    }

    private fun installKindle() = installApp(
        ApplicationProvider.getApplicationContext(),
        packageName = EditionIntents.KINDLE_PACKAGE,
        label = "Kindle",
        filters = listOf(IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }),
    )

    /** Today with yesterday's edition and today's, both sent, for a reader with [device]. */
    private fun todaySent(name: String, device: Device, kindleNote: Boolean = false, act: () -> Unit = {}) {
        if (device == Device.KINDLE) installKindle()
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        val latest = runBlocking {
            val now = Instant.parse("2026-09-29T06:30:00Z")
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = now.minusSeconds(86_400), status = EditionStatus.DELIVERED, articleCount = 7, minutes = 31.0))
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.DELIVERED, fileName = "x.epub", articleCount = 8, minutes = 33.4, deliveredAt = now))
                .also { db.editions().insertArticles(sampleArticles(it)) }
        }
        val sent = if (kindleNote) mapOf(latest to KindleSend.APP) else emptyMap()
        val vm = viewModels.add(TodayViewModel(EditionRepository(db, files, clock), flowOf(null), settings = flowOf(Settings(device = device)), sentToKindle = flowOf(sent), now = { clock.instant().atZone(clock.zone) }) {})
        shoot(name, ready = { vm.state.value.editions?.isNotEmpty() == true }, act = act) { TodayScreen(vm) }
    }

    /** Opens a sent edition's menu, where there is one (not before it was added). */
    private fun openMenu(description: String) {
        val menu = hasContentDescription(description)
        if (compose.onAllNodes(menu).fetchSemanticsNodes().isNotEmpty()) compose.onNode(menu).performClick()
    }

    /** The newest edition left is one released as not sent: Mark as sent, and making another. */
    @Test
    fun todayNotSent() {
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        runBlocking {
            val now = Instant.parse("2026-09-29T06:30:00Z")
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = now.minusSeconds(86_400), status = EditionStatus.DELIVERED, articleCount = 7, minutes = 31.0))
            db.editions().insert(
                EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.FAILED, error = com.app.newspaperss.edition.EditionBuilder.NOT_SENT, fileName = "x.epub", articleCount = 8, minutes = 33.4),
            ).also { db.editions().insertArticles(sampleArticles(it)) }
        }
        val vm = viewModels.add(TodayViewModel(EditionRepository(db, files, clock), flowOf(null), now = { clock.instant().atZone(clock.zone) }) {})
        shoot("05l-today-not-sent", ready = { vm.state.value.editions?.isNotEmpty() == true }) { TodayScreen(vm) }
    }

    @Test
    fun todaySentKindle() = todaySent("05d-today-sent-kindle", Device.KINDLE, kindleNote = true)

    @Test
    fun todaySentBoox() = todaySent("05e-today-sent-boox", Device.BOOX)

    @Test
    fun todaySentMenu() = todaySent("05f-today-sent-menu", Device.KINDLE) { openMenu("More options for Tuesday Morning Edition") }

    @Test
    fun editionDetail() {
        val vm = sentEdition()
        val listening = listening()
        shoot("05b-edition-detail", ready = { vm.detail.value?.contents?.isNotEmpty() == true }) { EditionDetailScreen(vm, onBack = {}, listening = listening) }
    }

    /** Posts the episode detector judged: a letter and its reading, show notes, a transcript, and an article. */
    @Test
    fun editionDetailEpisodes() {
        val id = runBlocking {
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Letter"))
            val posts = listOf(
                "October 15" to EpisodeKind.ARTICLE,
                "A reading of the letter" to EpisodeKind.AUDIO_VERSION,
                "How a ferry timetable is made, with its planner" to EpisodeKind.NOTES,
                "Transcript: the ferry planner" to EpisodeKind.TRANSCRIPT,
                "Why city trees are planted in pairs" to EpisodeKind.ARTICLE,
            )
            val ids = posts.mapIndexed { i, (title, _) -> db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = title, url = "https://example.com/$i", title = title, state = ArticleState.DELIVERED)) }
            db.articles().pairEpisode(source, posts[0].first, posts[1].first, EpisodeKind.ARTICLE)
            db.articles().pairEpisode(source, posts[1].first, posts[0].first, EpisodeKind.AUDIO_VERSION)
            posts.drop(2).forEach { (title, kind) -> db.articles().setEpisodeKind(source, title, kind) }
            val now = Instant.parse("2026-09-29T06:30:00Z")
            val edition = db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.DELIVERED, fileName = "e.epub", articleCount = 5, minutes = 52.0, deliveredAt = now))
            db.editions().insertArticles(posts.mapIndexed { i, (title, _) ->
                EditionArticleEntity(editionId = edition, articleId = ids[i], position = i, title = title, sourceTitle = "The Example Letter", minutes = listOf(10.0, 1.0, 2.0, 30.0, 9.0)[i])
            })
            edition
        }
        val vm = viewModels.add(EditionDetailViewModel(EditionRepository(db, tmp.newFolder().apply { resolve("e.epub").writeText("epub") }, clock), id) {})
        shoot("05k-edition-detail-episodes", ready = { vm.detail.value?.contents?.any { it.episodeKind != null } == true }, act = {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Why city trees are planted in pairs"))
        }) { EditionDetailScreen(vm, onBack = {}) }
    }

    /** Not sent before the next was made: released, and can still be marked as sent. */
    @Test
    fun editionDetailNotSent() {
        val vm = sentEdition(notSent = true)
        val listening = listening()
        shoot("05j-edition-detail-not-sent", ready = { vm.detail.value?.contents?.isNotEmpty() == true }) { EditionDetailScreen(vm, onBack = {}, listening = listening) }
    }

    /** Released as not sent while a newer edition is being made: Mark as sent waits, and says why. */
    @Test
    fun editionDetailNotSentWhileOneIsMade() {
        val vm = sentEdition(notSent = true)
        runBlocking { db.editions().insert(EditionEntity(title = "Wednesday Morning Edition", createdAt = clock.instant(), status = EditionStatus.BUILDING)) }
        val listening = listening()
        shoot("05j2-edition-detail-not-sent-while-one-is-made", ready = { vm.detail.value?.contents?.isNotEmpty() == true && vm.building.value }) {
            EditionDetailScreen(vm, onBack = {}, listening = listening)
        }
    }

    /** Kokoro in use, the first two of four articles made: the bar under Listen. */
    @Test
    fun editionDetailWithThePodcast() {
        val vm = sentEdition()
        val (player, _) = listenPlayer()
        runBlocking { store.update { it.copy(listenVoice = ListenVoice.PODCAST, podcastPace = 1.2f) } }
        val install = kokoroInstall(tmp.newFolder())
        val made = PodcastStore(tmp.newFolder())
        val id = runBlocking { db.editions().observeAll().first().first().id }
        made.want(id, PodcastVoice.HEART)
        made.keep(id, 0, 0, made.scratch(id, 0, 0).apply { writeBytes(ByteArray(4_000_000)) }, listOf(0.0))
        made.complete(id, 0)
        made.complete(id, 1)
        val listening = Listening(
            player, StoredListenProgress(ApplicationProvider.getApplicationContext()), EditionRepository(db, tmp.newFolder(), clock),
            Podcasts(made, store, install) {},
        ) {}
        shoot("05i-edition-detail-podcast", ready = {
            vm.detail.value?.contents?.isNotEmpty() == true && compose.onAllNodes(hasText("It carries on while the phone charges", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }) { EditionDetailScreen(vm, onBack = {}, listening = listening) }
    }

    /** A player with a voice that says nothing, over [db]'s editions in [files]. */
    private fun listenPlayer(files: File = tmp.newFolder(), readings: Readings? = null): Pair<ListenPlayer, FakeSpeaker> {
        val repo = EditionRepository(db, files, clock)
        val speaker = FakeSpeaker()
        val progress = StoredListenProgress(ApplicationProvider.getApplicationContext())
        return ListenPlayer(speaker, progress, open = { ListenBook.open(repo, it, readings) }, kotlinx.coroutines.MainScope()) to speaker
    }

    private fun listening(): Listening {
        val (player, _) = listenPlayer()
        return Listening(player, StoredListenProgress(ApplicationProvider.getApplicationContext()), EditionRepository(db, tmp.newFolder(), clock)) {}
    }

    /** The app's tabs, with Listen's bar over them as MainActivity puts it, around [content]. */
    @Composable
    private fun WithListenBar(playing: Boolean, loading: Boolean = false, content: @Composable () -> Unit) {
        val state = if (loading) ListenState(editionId = 1, loading = true, playing = true) else ListenState(
            editionId = 1, editionTitle = "Thursday Morning Edition", playing = playing,
            pages = listOf(ListenPage("The Mathematician Who Counted the Shapes of Knots", "Quanta Magazine", 8.0)),
        )
        Scaffold(
            bottomBar = {
                Column {
                    ListenBar(state, onOpen = {}, onToggle = {}, onClose = {})
                    NavigationBar {
                        listOf("Today" to Icons.Default.Newspaper, "Sources" to Icons.AutoMirrored.Filled.List, "Settings" to Icons.Default.Settings).forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(selected = i == 0, onClick = {}, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                        }
                    }
                }
            },
        ) { padding -> Box(Modifier.padding(padding)) { content() } }
    }

    private fun todayWithListenBar(name: String, playing: Boolean, loading: Boolean = false) {
        runBlocking {
            val now = Instant.parse("2026-09-29T06:30:00Z")
            val latest = db.editions().insert(EditionEntity(title = "Thursday Morning Edition", createdAt = now, status = EditionStatus.READY, fileName = "x.epub", articleCount = 8, minutes = 33.4))
            db.editions().insertArticles(sampleArticles(latest))
        }
        val files = tmp.newFolder().apply { resolve("x.epub").writeText("epub") }
        val vm = viewModels.add(TodayViewModel(EditionRepository(db, files, clock), flowOf(null), now = { clock.instant().atZone(clock.zone) }) {})
        shoot(name, ready = { vm.state.value.editions?.isNotEmpty() == true }) { WithListenBar(playing, loading) { TodayScreen(vm) } }
    }

    /** Today with Listen's bar over the tabs, while an edition plays. */
    @Test
    fun todayWithTheListenBar() = todayWithListenBar("11h-listen-bar", playing = true)

    /** Paused: the bar stays, with Play, until it's stopped or heard to the end. */
    @Test
    fun todayWithTheListenBarPaused() = todayWithListenBar("11h2-listen-bar-paused", playing = false)

    /** Opening an edition, as when switching to another: the bar stays, saying so. */
    @Test
    fun todayWithTheListenBarOpening() = todayWithListenBar("11h4-listen-bar-opening", playing = true, loading = true)

    /** At twice the font size: one line each, cut short, the buttons still in reach. */
    @Test
    @Config(fontScale = 2f)
    fun todayWithTheListenBarAtTwiceTheFontSize() = todayWithListenBar("11h3-listen-bar-font-200", playing = true)

    /** One view: the article preview while Listen plays it, the quote being read and the player under the page. */
    @Test
    fun articlePreviewListening() {
        val files = tmp.newFolder()
        val article = FORMATTED
        File(files, "f.epub").outputStream().use {
            EpubWriter.write(EditionDoc("Thursday Morning Edition", LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000013", listOf(EditionSection(null, listOf(article)))), it)
        }
        val id = runBlocking {
            val id = db.editions().insert(EditionEntity(title = "Thursday Morning Edition", status = EditionStatus.DELIVERED, fileName = "f.epub", articleCount = 1, minutes = 3.0))
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = id, articleId = null, position = 0, title = article.title, sourceTitle = article.sourceTitle, minutes = 3.0)))
            id
        }
        val (player, speaker) = listenPlayer(files)
        player.start(id, ListenPosition(0, 4))
        idleUntil { player.state.value.script != null }
        speaker.startNext()
        shoot("05c3-article-preview-listening", ready = { player.state.value.at.line == 4 && compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { File(files, "f.epub") }, position = 0, title = article.title, onBack = {}, onListen = {}, player = player, editionId = id)
        }
    }

    /** Listening to an article, two sentences in, its picture just ahead. */
    @Test
    fun listenPlaying() = shootListening("11a-listen-playing", voice = null)

    /** In the phone's voice, the podcast not having reached this article yet. */
    @Test
    fun listenPlayingBeforeThePodcastReachesIt() =
        shootListening("11c-listen-not-made-yet", voice = null, instead = PodcastSpeaker.Instead.NOT_MADE_YET)

    /** The same article playing from its podcast: the voice's name by the source. */
    @Test
    fun listenPlayingThePodcast() = shootListening("11b-listen-podcast", voice = PodcastVoice.HEART)

    /** At Settings › Article text size Larger, as the preview would show it. */
    @Test
    fun listenPlayingLargerText() = shootListening("11d-listen-larger-text", voice = null, textSize = PreviewTextSize.LARGER)

    /** An article playing its recorded reading, with the switch back to the voice. */
    @Test
    fun listenPlayingRecording() = shootListening("11e-listen-recording", voice = null, recording = true)

    /** A transcript playing its episode in its place. */
    @Test
    fun listenPlayingEpisode() = shootListening("11f-listen-episode", voice = null, recording = true, episode = true)

    /** Past the edition's end: a notes post's episode, playing on a page of its own. */
    @Test
    fun listenPlayingAnEpisodeAtTheEnd() = shootEditionWithEpisodes("11g-listen-episode-at-the-end", page = 4)

    /** In the edition, with two notes posts' episodes waiting after it. */
    @Test
    fun listenPlayingAnEditionWithEpisodesAfterIt() = shootEditionWithEpisodes("11g2-listen-episodes-after", page = 1)

    /** Three articles, two of them notes posts: the closing page is page 3, their episodes 4 and 5. */
    private fun shootEditionWithEpisodes(name: String, page: Int) {
        val files = tmp.newFolder()
        val articles = listOf(
            EditionArticle(title = "Episode 212: The ferry planner", sourceTitle = "The Example Show", url = "https://example.com/212", bodyHtml = "<p>This week, a ferry planner.</p>", minutes = 1.0),
            EditionArticle(title = "What do octopuses dream about?", sourceTitle = "Nautilus", url = "https://example.com/o", bodyHtml = "<p>Text.</p>", minutes = 5.0),
            EditionArticle(title = "Episode 48: Night trains", sourceTitle = "Rail Talk", url = "https://example.com/48", bodyHtml = "<p>Night trains are back.</p>", minutes = 1.0),
        )
        File(files, "e.epub").outputStream().use {
            EpubWriter.write(EditionDoc("Thursday Morning Edition", LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000011", listOf(EditionSection(null, articles))), it)
        }
        val id = runBlocking {
            val show = db.sources().insert(SourceEntity(url = "https://example.com/show.xml", title = "The Example Show"))
            val rail = db.sources().insert(SourceEntity(url = "https://example.com/rail.xml", title = "Rail Talk"))
            fun notes(source: Long, guid: String, a: EditionArticle, seconds: Int) =
                ArticleEntity(sourceId = source, guid = guid, url = a.url, title = a.title, episodeKind = EpisodeKind.NOTES, audioUrl = "https://cdn.example/$guid.mp3", audioSeconds = seconds)
            val ids = listOf(db.articles().insertIgnoring(notes(show, "212", articles[0], 3_480)), null, db.articles().insertIgnoring(notes(rail, "48", articles[2], 2_700)))
            val id = db.editions().insert(EditionEntity(title = "Thursday Morning Edition", status = EditionStatus.DELIVERED, fileName = "e.epub", articleCount = articles.size, minutes = 7.0))
            db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = id, articleId = ids[i], position = i, title = a.title, sourceTitle = a.sourceTitle, minutes = a.minutes) })
            id
        }
        val readings = Readings(db, { Recordings() })
        val (player, speaker) = listenPlayer(files, readings)
        player.start(id, ListenPosition(page, 0))
        idleUntil { player.state.value.script != null }
        speaker.startNext()
        val now = readings.at(id, page)?.let { ReadingSpeaker.Now(id, page, it, voiceInstead = false) }
        val title = player.state.value.pages[page].title
        shoot(name, ready = { player.state.value.at.page == page && compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { File(files, "e.epub") }, position = page, title = title, onBack = {}, onListen = {}, player = player, editionId = id, reading = MutableStateFlow(now))
        }
    }

    private fun shootListening(name: String, voice: PodcastVoice?, instead: PodcastSpeaker.Instead? = null, textSize: PreviewTextSize = PreviewTextSize.DEFAULT, recording: Boolean = false, episode: Boolean = false) {
        val files = tmp.newFolder()
        val picture = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(android.graphics.Color.rgb(228, 221, 204))
            val ink = android.graphics.Paint().apply { color = android.graphics.Color.rgb(74, 71, 64); strokeWidth = 8f; style = android.graphics.Paint.Style.STROKE }
            for (i in 0 until 4) canvas.drawCircle(90f + i * 140f, 150f, 55f, ink)
        }
        val png = java.io.ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val knots = EditionArticle(
            title = "The Mathematician Who Counted the Shapes of Knots", sourceTitle = "Quanta Magazine", url = "https://example.com/knots",
            bodyHtml = "<p>For forty years, nobody could say how many ways a loop of string can tangle. Then a retired schoolteacher wrote a program.</p>" +
                "<p>The question sounds like a puzzle for a rainy afternoon. Tie a knot in a piece of string, fuse the ends, and ask which knots are really different. Two that look nothing alike may be the same knot, pulled into a new shape.</p>" +
                "<figure><img src=\"images/knots.png\"/><figcaption>Tait's 1877 table of knots, drawn by hand</figcaption></figure>" +
                "<p>Mathematicians had catalogued them by hand since the 1870s.</p>",
            minutes = 8.0, author = "Erica Klarreich", images = listOf(com.app.newspaperss.core.epub.EpubImage("images/knots.png", "image/png", png)),
        )
        val others = listOf(
            "Rest of World" to "Why Lagos's ride-hailing drivers built their own app" to 6.0,
            "Nautilus" to "What do octopuses dream about?" to 5.0,
            "ProPublica" to "The hidden cost of hospital price lists" to 6.0,
        ).map { (st, minutes) -> EditionArticle(title = st.second, sourceTitle = st.first, url = "https://example.com/x", bodyHtml = "<p>Text.</p>", minutes = minutes) }
        val articles = listOf(EditionArticle(title = "Flooding forces thousands from their homes in northern Italy", sourceTitle = "BBC News", url = "https://example.com/f", bodyHtml = "<p>Text.</p>", minutes = 3.0), knots) + others
        File(files, "l.epub").outputStream().use {
            EpubWriter.write(EditionDoc("Thursday Morning Edition", LocalDate.of(2026, 10, 1), "urn:uuid:5b1f3c8e-0000-4000-8000-000000000010", listOf(EditionSection(null, articles))), it)
        }
        val id = runBlocking {
            val id = db.editions().insert(EditionEntity(title = "Thursday Morning Edition", status = EditionStatus.DELIVERED, fileName = "l.epub", articleCount = articles.size, minutes = articles.sumOf { it.minutes }))
            db.editions().insertArticles(articles.mapIndexed { i, a -> EditionArticleEntity(editionId = id, articleId = null, position = i, title = a.title, sourceTitle = a.sourceTitle, minutes = a.minutes) })
            id
        }
        val (player, speaker) = listenPlayer(files)
        player.start(id, ListenPosition(1, 6))
        idleUntil { player.state.value.script != null }
        speaker.startNext()
        val now = ReadingSpeaker.Now(id, 1, Reading("https://example.com/knots.mp3", if (episode) 5_220 else 540, own = episode, bodyLine = 3, starts = listOf(0.0), episode = episode), voiceInstead = false).takeIf { recording }
        shoot(name, ready = { player.state.value.at.line == 6 && compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(
                loadFile = { File(files, "l.epub") }, position = 1, title = knots.title, onBack = {}, onListen = {}, textSize = textSize,
                player = player, editionId = id, podcastVoice = MutableStateFlow(voice), instead = MutableStateFlow(instead), reading = MutableStateFlow(now),
            )
        }
    }

    @Test
    fun editionDetailKindleMenu() {
        installKindle()
        val vm = sentEdition()
        shoot("05g-edition-detail-kindle-menu", ready = { vm.detail.value?.contents?.isNotEmpty() == true }, act = { openMenu("More options") }) {
            EditionDetailScreen(vm, onBack = {}, offerOpen = false, kindleReader = true)
        }
    }

    private fun sentEdition(notSent: Boolean = false): EditionDetailViewModel {
        val id = runBlocking {
            val source = db.sources().insert(SourceEntity(url = "https://example.com/feed", title = "The Example Review"))
            val titles = listOf(
                "The quiet return of the night train",
                "What a century of weather records says about spring",
                "A short history of the paperback",
                "Why city trees are planted in pairs",
            )
            val articles = titles.mapIndexed { i, title ->
                db.articles().insertIgnoring(
                    ArticleEntity(
                        sourceId = source, guid = "$i", url = "https://example.com/$i", title = title, state = if (notSent) ArticleState.NEW else ArticleState.DELIVERED,
                        starredAt = if (i == 1) Instant.parse("2026-09-29T08:00:00Z") else null,
                    ),
                )
            }
            val now = Instant.parse("2026-09-29T06:30:00Z")
            val edition = db.editions().insert(
                if (notSent) {
                    EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.FAILED, error = com.app.newspaperss.edition.EditionBuilder.NOT_SENT, fileName = "e.epub", articleCount = 4, minutes = 27.5)
                } else {
                    EditionEntity(title = "Tuesday Morning Edition", createdAt = now, status = EditionStatus.DELIVERED, fileName = "e.epub", articleCount = 4, minutes = 27.5, deliveredAt = now)
                },
            )
            db.editions().insertArticles(
                titles.mapIndexed { i, title ->
                    EditionArticleEntity(editionId = edition, articleId = articles[i], position = i, title = title, sourceTitle = "The Example Review", minutes = 4.0 + 3 * i, starred = i == 0)
                },
            )
            edition
        }
        return viewModels.add(EditionDetailViewModel(EditionRepository(db, tmp.newFolder().apply { resolve("e.epub").writeText("epub") }, clock), id) {})
    }

    @Test
    fun articlePreview() {
        val file = tmp.newFile("p.epub")
        val article = EditionArticle(title = "The quiet return of the night train", sourceTitle = "The Example Review", url = "https://example.com/night-train", bodyHtml = "<p>Sleeper services are coming back.</p>", minutes = 4.0)
        file.writeEpub(listOf(article), title = "Tuesday Morning Edition")
        shoot("05c-article-preview", ready = { compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {}, onListen = {})
        }
    }

    /** An article with its formatting: bold, a link, a quote, a numbered list, a table and code. */
    @Test
    fun articlePreviewFormatted() {
        val file = tmp.newFile("pf.epub")
        file.writeEpub(listOf(FORMATTED), title = "Tuesday Morning Edition")
        shoot("05c2-article-preview-formatted", ready = { compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = FORMATTED.title, onBack = {}, onListen = {})
        }
    }

    /** The ⋮ on an article: the way to the feed it came from, then Send for a look. */
    @Test
    fun articlePreviewMenu() {
        val file = tmp.newFile("m.epub")
        val article = EditionArticle(title = "The quiet return of the night train", sourceTitle = "The Example Review", url = "https://example.com/night-train", bodyHtml = "<p>Sleeper services are coming back.</p>", minutes = 4.0)
        file.writeEpub(listOf(article), title = "Tuesday Morning Edition")
        shoot("05f-article-preview-menu", ready = { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }, act = {
            compose.onNodeWithContentDescription("More").performClick()
        }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {}, onListen = {}, sourceAt = { "The Example Review" }, onOpenSource = {})
        }
    }

    /** An episode post: the line above it says what it is. */
    @Test
    fun articlePreviewEpisode() {
        val file = tmp.newFile("p.epub")
        val article = EditionArticle(title = "How a ferry timetable is made, with its planner", sourceTitle = "The Example Letter", url = "https://example.com/ferry", bodyHtml = "<p>This week's guest plans ferries.</p>", minutes = 2.0)
        file.writeEpub(listOf(article), title = "Tuesday Morning Edition")
        shoot("05c-article-preview-episode", ready = { compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {}, onListen = {}, episodeFor = { EpisodeJudgement(EpisodeKind.NOTES) })
        }
    }

    /** Send for a look on an episode post: the second reason, a wrong label, picked. */
    @Test
    fun articlePreviewSendForALookLabel() {
        val file = tmp.newFile("p.epub")
        val article = EditionArticle(title = "How a ferry timetable is made, with its planner", sourceTitle = "The Example Letter", url = "https://example.com/ferry", bodyHtml = "<p>This week's guest plans ferries.</p>", minutes = 2.0)
        file.writeEpub(listOf(article), title = "Tuesday Morning Edition")
        shoot("05e-article-preview-look-label", ready = { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }, act = {
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Send for a look…").performClick()
            compose.onNodeWithText("It's labelled “Episode notes”, and it's…").performClick()
            compose.onNodeWithText("An article").performClick()
        }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {}, onListen = {}, episodeFor = { EpisodeJudgement(EpisodeKind.NOTES) })
        }
    }

    /** Send for a look: what it says before anything is sent. */
    @Test
    fun articlePreviewSendForALook() {
        val file = tmp.newFile("p.epub")
        val article = EditionArticle(title = "The quiet return of the night train", sourceTitle = "The Example Review", url = "https://example.com/night-train", bodyHtml = "<p>Sleeper services are coming back.</p>", minutes = 4.0)
        file.writeEpub(listOf(article), title = "Tuesday Morning Edition")
        shoot("05e-article-preview-look", ready = { compose.onAllNodes(hasContentDescription("More")).fetchSemanticsNodes().isNotEmpty() }, act = {
            compose.onNodeWithContentDescription("More").performClick()
            compose.onNodeWithText("Send for a look…").performClick()
        }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {}, onListen = {})
        }
    }

    /** The top bar at twice the font size: the title stays on one line beside Back and Share. */
    @Test
    @Config(fontScale = 2f)
    fun articlePreviewAtTwiceTheFontSize() {
        val file = tmp.newFile("p.epub")
        val article = EditionArticle(title = "The quiet return of the night train", sourceTitle = "The Example Review", url = "https://example.com/night-train", bodyHtml = "<p>Sleeper services are coming back.</p>", minutes = 4.0)
        file.writeEpub(listOf(article), title = "Tuesday Morning Edition")
        shoot("05d-article-preview-200", ready = { compose.onAllNodes(hasText("Opening…")).fetchSemanticsNodes().isEmpty() }) {
            ArticlePreviewScreen(loadFile = { file }, position = 0, title = article.title, onBack = {}, textSize = PreviewTextSize.LARGEST, onListen = {})
        }
    }

    /** The reading list: links with their reading time, and one that can never go in the paper. */
    @Test
    fun readingList() {
        val list = com.app.newspaperss.data.ReadingListRepository(db, clock)
        runBlocking {
            list.save("https://example.com/2026/the-quiet-return-of-the-night-train", "The quiet return of the night train")
            list.save("https://example.org/essays/on-walking", "On walking")
            list.save("https://example.net/files/annual-report.pdf", "Annual report 2025")
            val saved = db.articles().allForSource(list.sourceId()).associateBy { it.title }
            db.articles().setPageWords(saved.getValue("The quiet return of the night train").id, 1900)
            db.articles().setPageWords(saved.getValue("On walking").id, 3400)
            db.articles().setUnreadable(saved.getValue("Annual report 2025").id, "not a web page")
        }
        val vm = com.app.newspaperss.ui.readinglist.ReadingListViewModel(list)
        shoot("09a-reading-list", ready = { vm.items.value?.size == 3 }) { com.app.newspaperss.ui.readinglist.ReadingListScreen(vm, onBack = {}) }
    }

    @Test
    fun shareAnArticle() = shoot("09b-share-article", dialog = true) {
        com.app.newspaperss.ui.share.ShareChoicesDialog("The quiet return of the night train", "news.example", frontPage = false, onChoose = {}, onCancel = {})
    }

    @Test
    fun shareAFrontPage() = shoot("09c-share-front-page", dialog = true) {
        com.app.newspaperss.ui.share.ShareChoicesDialog(null, "news.example", frontPage = true, onChoose = {}, onCancel = {})
    }

    @Test
    fun sources() {
        val repo = SourceRepository(db, clock)
        runBlocking {
            StarterPacks.all[3].feeds.forEach { repo.addFeed(it.url, it.title) }
            val broken = repo.addFeed("https://broken.example/feed", "A blog that moved")
            db.sources().recordFailure(broken, clock.instant(), "We can't get new articles from this site any more. It may have moved; try adding it again.")
        }
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SourcesViewModel(repo, FeedFinder(FakeHttp()), settings = store) {}
        shoot("06-sources", ready = { vm.screen.value?.rows?.isNotEmpty() == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    /** A source with a row in each state the design shows: waiting, starred, marked read, in an unsent edition, delivered (two days ago), got old. */
    private fun sourceWithArticles(): SourceDetailViewModel {
        val repo = SourceRepository(db, clock)
        val id = runBlocking {
            val id = repo.addFeed("https://www.theguardian.com/world/rss", "The Guardian: World")
            val titles = listOf(
                "Talks resume after a week of storms",
                "The town that voted to keep its library open",
                "What the census says about who moves where",
                "A short history of the night train",
                "Why city trees are planted in pairs",
                "The quiet return of the paperback",
            )
            db.articles().insertNew(titles.mapIndexed { i, t -> ArticleEntity(sourceId = id, guid = "$i", url = "https://www.theguardian.com/$i", title = t, discoveredAt = clock.instant().minusSeconds(3_600L * (i + 1))) })
            val ids = db.articles().candidates().sortedBy { it.guid }.map { it.id }
            repo.setStarred(ids[1], true)
            db.articles().setState(listOf(ids[2]), ArticleState.SKIPPED)
            db.articles().setState(listOf(ids[3]), ArticleState.IN_EDITION)
            val today = LocalDate.now(clock).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val edition = db.editions().insert(EditionEntity(title = "$today Morning Edition", status = EditionStatus.READY))
            db.editions().insertArticles(listOf(EditionArticleEntity(editionId = edition, articleId = ids[3], position = 0, title = titles[3], sourceTitle = "The Guardian: World", minutes = 6.0)))
            db.articles().setState(listOf(ids[4]), ArticleState.DELIVERED)
            db.articles().rememberDelivered(listOf(ids[4]), clock.instant().minus(Duration.ofDays(2)))
            db.articles().setState(listOf(ids[5]), ArticleState.EXPIRED)
            db.sources().recordSuccess(id, clock.instant(), null, "https://www.theguardian.com", "")
            db.sources().savePublication(PublicationEntity(id, PublicationEntity.OWN, ContentMode.PAGE, FullTextEvidence.PAGE_LONGER, 3))
            id
        }
        return viewModels.add(SourceDetailViewModel(repo, id, flowOf(1)))
    }

    /** Scrolled to the articles, which sit below the source's settings. */
    private fun scrollToArticles() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("articles", substring = true) and isHeading())
    }

    @Test
    fun sourceDetail() {
        val vm = sourceWithArticles()
        shoot("06b-source-detail", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
    }

    /** A newsletter that posts episodes: how often, and each one's label. */
    @Test
    fun sourceDetailEpisodes() {
        val repo = SourceRepository(db, clock)
        val id = runBlocking {
            val id = repo.addFeed("https://example.com/feed", "The Example Letter")
            val posts = listOf(
                "How a ferry timetable is made, with its planner" to EpisodeKind.NOTES,
                "The case for slower trains" to EpisodeKind.ARTICLE,
                "Transcript: the ferry planner" to EpisodeKind.TRANSCRIPT,
                "October 15" to EpisodeKind.ARTICLE,
                "A reading of the letter" to EpisodeKind.AUDIO_VERSION,
                "Why city trees are planted in pairs" to EpisodeKind.ARTICLE,
            )
            db.articles().insertNew(posts.mapIndexed { i, (title, _) -> ArticleEntity(sourceId = id, guid = title, url = "https://example.com/$i", title = title, discoveredAt = clock.instant().minusSeconds(3_600L * (i + 1))) })
            db.articles().pairEpisode(id, "October 15", "A reading of the letter", EpisodeKind.ARTICLE)
            db.articles().pairEpisode(id, "A reading of the letter", "October 15", EpisodeKind.AUDIO_VERSION)
            db.articles().pairEpisode(id, "Transcript: the ferry planner", posts[0].first, EpisodeKind.TRANSCRIPT)
            posts.filter { it.first !in setOf("October 15", "A reading of the letter", "Transcript: the ferry planner") }.forEach { (title, kind) -> db.articles().setEpisodeKind(id, title, kind) }
            db.sources().recordSuccess(id, clock.instant(), null, "https://example.com", "")
            id
        }
        val vm = viewModels.add(SourceDetailViewModel(repo, id, flowOf(1)))
        shoot("06g-source-detail-episodes", ready = { vm.detail.value?.episodePairs?.isNotEmpty() == true }, act = {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Posts episodes", substring = true))
        }) { SourceDetailScreen(vm, onBack = {}) }
    }

    /** A busy source lists only its newest articles, and the heading says so rather than looking like a total. */
    @Test
    fun sourceDetailNewest() {
        val repo = SourceRepository(db, clock)
        val id = runBlocking {
            val id = repo.addFeed("https://www.theguardian.com/world/rss", "The Guardian: World")
            db.articles().insertNew((1..40).map { i ->
                ArticleEntity(sourceId = id, guid = "$i", url = "https://www.theguardian.com/$i", title = "Morning briefing, part $i", discoveredAt = clock.instant().minusSeconds(600L * i))
            })
            db.sources().recordSuccess(id, clock.instant(), null, "https://www.theguardian.com", "")
            id
        }
        val vm = viewModels.add(SourceDetailViewModel(repo, id, flowOf(1)))
        shoot("06f-source-detail-newest", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun sourceDetailSelecting() {
        val vm = sourceWithArticles()
        shoot(
            "06c-source-detail-selecting",
            ready = { vm.detail.value?.articles?.isNotEmpty() == true },
            act = {
                scrollToArticles()
                compose.onNode(hasText("Talks resume after a week of storms") and hasClickAction()).performTouchInput { longClick() }
                compose.onNode(hasText("The town that voted to keep its library open") and hasClickAction()).performClick()
                compose.onNode(hasText("A short history of the night train") and hasClickAction()).performClick()
            },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    /** News and Science folded: a heading and its count, closed by a rule. */
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun sourcesWithAServerFolded() {
        val vm = serverSources()
        runBlocking { store.update { it.copy(foldedCategories = setOf("News", "Science")) } }
        shoot("08n-sources-server-folded", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    @Config(fontScale = 2f)
    fun sourceDetailAtTwiceTheFontSize() {
        val vm = sourceWithArticles()
        shoot("06d-source-detail-200", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, act = ::scrollToArticles) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun sourceDetailSelectingAtTwiceTheFontSize() {
        val vm = sourceWithArticles()
        shoot(
            "06e-source-detail-selecting-200",
            ready = { vm.detail.value?.articles?.isNotEmpty() == true },
            act = {
                compose.onNodeWithText("Select").performScrollTo().performClick()
                val first = hasText("Talks resume after a week of storms") and hasClickAction()
                compose.onNode(hasScrollAction()).performScrollToNode(first)
                compose.onNode(first).performClick()
                val second = hasText("The town that voted to keep its library open") and hasClickAction()
                compose.onNode(hasScrollAction()).performScrollToNode(second)
                compose.onNode(second).performClick()
            },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    /**
     * A tt-rss account listed by tt-rss: feeds with settings of their own, one also added on the
     * phone, two left out, and articles from one of them.
     */
    private fun ttrssAccount(repo: SourceRepository): Long = runBlocking {
        repo.addFeed("https://www.theguardian.com/world/rss", "The Guardian: World")
        repo.addFeed("https://aeon.co/feed.rss", "Aeon")
        val account = repo.addTtrss("https://rss.example.com/tt-rss/api/")
        db.sources().recordSuccess(account, clock.instant(), null, null, SourceRepository.TTRSS_TITLE)
        fun feed(key: String, title: String, url: String, category: String) =
            PublicationEntity(account, key, title = title, feedUrl = url, category = category, listed = true)
        listOf(
            feed("1", "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Tech"),
            feed("2", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "News").copy(maxArticles = 1),
            feed("3", "Aeon", "https://aeon.co/feed.rss", "Essays"),
            feed("4", "Longreads", "https://longreads.com/feed/", "Essays").copy(chosenMode = ContentMode.PAGE),
            feed("5", "Quanta Magazine", "https://www.quantamagazine.org/feed/", "Science"),
            feed("6", "The Marginalian", "https://www.themarginalian.org/feed/", "Essays"),
            feed("7", "Hacker News", "https://news.ycombinator.com/rss", "Tech").copy(leftOut = true),
            feed("8", "Slashdot", "https://rss.slashdot.org/Slashdot/slashdotMain", "Tech").copy(leftOut = true),
        ).forEach { db.sources().savePublication(it) }
        db.sources().setFeedsListed(account, clock.instant())
        val stories = listOf(
            "How a quiet lab rebuilt the atomic clock",
            "The mathematicians who count the uncountable",
            "Why some ice is older than it should be",
            "A new map of the brain's wiring",
        )
        db.articles().insertNew(
            stories.mapIndexed { i, t ->
                ArticleEntity(
                    sourceId = account, guid = "ttrss:5$i", url = "https://www.quantamagazine.org/$i", title = t,
                    originId = "5", originTitle = "Quanta Magazine", discoveredAt = clock.instant().minusSeconds(3_600L * (i + 1)),
                )
            },
        )
        account
    }

    /**
     * The server setup: a curated list on the phone, and a tt-rss account with 21 well-known
     * public feeds in four categories and Uncategorized, a few with settings of their own and two
     * left out.
     */
    private fun serverSources(category: String? = null): SourcesViewModel {
        val repo = SourceRepository(db, clock)
        runBlocking {
            repo.addList(com.app.newspaperss.core.lists.CuratedLists.all.first())
            val account = repo.addTtrss("https://rss.example.com/tt-rss/api/")
            fun feed(key: Int, title: String, url: String, category: String?) =
                PublicationEntity(account, key.toString(), title = title, feedUrl = url, category = category)
            val feeds = listOf(
                feed(1, "BBC News", "https://feeds.bbci.co.uk/news/rss.xml", "News").copy(maxArticles = 2),
                feed(2, "The Guardian: World", "https://www.theguardian.com/world/rss", "News"),
                feed(3, "NPR News", "https://feeds.npr.org/1001/rss.xml", "News"),
                feed(4, "Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml", "News"),
                feed(5, "ProPublica", "https://www.propublica.org/feeds/propublica/main", "News").copy(chosenMode = ContentMode.PAGE),
                feed(6, "Quanta Magazine", "https://www.quantamagazine.org/feed/", "Science"),
                feed(7, "New Scientist", "https://www.newscientist.com/feed/home/", "Science"),
                feed(8, "Nature News", "https://www.nature.com/nature.rss", "Science"),
                feed(9, "NASA Breaking News", "https://www.nasa.gov/news-release/feed/", "Science"),
                feed(10, "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index", "Tech"),
                feed(11, "The Verge", "https://www.theverge.com/rss/index.xml", "Tech"),
                feed(12, "Wired", "https://www.wired.com/feed/rss", "Tech").copy(skipPaidPosts = true),
                feed(13, "Hacker News", "https://news.ycombinator.com/rss", "Tech").copy(leftOut = true),
                feed(14, "Slashdot", "https://rss.slashdot.org/Slashdot/slashdotMain", "Tech").copy(leftOut = true),
                feed(15, "Aeon", "https://aeon.co/feed.rss", "Essays"),
                feed(16, "The Marginalian", "https://www.themarginalian.org/feed/", "Essays"),
                feed(17, "Longreads", "https://longreads.com/feed/", "Essays"),
                feed(18, "Psyche", "https://psyche.co/feed", "Essays"),
                feed(19, "xkcd", "https://xkcd.com/rss.xml", null),
                feed(20, "Kottke.org", "https://feeds.kottke.org/main", null),
                feed(21, "Atlas Obscura", "https://www.atlasobscura.com/feeds/latest", null),
            )
            feeds.forEach { f ->
                // With a category chosen, the rest are outside it.
                val inside = category == null || f.category == category
                db.sources().savePublication(f.copy(listed = inside, outsideCategory = !inside))
            }
            if (category != null) db.sources().setTtrssCategory(account, 99, category)
            db.sources().setFeedsListed(account, clock.instant())
            db.sources().recordSuccess(account, clock.instant(), null, null, SourceRepository.TTRSS_TITLE)
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
        }
        return SourcesViewModel(repo, FeedFinder(FakeHttp()), settings = store) {}
    }

    @Test
    fun sourcesWithAServer() {
        val vm = serverSources()
        shoot("08a-sources-server", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    /** The whole list, to its end: Uncategorized and Left out last. */
    @Test
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    fun sourcesWithAServerWhole() {
        val vm = serverSources()
        shoot("08b-sources-server-whole", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    @Config(fontScale = 2f)
    fun sourcesWithAServerAtTwiceTheFontSize() {
        val vm = serverSources()
        shoot("08f-sources-server-large-text", ready = { vm.screen.value?.server?.categories?.isNotEmpty() == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun sourcesWithAServerAndOneCategory() {
        val vm = serverSources(category = "Essays")
        shoot("08j-sources-server-one-category", ready = { vm.screen.value?.server?.outside?.isNotEmpty() == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    fun notInYourPaper() {
        serverSources(category = "Essays")
        val account = runBlocking { db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id }
        val vm = viewModels.add(SourceDetailViewModel(SourceRepository(db, clock), account, flowOf(1)))
        shoot("08k-not-in-your-paper", ready = { vm.outside.value.isNotEmpty() && vm.detail.value != null }) { NotInPaperScreen(vm, onBack = {}, onOpenAccount = {}) }
    }

    @Test
    fun sourcesWithAServerDown() {
        val vm = serverSources()
        runBlocking {
            val account = db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id
            db.sources().recordFailure(account, clock.instant().minusSeconds(3_600), "Couldn't reach tt-rss.")
        }
        shoot("08l-sources-server-down", ready = { vm.screen.value?.server?.account?.lastError != null && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    fun sourcesWithAServerPaused() {
        val vm = serverSources()
        runBlocking { db.sources().setPaused(db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id, true) }
        shoot("08m-sources-server-paused", ready = { vm.screen.value?.server?.account?.paused == true && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    private fun feedPage(key: String): SourceDetailViewModel {
        val repo = SourceRepository(db, clock)
        val account = ttrssAccount(repo)
        return viewModels.add(SourceDetailViewModel(repo, account, flowOf(1), key = key))
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun ttrssFeedPage() {
        val vm = feedPage("5")
        shoot("08c-ttrss-feed-page", ready = { vm.detail.value?.articles?.isNotEmpty() == true }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun ttrssFeedPageTtrssCantFetch() {
        val vm = feedPage("6")
        runBlocking { db.sources().setServerError(db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id, "6", "HTTP Code: 404") }
        shoot("08p-ttrss-feed-cant-fetch", ready = { vm.detail.value?.text?.serverError != null }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun sourcesWithAFeedTtrssCantFetch() {
        val vm = serverSources()
        runBlocking {
            val account = db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id
            db.sources().setServerError(account, "2", "HTTP Code: 404")
            db.sources().setServerError(account, "15", "Couldn't download feed: connection timed out")
        }
        shoot("08q-sources-server-feed-cant-fetch", ready = { vm.screen.value?.server?.cantFetch?.size == 2 && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    @Config(qualifiers = "w411dp-h1100dp-xxhdpi")
    fun sourcesWithAQuietTtrssFeed() {
        val vm = serverSources()
        runBlocking {
            val account = db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id
            db.sources().posted(account, "2", clock.instant().minus(java.time.Duration.ofDays(30)))
        }
        shoot("08r-sources-server-feed-quiet", ready = { vm.screen.value?.server != null && vm.folded.value != null }) { SourcesScreen(vm) }
    }

    @Test
    fun ttrssFeedPageLeftOut() {
        val vm = feedPage("7")
        shoot("08d-ttrss-feed-left-out", ready = { vm.detail.value?.text?.leftOut == true }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun ttrssFeedLeaveOutDialog() {
        val vm = feedPage("5")
        shoot(
            "08e-leave-out-dialog", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, dialog = true,
            act = { compose.onNodeWithText("Leave out").performClick() },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun ttrssFeedArticleTextDialog() {
        val vm = feedPage("5")
        shoot(
            "08g-article-text-dialog", ready = { vm.detail.value?.articles?.isNotEmpty() == true }, dialog = true,
            act = { compose.onNodeWithText("Article text: Automatic").performClick() },
        ) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun leftOutList() {
        val repo = SourceRepository(db, clock)
        val account = ttrssAccount(repo)
        val vm = viewModels.add(SourceDetailViewModel(repo, account, flowOf(1)))
        shoot("08h-left-out-list", ready = { vm.feeds.value.any { !it.inPaper } }) { LeftOutScreen(vm, onBack = {}, onOpenFeed = {}) }
    }

    /** A failing feed's page from the top, where its own address is offered to check. */
    @Test
    fun sourceDetailFailing() {
        val repo = SourceRepository(db, clock)
        val id = runBlocking {
            val id = repo.addFeed("https://example.org/weekly/rss.xml", "Example Weekly")
            db.sources().recordSuccess(id, clock.instant().minus(Duration.ofDays(6)), null, "https://example.org", "")
            db.sources().recordFailure(id, clock.instant().minus(Duration.ofDays(5)), "Couldn't read this feed: the site sent a web page.")
            db.sources().recordFailure(id, clock.instant().minusSeconds(3_600), "Couldn't read this feed: the site sent a web page.")
            id
        }
        val vm = viewModels.add(SourceDetailViewModel(repo, id, flowOf(1)))
        shoot("06g-source-detail-failing", ready = { vm.detail.value?.source?.lastError != null }) { SourceDetailScreen(vm, onBack = {}) }
    }

    /** A phone feed's page from the top: its settings. */
    @Test
    fun phoneFeedSettings() {
        val vm = sourceWithArticles()
        shoot("08i-phone-feed-settings", ready = { vm.detail.value?.articles?.isNotEmpty() == true }) { SourceDetailScreen(vm, onBack = {}) }
    }

    @Test
    fun settings() {
        installVoice(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(device = Device.KINDLE, scheduleEnabled = true, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "name_abc123@kindle.com") } }
        val vm = SettingsViewModel(store) {}
        shoot("07-settings", ready = { vm.settings.value != null }) { SettingsScreen(vm, onOpen = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun settingsAtLargeText() {
        val vm = SettingsViewModel(store) {}
        shoot("07b-settings-200", ready = { vm.settings.value != null }) { SettingsScreen(vm, onOpen = {}) }
    }

    @Test
    fun settingsEdition() {
        val vm = SettingsViewModel(store) {}
        shoot("07c-settings-edition", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.EDITION, onBack = {}) }
    }

    @Test
    fun settingsSchedule() {
        runBlocking { store.update { it.copy(scheduleEnabled = true) } }
        val vm = SettingsViewModel(store) {}
        shoot("07d-settings-schedule", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.SCHEDULE, onBack = {}) }
    }

    /** Kokoro in use on a Pixel 8: the paper starts 70 minutes earlier, and the page says when. */
    @Test
    fun settingsScheduleWithThePodcast() {
        runBlocking { store.update { it.copy(scheduleEnabled = true, listenVoice = ListenVoice.PODCAST, podcastPace = 1.2f) } }
        val vm = SettingsViewModel(store) {}
        shoot("07t-settings-schedule-podcast", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.SCHEDULE, onBack = {}) }
    }

    @Test
    fun settingsDelivery() {
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "name_abc123@kindle.com") } }
        val vm = SettingsViewModel(store) {}
        shoot("07e-settings-delivery", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.DELIVERY, onBack = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun settingsDeliveryAtLargeText() {
        runBlocking { store.update { it.copy(device = Device.BOOX) } }
        val vm = SettingsViewModel(store) {}
        shoot("07g-settings-delivery-200", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.DELIVERY, onBack = {}) }
    }

    @Test
    fun settingsTextSize() {
        runBlocking { store.update { it.copy(previewTextSize = PreviewTextSize.LARGE) } }
        val vm = SettingsViewModel(store) {}
        shoot("07m-settings-text-size", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.TEXT_SIZE, onBack = {}) }
    }

    @Test
    @Config(fontScale = 2f)
    fun settingsTextSizeLargestAtLargeText() {
        runBlocking { store.update { it.copy(previewTextSize = PreviewTextSize.LARGEST) } }
        val vm = SettingsViewModel(store) {}
        shoot("07n-settings-text-size-largest-200", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.TEXT_SIZE, onBack = {}) }
    }

    @Test
    fun settingsListening() {
        installVoice(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(listenSpeed = 1.2f) } }
        val vm = SettingsViewModel(store) {}
        shoot("07o-settings-listening", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    /** At twice the font size: the four speeds still fit one row. */
    @Test
    @Config(fontScale = 2f)
    fun settingsListeningLargestFont() {
        installVoice(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(listenSpeed = 0.8f) } }
        val vm = SettingsViewModel(store) {}
        shoot("07o3-settings-listening-font-200", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    /** Recordings switched off: the kinds go away. */
    @Test
    fun settingsListeningRecordingsOff() {
        installVoice(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(listenSpeed = 1.2f, recordings = Recordings(on = false)) } }
        val vm = SettingsViewModel(store) {}
        shoot("07o2-settings-listening-recordings-off", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    /** Kokoro with its real download's size, which Settings shows. */
    private fun shownKokoro(installed: Boolean) = kokoroInstall(tmp.newFolder(), installed, size = 384_077_374L, sha1 = "0".repeat(40))

    /** Settings › Listening with the podcast offered, its work in [work] and Kokoro's files in place if [pace]. */
    private fun podcastSettings(
        work: Flow<WorkInfo?> = flowOf(null),
        pace: Float? = null,
        inUse: Boolean = false,
        install: KokoroInstall = shownKokoro(installed = pace != null),
        log: PodcastLog = PodcastLog(null),
        podcasts: PodcastStore = PodcastStore(tmp.newFolder()),
    ): SettingsViewModel {
        installVoice(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(podcastPace = pace, listenVoice = if (inUse) ListenVoice.PODCAST else ListenVoice.PHONE) } }
        val setup = PodcastSetup(
            install, store, work, start = {}, stop = {}, unsupported = { null }, engine = { error("not shot") },
            podcasts = Podcasts(podcasts, store, install) {},
            log = log,
        )
        return SettingsViewModel(store, podcast = setup) {}
    }

    @Test
    fun settingsListeningWithThePodcast() {
        val vm = podcastSettings()
        shoot("07p-settings-podcast-offered", ready = { vm.settings.value != null }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    @Test
    fun settingsPodcastDownloading() {
        val vm = podcastSettings(flowOf(runningWork(PodcastSetup.GOT to 154_000_000L, PodcastSetup.TOTAL to 384_077_374L)))
        shoot("07q-settings-podcast-downloading", ready = { vm.kokoro.value is KokoroState.Downloading }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    /** The check just finished on a Pixel 8 (0.8× cool): the verdict, before it's used. */
    @Test
    fun settingsPodcastChecked() {
        val checked = MutableStateFlow<WorkInfo?>(runningWork(PodcastSetup.PHASE to PodcastSetup.PHASE_CHECK))
        // Scheduled editions, so the verdict says how much earlier they'd start.
        runBlocking { store.update { it.copy(scheduleEnabled = true) } }
        val install = shownKokoro(installed = false)
        val vm = podcastSettings(checked, install = install)
        shoot("07r-settings-podcast-checked", ready = { vm.kokoro.value is KokoroState.Checking }, act = {
            install.finishDownload()
            runBlocking { store.update { it.copy(podcastPace = 1.2f) } }
            checked.value = null
            idleUntil { compose.waitForIdle(); vm.kokoro.value is KokoroState.Ready }
        }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    /** Two editions' podcasts made, 38 MB between them. */
    private fun podcastInUse(): SettingsViewModel {
        val made = PodcastStore(tmp.newFolder())
        listOf(7L, 8L).forEach { id ->
            made.want(id, PodcastVoice.HEART)
            made.keep(id, 0, 0, made.scratch(id, 0, 0).apply { writeBytes(ByteArray(19_000_000)) }, listOf(0.0))
        }
        return podcastSettings(pace = 1.2f, inUse = true, podcasts = made)
    }

    @Test
    fun settingsPodcastInUse() {
        val vm = podcastInUse()
        shoot("07s-settings-podcast-in-use", ready = { vm.kokoro.value is KokoroState.Ready && vm.podcastBytes.value != null }) { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
    }

    /** Settings › Listening › Voices and audio: the natural voice's own page. */
    @Test
    fun settingsPodcastVoicesAndAudio() {
        val vm = podcastInUse()
        shoot("07s2-settings-voices-and-audio", ready = { vm.kokoro.value is KokoroState.Ready && vm.podcastBytes.value != null }) { NaturalVoiceScreen(vm, onBack = {}) }
    }

    @Test
    fun settingsPodcastLog() {
        val log = PodcastLog(tmp.newFolder(), now = { 1_791_000_000_000L })
        listOf(
            "Edition 12's audio asked for, in Heart; made once the phone charges",
            "Run started (try 1): asking for the foreground, Android 36, battery unrestricted, plugged in",
            "Kokoro loaded (Heart) in 4s",
            "Edition 12 page 0: from line 0 of 84",
            "In the foreground",
            "Kept page 0 lines 0–21: 124s of audio in 181s",
            "Page 0 line 30 took 41s (612 characters)",
            "Stopped: unplugged",
            "Stopped on page 0 before line 31; 37s of unkept audio dropped",
        ).forEach(log::add)
        val vm = podcastSettings(pace = 1.2f, inUse = true, log = log)
        shoot(
            "07u-settings-podcast-log",
            ready = { vm.kokoro.value is KokoroState.Ready },
            act = {
                compose.onNodeWithText("Audio log").performScrollTo().performClick()
                idleUntil { compose.onAllNodes(hasText("Stopped: unplugged", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            },
            dialog = true,
        ) { NaturalVoiceScreen(vm, onBack = {}) }
    }

    /** Settings › Where your feeds live, signed in to a tt-rss account. */
    private fun feedsFromWithServer(): Pair<SettingsViewModel, FeedsFromViewModel> {
        val server = sampleServer()
        runBlocking {
            ttrss.connect("rss.example.com/tt-rss", server.user, server.password)
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
            db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).forEach { db.sources().recordSuccess(it.id, clock.instant(), null, null, it.title) }
        }
        return SettingsViewModel(store, ttrss.observeStatus()) {} to FeedsFromViewModel(store, ttrss)
    }

    @Test
    fun settingsWithAServer() {
        val (vm, _) = feedsFromWithServer()
        shoot("07h-settings-with-server", ready = { vm.settings.value != null && vm.ttrssStatus.value.source != null }) { SettingsScreen(vm, onOpen = {}) }
    }

    @Test
    fun settingsFeedsFromServer() {
        val (vm, feeds) = feedsFromWithServer()
        shoot("07i-settings-feeds-from-server", ready = { feeds.state.value?.ttrss?.signedIn == true }) {
            SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    @Test
    fun settingsFeedsFromPhone() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feeds = FeedsFromViewModel(store, ttrss)
        shoot("07j-settings-feeds-from-phone", ready = { feeds.state.value != null }) {
            SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    @Test
    fun settingsLeavingTheServer() {
        val (vm, feeds) = feedsFromWithServer()
        shoot(
            "07k-settings-leave-server", ready = { feeds.state.value?.ttrss?.signedIn == true }, dialog = true,
            act = { compose.onNodeWithText("I pick my own sites").performClick() },
        ) { SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds) }
    }

    @Test
    fun settingsServerSignIn() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.PHONE) } }
        val vm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feeds = FeedsFromViewModel(store, ttrss)
        shoot("07l-settings-server-sign-in", ready = { feeds.state.value != null }, act = { feeds.openSignIn() }) {
            SettingsPageScreen(vm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    @Test
    fun sourcesSignedOutOfTheServer() {
        runBlocking { store.update { it.copy(feedsFrom = FeedsFrom.SERVER) } }
        val vm = SourcesViewModel(SourceRepository(db, clock), FeedFinder(FakeHttp()), ttrss, settings = store) {}
        shoot("06f-sources-sign-in", ready = { vm.screen.value?.needsSignIn == true }) { SourcesScreen(vm) }
    }

    /**
     * Adding a site in the server setup, at [state]: signed in to a tt-rss with four categories,
     * Science used last. Quanta Magazine's page advertises its feed.
     */
    private fun shootServerAdd(name: String, state: (SourcesViewModel) -> Boolean, server: FakeTtrss.() -> Unit = {}, act: (SourcesViewModel) -> Unit) {
        val fake = FakeTtrss(ttrssHttp).apply {
            listOf("Essays", "News", "Science", "Tech").forEachIndexed { i, name -> categories[i + 1] = name }
            titles["https://www.quantamagazine.org/feed/"] = "Quanta Magazine"
            server()
        }
        ttrssHttp.page("https://quantamagazine.org", """<html><head><link rel="alternate" type="application/rss+xml" title="Quanta Magazine" href="https://www.quantamagazine.org/feed/"></head></html>""")
        val repo = SourceRepository(db, clock)
        runBlocking {
            assertNull(ttrss.connect("rss.example.com/tt-rss", fake.user, fake.password))
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER, lastCategoryId = 3) }
        }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val vm = SourcesViewModel(repo, FeedFinder(ttrssHttp), ttrss, saveToReadingList = { true }, settings = store, subscriptions = TtrssSubscriptions(ttrss, scope)) {}
        try {
            shoot(
                name, ready = { vm.screen.value?.server != null }, dialog = true,
                act = {
                    act(vm)
                    idleUntil { compose.waitForIdle(); state(vm) }
                },
            ) { SourcesScreen(vm) }
        } finally {
            scope.cancel()
        }
    }

    private fun SourcesViewModel.typeAndFind(address: String) {
        openAdd()
        editInput(address)
        find()
    }

    private val categoriesLoaded: (SourcesViewModel) -> Boolean = { (it.add.value as? AddState.Subscribing)?.categories != null }

    @Test
    fun addingASiteWithAServer() =
        shootServerAdd("09a-add-server-subscribe", categoriesLoaded) { it.typeAndFind("quantamagazine.org") }

    @Test
    fun addingASiteWithAServerPickingACategory() =
        shootServerAdd("09b-add-server-category", categoriesLoaded) {
            it.typeAndFind("quantamagazine.org")
            idleUntil { compose.waitForIdle(); categoriesLoaded(it) }
            compose.onNodeWithContentDescription("Category, Science").performClick()
        }

    @Test
    fun addingASiteWithAServerAsking() =
        shootServerAdd("09c-add-server-asking", { it.add.value is AddState.Asking }, server = { subscribeGate = CompletableDeferred() }) {
            it.typeAndFind("quantamagazine.org")
            idleUntil { compose.waitForIdle(); categoriesLoaded(it) }
            it.subscribeInTtrss()
        }

    @Test
    fun addingASiteAlreadyInTtrss() =
        shootServerAdd("09d-add-server-already", { it.add.value is AddState.AlreadyIn }, server = { feeds[6] = FakeTtrss.Feed("Quanta Magazine", "https://www.quantamagazine.org/feed/", categoryId = 3) }) {
            it.typeAndFind("quantamagazine.org")
            idleUntil { compose.waitForIdle(); categoriesLoaded(it) }
            it.subscribeInTtrss()
        }

    @Test
    fun addingASiteTtrssCantFetch() =
        shootServerAdd("09e-add-server-cant-fetch", { it.add.value is AddState.Refused }, server = { subscribeCode = 5 }) {
            ttrssHttp.page(
                "https://www.quantamagazine.org/a-new-proof-20261001/",
                """<html><head><link rel="alternate" type="application/rss+xml" title="Quanta Magazine" href="https://www.quantamagazine.org/feed/"></head></html>""",
            )
            it.typeAndFind("https://www.quantamagazine.org/a-new-proof-20261001/")
            idleUntil { compose.waitForIdle(); categoriesLoaded(it) }
            it.subscribeInTtrss()
        }

    @Test
    fun addingASiteWithNoFeed() =
        shootServerAdd("09f-add-server-no-feed", { it.add.value is AddState.NoFeed }) {
            ttrssHttp.page("https://quietpaper.example/2026/10/on-slowness", "<html><body><p>An essay.</p></body></html>")
            it.typeAndFind("quietpaper.example/2026/10/on-slowness")
        }

    /** Sources with the feed just added, its first fetch to come, and Undo. */
    @Test
    fun aSiteAddedToTtrss() {
        val fake = FakeTtrss(ttrssHttp).apply {
            listOf("Essays", "News", "Science", "Tech").forEachIndexed { i, name -> categories[i + 1] = name }
            feeds[1] = FakeTtrss.Feed("Aeon", "https://aeon.co/feed.rss", categoryId = 1)
            feeds[2] = FakeTtrss.Feed("New Scientist", "https://www.newscientist.com/feed/home/", categoryId = 3)
            titles["https://www.quantamagazine.org/feed/"] = "Quanta Magazine"
        }
        ttrssHttp.page("https://quantamagazine.org", """<html><head><link rel="alternate" type="application/rss+xml" title="Quanta Magazine" href="https://www.quantamagazine.org/feed/"></head></html>""")
        runBlocking {
            assertNull(ttrss.connect("rss.example.com/tt-rss", fake.user, fake.password))
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER, lastCategoryId = 3) }
            val account = db.sources().ofKind(com.app.newspaperss.data.SourceKind.TTRSS).single().id
            db.sources().recordSuccess(account, clock.instant(), null, null, SourceRepository.TTRSS_TITLE)
        }
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val vm = SourcesViewModel(SourceRepository(db, clock), FeedFinder(ttrssHttp), ttrss, settings = store, subscriptions = TtrssSubscriptions(ttrss, scope)) {}
        try {
            shoot(
                "09g-add-server-added", ready = { vm.screen.value?.server != null },
                act = {
                    vm.typeAndFind("quantamagazine.org")
                    idleUntil { compose.waitForIdle(); categoriesLoaded(vm) }
                    vm.subscribeInTtrss()
                    idleUntil { compose.waitForIdle(); compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
                },
            ) { SourcesScreen(vm) }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Moving phone feeds into tt-rss, at [ready]: signed in to a tt-rss with four categories that
     * has Aeon already, and four phone feeds besides a curated list.
     */
    private fun shootMove(
        name: String,
        ready: (SourcesViewModel) -> Boolean,
        dialog: Boolean = false,
        server: FakeTtrss.() -> Unit = {},
        act: (SourcesViewModel, FeedMoves, CoroutineScope) -> Unit = { _, _, _ -> },
    ) {
        val fake = FakeTtrss(ttrssHttp).apply {
            listOf("Essays", "News", "Science", "Tech").forEachIndexed { i, n -> categories[i + 1] = n }
            feeds[1] = FakeTtrss.Feed("Aeon", "https://aeon.co/feed.rss", categoryId = 1)
            server()
        }
        val repo = SourceRepository(db, clock)
        runBlocking {
            assertNull(ttrss.connect("rss.example.com/tt-rss", fake.user, fake.password))
            store.update { it.copy(feedsFrom = FeedsFrom.SERVER) }
            ttrss.listFor(ttrss.login()!!)
            repo.addList(com.app.newspaperss.core.lists.CuratedLists.all.first())
            listOf(
                "https://aeon.co/feed.rss" to "Aeon",
                "https://www.theguardian.com/world/rss" to "The Guardian: World",
                "https://www.quantamagazine.org/feed/" to "Quanta Magazine",
                "https://www.themarginalian.org/feed/" to "The Marginalian",
            ).forEach { (url, title) -> repo.addFeed(url, title) }
            db.sources().all().forEach { db.sources().recordSuccess(it.id, clock.instant(), null, null, it.title) }
        }
        val moves = FeedMoves(stores.preferences("moves"), db, ttrss, clock) {}
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val vm = SourcesViewModel(repo, FeedFinder(ttrssHttp), ttrss, settings = store, moves = moves) {}
        try {
            shoot(
                name, ready = { vm.screen.value?.phoneFeeds != null }, dialog = dialog,
                act = {
                    act(vm, moves, scope)
                    idleUntil { compose.waitForIdle(); ready(vm) }
                },
            ) { SourcesScreen(vm) }
        } finally {
            fake.subscribeGate?.complete(Unit)
            scope.cancel()
        }
    }

    private fun phoneFeedIds() = runBlocking { db.sources().all().filter { it.kind == com.app.newspaperss.data.SourceKind.FEED }.map { it.id } }

    @Test
    fun moveBanner() = shootMove("10a-move-banner", { it.screen.value?.phoneFeeds is PhoneFeeds.Offer })

    @Test
    fun moveSheet() = shootMove("10b-move-sheet", { it.mover?.sheet?.value?.categories != null }, dialog = true) { vm, _, _ -> vm.mover!!.open() }

    @Test
    fun moveProgress() = shootMove(
        "10c-move-progress",
        { (it.screen.value?.phoneFeeds as? PhoneFeeds.Moving)?.step == 3 },
        server = { afterSubscribe = { url -> if (url.contains("theguardian")) subscribeGate = CompletableDeferred() } },
    ) { _, moves, scope ->
        runBlocking { moves.start(phoneFeedIds(), com.app.newspaperss.core.ttrss.TtrssCategory(1, "Essays")) }
        scope.launch { moves.run() }
    }

    @Test
    fun movePartlyDone() = shootMove(
        "10d-move-partial",
        { it.screen.value?.phoneFeeds is PhoneFeeds.Partial },
        server = {
            refuse["https://www.quantamagazine.org/feed/"] = 5
            refuse["https://www.themarginalian.org/feed/"] = 6
        },
    ) { _, moves, _ ->
        runBlocking {
            moves.start(phoneFeedIds(), com.app.newspaperss.core.ttrss.TtrssCategory(1, "Essays"))
            moves.run()
        }
    }

    /** Settings straight after signing in from the phone setup, with phone feeds to move. */
    @Test
    fun moveOfferAfterSignIn() {
        val fake = sampleServer()
        val repo = SourceRepository(db, clock)
        runBlocking {
            store.update { it.copy(feedsFrom = FeedsFrom.PHONE) }
            repo.addFeed("https://aeon.co/feed.rss", "Aeon")
            repo.addFeed("https://www.quantamagazine.org/feed/", "Quanta Magazine")
            repo.addFeed("https://feed3.example/rss", "Feed 3")
        }
        val moves = FeedMoves(stores.preferences("moves"), db, ttrss, clock) {}
        val settingsVm = SettingsViewModel(store, ttrss.observeStatus()) {}
        val feeds = FeedsFromViewModel(store, ttrss, moves, repo).apply {
            openSignIn()
            editSignIn(form.value!!.copy(address = "rss.example.com/tt-rss", user = fake.user, password = fake.password))
            signIn()
        }
        shoot(
            "10e-move-offer", ready = { feeds.offer.value != null && feeds.form.value == null },
            act = { idleUntil { compose.waitForIdle(); compose.onAllNodes(hasText("Move 3")).fetchSemanticsNodes().isNotEmpty() } },
        ) {
            SettingsPageScreen(settingsVm, SettingsPage.FEEDS, onBack = {}, feedsFrom = feeds)
        }
    }

    private companion object {
        /** An article with bold, a link, a quote, a numbered list, a table and code: for the playing screen and the preview. */
        val FORMATTED = EditionArticle(
            title = "How a Retired Teacher Counted the Knots", sourceTitle = "Quanta Magazine", url = "https://example.com/count",
            bodyHtml = "<p>The count took <b>a week</b> of computer time, and the <a href=\"https://example.com/paper\">paper</a> ran to <em>forty pages</em>.</p>" +
                "<blockquote><p>We thought it would take a century.</p></blockquote>" +
                "<p>The method came in three steps:</p><ol><li>Draw every knot with few crossings.</li><li>Throw out the ones that are the same.</li><li>Check what's left by hand.</li></ol>" +
                "<table><tr><th>Crossings</th><th>Knots</th></tr><tr><td>3</td><td>1</td></tr><tr><td>4</td><td>1</td></tr><tr><td>5</td><td>2</td></tr></table>" +
                "<pre>for knot in knots:\n    if new(knot):\n        keep(knot)</pre>",
            minutes = 3.0, author = "Erica Klarreich", published = LocalDate.of(2026, 9, 30),
        )
    }
}
