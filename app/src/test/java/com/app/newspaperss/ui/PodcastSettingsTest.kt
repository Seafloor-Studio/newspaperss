package com.app.newspaperss.ui

import android.net.ConnectivityManager
import android.net.NetworkInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.app.newspaperss.listen.PodcastEngine
import com.app.newspaperss.listen.PodcastSetup
import org.junit.Assert.assertTrue
import com.app.newspaperss.listen.PodcastLog
import com.app.newspaperss.listen.PodcastStore
import com.app.newspaperss.listen.Podcasts
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.stopStores
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.finishDownload
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.testutil.installVoice
import com.app.newspaperss.testutil.kokoroInstall
import com.app.newspaperss.testutil.runningWork
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.NaturalVoiceScreen
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.UUID

/** Settings › Listening with the podcast offered: getting Kokoro, its check, using and removing it. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PodcastSettingsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    // Cancelled before TemporaryFolder deletes the file, as in SettingsScreenTest.
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy { SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") }) }
    private val install by lazy { kokoroInstall(tmp.newFolder("kokoro"), installed = false) }
    private val work = MutableStateFlow<WorkInfo?>(null)
    private val started = mutableListOf<Boolean>()
    private var stopped = 0

    @After fun stopStore() = stopStores(storeScope)

    private lateinit var setup: PodcastSetup
    // Where AppContainer puts it: a log outside filesDir has no content:// uri to share.
    private val log by lazy { PodcastLog(java.io.File(context.filesDir, "podcast-logs")) }

    private fun show(supported: Boolean = true): SettingsViewModel {
        installVoice(context)
        setup = PodcastSetup(
            install, store, work, start = { started += it }, stop = { stopped++ }, unsupported = { if (supported) null else "Kokoro needs a 64-bit phone, and this one is 32-bit." },
            engine = { error("not in these tests") },
            podcasts = Podcasts(PodcastStore(java.io.File(context.filesDir, "podcasts")), store, install) {},
            log = log,
        )
        val vm = SettingsViewModel(store, podcast = setup) {}
        // Listening and the natural voice's own page, gone between as the app's navigation does.
        compose.setContent {
            var voicesAndAudio by remember { mutableStateOf(false) }
            if (voicesAndAudio) {
                NaturalVoiceScreen(vm, onBack = { voicesAndAudio = false })
            } else {
                SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}, onOpenNaturalVoice = { voicesAndAudio = true })
            }
        }
        // Until Kokoro's state is read (off the main thread), picking the podcast can't tell
        // whether to ask about mobile data, and does nothing.
        settle { vm.settings.value != null && vm.kokoro.value != null }
        return vm
    }

    /**
     * [idleUntil] for a view model's state, idling Compose too. The view model's flows only run
     * once the screen's effects subscribe to them, and those effects run on the Compose test
     * rule's own dispatcher, which draining the main looper never advances. [waitFor] gets this
     * from `fetchSemanticsNodes`.
     */
    private fun settle(condition: () -> Boolean) = idleUntil { compose.waitForIdle(); condition() }

    private fun waitFor(text: String) = idleUntil { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun on(type: Int) = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        .setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, type, 0, true, NetworkInfo.State.CONNECTED))

    private fun ready(pace: Float) {
        install.finishDownload()
        runBlocking { store.update { it.copy(podcastPace = pace) } }
    }

    private fun pickPodcast() = compose.onNodeWithText("A natural voice").performScrollTo().performClick()

    private fun openVoicesAndAudio() = compose.onNodeWithText("Voices and audio").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()

    @Test
    fun offWifiItAsksBeforeUsingMobileData() {
        on(ConnectivityManager.TYPE_MOBILE)
        show()
        pickPodcast()
        waitFor("You're not on Wi-Fi")
        compose.onNodeWithText("Use mobile data").performScrollTo().performClick()
        assertEquals(listOf(true), started)
    }

    @Test
    fun offWifiItCanWaitForWifi() {
        on(ConnectivityManager.TYPE_MOBILE)
        show()
        pickPodcast()
        compose.onNodeWithText("Wait for Wi-Fi").performScrollTo().performClick()
        assertEquals(listOf(false), started)
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        waitFor("download by itself on the next Wi-Fi")
    }

    @Test
    fun onWifiItDownloadsStraightAwayWithProgressAndCancel() {
        on(ConnectivityManager.TYPE_WIFI)
        show()
        pickPodcast()
        assertEquals(listOf(false), started)
        work.value = runningWork(PodcastSetup.GOT to 154_000_000L, PodcastSetup.TOTAL to 384_077_374L)
        waitFor("154 of 384 MB")
        compose.onNodeWithText("A natural voice").assertIsSelected()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun afterTheCheckItSaysWhatYourPaperCostsAndCanBeUsed() {
        on(ConnectivityManager.TYPE_WIFI)
        val vm = show()
        work.value = runningWork(PodcastSetup.PHASE to PodcastSetup.PHASE_CHECK)
        waitFor("Seeing how fast this phone is")
        // A Pixel 8: 0.8× cool, so 1.2 warm.
        ready(1.2f)
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.SUCCEEDED, emptySet())
        waitFor("This phone can do it")
        waitFor("Your 30-minute paper takes about 55 minutes to make here")
        compose.onNodeWithText("Use it").performScrollTo().performClick()
        idleUntil { vm.settings.value?.listenVoice == ListenVoice.PODCAST }
        // In use: its voices, each with a sample, on a page of their own.
        waitFor("Voices and audio")
        compose.onAllNodes(hasText("Emma")).fetchSemanticsNodes().let { assertEquals("not on Listening itself", 0, it.size) }
        openVoicesAndAudio()
        compose.onNodeWithText("Emma").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { vm.settings.value?.podcastVoice == PodcastVoice.EMMA }
        waitFor("Listen plays an article in the natural voice once its audio is made")
    }

    @Test
    fun aTooSlowPhoneIsToldSoAndOfferedOnlyTheSpaceBack() {
        ready(4.0f)
        val vm = show()
        pickPodcast()
        waitFor("Too slow on this phone")
        waitFor("25 minutes would take about 2½ hours")
        compose.onAllNodes(hasText("Use it")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithText("Remove Kokoro (0 MB)").performScrollTo().performClick()
        // Waited for on the screen's own state: blocking the main thread for the store would hold
        // up the removal, which finishes there.
        idleUntil { vm.settings.value?.podcastPace == null }
        assertEquals(1, stopped)
        assertFalse(install.dir.exists())
        assertEquals(ListenVoice.PHONE, vm.settings.value?.listenVoice)
    }

    @Test
    fun aFailureSaysWhyAndCanBeTriedAgain() {
        on(ConnectivityManager.TYPE_WIFI)
        show()
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet(), outputData = workDataOf(PodcastSetup.ERROR to "Kokoro couldn't start on this phone."))
        waitFor("Kokoro couldn't start on this phone.")
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        assertEquals(listOf(false), started)
    }

    @Test
    fun comingBackToADownloadedKokoroStillShowsWhatItCosts() {
        // Checked while the reader was elsewhere: the verdict waits for them.
        ready(2.5f)
        show()
        waitFor("This phone is slow at it, but it can")
        waitFor("Your 30-minute paper takes about 2 hours to make here")
        compose.onNodeWithText("Your phone's voice").assertIsSelected()
    }

    @Test
    fun deletingAllPodcastsAsksFirstAndKeepsKokoro() {
        ready(1.2f)
        runBlocking { store.update { it.copy(listenVoice = ListenVoice.PODCAST) } }
        val podcasts = PodcastStore(java.io.File(context.filesDir, "podcasts"))
        podcasts.want(7, PodcastVoice.HEART)
        podcasts.keep(7, 0, 0, podcasts.scratch(7, 0, 0).apply { writeBytes(ByteArray(5_000_000)) }, listOf(0.0))
        show()
        waitFor("Heart · 5 MB of audio")
        openVoicesAndAudio()
        waitFor("Audio takes 5 MB")
        compose.onNodeWithText("Delete all audio").performScrollTo().performClick()
        waitFor("It frees 5 MB")
        compose.onNodeWithText("Delete").performClick()
        idleUntil { podcasts.voice(7) == null }
        assertTrue(install.complete)
        assertEquals(ListenVoice.PODCAST, runBlocking { store.current() }.listenVoice)
        // Nothing left to delete: the button goes.
        idleUntil { compose.onAllNodes(hasText("Delete all audio")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theLogShowsWhatTheMakingDidNewestFirst() {
        ready(1.2f)
        runBlocking { store.update { it.copy(listenVoice = ListenVoice.PODCAST) } }
        log.add("Run started (try 1)")
        log.add("Stopped: unplugged")
        show()
        openVoicesAndAudio()
        compose.onNodeWithText("Audio log").performScrollTo().performClick()
        waitFor("Stopped: unplugged")
        val shown = compose.onNode(hasText("Run started", substring = true)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assertTrue(shown, shown.indexOf("Stopped: unplugged") < shown.indexOf("Run started"))
    }

    /** Removed from its own page, the natural voice is gone, and so is the page: back to Listening. */
    @Test
    fun removingTheNaturalVoiceGoesBackToListening() {
        ready(1.2f)
        runBlocking { store.update { it.copy(listenVoice = ListenVoice.PODCAST) } }
        val vm = show()
        openVoicesAndAudio()
        compose.onNodeWithText("Remove Kokoro").performScrollTo().performClick()
        idleUntil { vm.settings.value?.podcastPace == null }
        waitFor("Speed")
        compose.onNodeWithText("Your phone's voice").assertIsSelected()
        assertFalse(install.dir.exists())
    }

    /** The screen shows a couple of nights; the share has to carry every day kept, so it sends the file. */
    @Test
    fun sharingTheLogSendsTheFileNotWhatIsOnTheScreen() {
        ready(1.2f)
        runBlocking { store.update { it.copy(listenVoice = ListenVoice.PODCAST) } }
        log.add("Run started (try 1)")
        show()
        openVoicesAndAudio()
        compose.onNodeWithText("Audio log").performScrollTo().performClick()
        waitFor("Run started (try 1)")

        compose.onNodeWithText("Share").performClick()
        idleUntil { compose.waitForIdle(); shadowOf(context).peekNextStartedActivity() != null }

        val chooser = shadowOf(context).peekNextStartedActivity()!!
        val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals(android.content.Intent.ACTION_SEND, send.action)
        val uri = send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertTrue(uri.toString(), uri.toString().endsWith("audio-log.txt"))
        assertNull("the file, not the text", send.getStringExtra(android.content.Intent.EXTRA_TEXT))
        assertTrue(send.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun choosingToReadLiveDuringTheDownloadStopsIt() {
        on(ConnectivityManager.TYPE_WIFI)
        show()
        work.value = runningWork(PodcastSetup.GOT to 1_000_000L, PodcastSetup.TOTAL to 384_077_374L)
        waitFor("1 of 384 MB")
        compose.onNodeWithText("Your phone's voice").performScrollTo().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun aRetryIsntWaitingForWifiButCanStillUseMobileData() {
        show()
        setup.failed()
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), runAttemptCount = 1, constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        waitFor("Trying again shortly")
        assertEquals(0, compose.onAllNodes(hasText("Waiting for Wi-Fi")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Use mobile data").performScrollTo().performClick()
        assertEquals(listOf(true), started)
    }

    @Test
    fun choosingToReadLivePutsAFailureAside() {
        show()
        work.value = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet(), outputData = workDataOf(PodcastSetup.ERROR to "Couldn't download Kokoro."))
        waitFor("Couldn't download Kokoro.")
        compose.onNodeWithText("Your phone's voice").performScrollTo().performClick()
        idleUntil { compose.onAllNodes(hasText("Couldn't download Kokoro.")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aThirtyTwoBitPhoneSeesWhyNot() {
        show(supported = false)
        waitFor("Kokoro needs a 64-bit phone")
        compose.onNodeWithText("A natural voice").assertIsNotEnabled()
    }

    @Test
    fun withoutTheSetupThereIsNoPodcast() {
        installVoice(context)
        val vm = SettingsViewModel(store) {}
        compose.setContent { SettingsPageScreen(vm, SettingsPage.LISTENING, onBack = {}) }
        settle { vm.settings.value != null }
        waitFor("Your phone's voice")
        assertEquals(0, compose.onAllNodes(hasText("A natural voice", substring = true)).fetchSemanticsNodes().size)
    }
}
