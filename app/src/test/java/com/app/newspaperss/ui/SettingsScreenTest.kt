package com.app.newspaperss.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextInput
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.app.newspaperss.settings.Device
import com.app.newspaperss.settings.KindleEmail
import com.app.newspaperss.settings.PreviewTextSize
import com.app.newspaperss.testutil.stopStores
import com.app.newspaperss.testutil.MAIL_APP
import com.app.newspaperss.testutil.installApp
import com.app.newspaperss.testutil.installVoice
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.edition.Ordering
import com.app.newspaperss.settings.DeliveryMethod
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.SettingsStore
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.settings.SettingsPage
import com.app.newspaperss.ui.settings.SettingsPageScreen
import com.app.newspaperss.ui.settings.SettingsScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.app.newspaperss.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.app.newspaperss.settings.ListenVoice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var store: SettingsStore
    // Cancelled before TemporaryFolder deletes the file: a write still running after that fails
    // its rename, and the error lands in whichever test is running then.
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rescheduled = mutableListOf<Settings>()
    private val heard = mutableListOf<Pair<String, Float>>()

    private lateinit var vm: SettingsViewModel

    /**
     * The line names the build from the package itself, so these tests don't have to be edited on
     * the way to every release: a literal version here is two failures at each bump.
     */
    private val versionLine: String
        get() = ApplicationProvider.getApplicationContext<android.content.Context>().let { context ->
            "NewspapeRSS " + context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        }

    @Before
    fun makeStore() {
        store = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") })
        vm = SettingsViewModel(store, hear = { text, speed -> heard += text to speed }) { rescheduled += it }
    }

    /** Settings as the app shows it: the summary, or [page] opened from it, with Back to the summary. */
    private fun show(page: SettingsPage? = null) {
        compose.setContent {
            var open by remember { mutableStateOf(page) }
            when (val p = open) {
                null -> SettingsScreen(vm, onOpen = { open = it })
                else -> SettingsPageScreen(vm, p, onBack = { open = null })
            }
        }
        // The title draws before the settings load from DataStore, so wait for the content.
        idleUntil { vm.settings.value != null }
        waitFor(if (page == null) "NewspapeRSS" else page.title)
    }

    @After fun stopStore() = stopStores(storeScope)

    private fun waitFor(text: String) = idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun turningOnTheScheduleShowsDaysAndReschedules() {
        show(SettingsPage.SCHEDULE)
        compose.onNodeWithText("Make an edition automatically").performScrollTo().performClick()
        idleUntil { rescheduled.isNotEmpty() }
        waitFor("Mon")
        compose.onNodeWithText("Make an edition automatically").assertHeightIsAtLeast(48.dp)
        assertEquals(true, rescheduled.last().scheduleEnabled)
    }

    /** With the natural voice, Schedule says the paper starts early so its audio is made in time, and how early. */
    @Test
    fun withANaturalVoiceTheScheduleSaysWhenTheAudioStarts() {
        runBlocking { store.update { it.copy(scheduleEnabled = true, listenVoice = ListenVoice.PODCAST, podcastPace = 1.2f) } }
        show(SettingsPage.SCHEDULE)
        waitFor(", for the audio")
        waitFor("minutes earlier, with room to spare.")

        // A phone too slow for the audio to fit in the 3 hours a paper can start early.
        runBlocking { store.update { it.copy(podcastPace = 20f) } }
        waitFor("more than the 3 hours a paper can start early")
        waitFor("The end of it will play in your phone's voice")
    }

    @Test
    fun theOrderRowsAreFullSizeTargets() {
        show(SettingsPage.EDITION)
        listOf("Take turns between sources", "Source by source, in list order", "Shuffle").forEach {
            compose.onNodeWithText(it).performScrollTo().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun screenReadersHearWhatTheStepperChangesAndTheNewNumber() {
        show(SettingsPage.EDITION)
        compose.onNodeWithContentDescription("More from each source").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.maxPerSource } == 2 }
        compose.onNode(hasText("2 articles from each source", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        compose.onNodeWithContentDescription("Fewer from each source").assertIsEnabled()
    }

    @Test
    fun theSizeSliderSaysMinutesAndOrderIsAHeading() {
        show(SettingsPage.EDITION)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "30 minutes")).assertExists()
        compose.onNode(hasText("Order") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
    }

    @Test
    fun orderingIsSaved() {
        show(SettingsPage.EDITION)
        compose.onNodeWithText("Shuffle").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().edition.ordering } == Ordering.SHUFFLE }
        compose.onNodeWithText("Shuffle").assertIsSelected()
    }

    @Test
    fun theArticleTextSizeKeepsAnEarlierChoiceAndSavesANewOne() {
        // Chosen with the preview's old Aa menu: the same stored value.
        runBlocking { store.update { it.copy(previewTextSize = PreviewTextSize.LARGER) } }
        show()
        waitFor("Article text size")
        compose.onNode(hasText("Larger") and hasClickAction()).assertExists()
        compose.onNodeWithText("Article text size").performClick()
        waitFor("on top of Android's own font size")
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        compose.onNodeWithText("Larger").assertIsSelected()

        compose.onNodeWithText("Largest").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { runBlocking { store.current().previewTextSize } == PreviewTextSize.LARGEST }
        compose.onNodeWithText("Largest").assertIsSelected()
        compose.onNodeWithText("Larger").assertIsNotSelected()

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Largest")
    }

    @Test
    fun listeningNamesThePhonesVoiceKeepsASpeedAndPlaysASample() {
        installVoice(ApplicationProvider.getApplicationContext())
        show()
        waitFor("Your phone's voice · 1×")
        compose.onNodeWithText("Listening").performScrollTo().performClick()
        waitFor("Speech Services by Google")
        compose.onNodeWithText("1× · normal").assertIsSelected()

        compose.onNodeWithText("1.5×").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { runBlocking { store.current().listenSpeed } == 1.5f }
        compose.onNodeWithText("1.5×").assertIsSelected()

        compose.onNodeWithText("Hear it").performClick()
        assertEquals(listOf(SettingsViewModel.SAMPLE to 1.5f), heard)

        compose.onNodeWithText("Change the phone's voice").performScrollTo().performClick()
        assertEquals("com.android.settings.TTS_SETTINGS", shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity.action)
    }

    @Test
    fun withoutAVoiceListeningSaysWhereToGetOne() {
        show()
        waitFor("No voice on this phone")
        compose.onNodeWithText("Listening").performScrollTo().performClick()
        waitFor("no text-to-speech voice")
        compose.onNodeWithText("Hear it").assertDoesNotExist()
        compose.onNodeWithText("Get Speech Services by Google").performClick()
        assertEquals("market://details?id=com.google.android.tts", shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity.dataString)
    }

    @Test
    fun theEReaderCanBeChangedAfterOnboarding() {
        runBlocking { store.update { it.copy(device = Device.KINDLE) } }
        show(SettingsPage.DELIVERY)
        waitFor("Email it to your Kindle")
        compose.onNode(hasText("Your e-reader") and hasClickAction()).performClick()
        compose.onNodeWithText("Boox or another Android e-reader").performClick()

        idleUntil { runBlocking { store.current().device } == Device.BOOX }
        waitFor("Boox or another Android e-reader")
        // A Boox reader isn't offered Kindle email.
        idleUntil { compose.onAllNodes(hasText("Email it to your Kindle")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun sendingByHandSaysWhenAnEditionCountsAsSentWithoutAFolder() {
        show(SettingsPage.DELIVERY)
        runBlocking { store.update { it.copy(device = Device.KOREADER, delivery = DeliveryMethod.SHARE) } }
        waitFor("counts as sent once you choose an app after Send")
        assertTrue("no folder in use to save to", compose.onAllNodes(hasText("saved to your folder", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theReaderTipIsAboutSharingSoOnlySharingShowsIt() {
        show(SettingsPage.DELIVERY)
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.SHARE) } }
        waitFor("tap Send and choose the Kindle app")
        // Under the choice it belongs to, where the reader is looking when they pick it.
        val (send, tip, folder) = listOf("Send it myself", "tap Send and choose the Kindle app", "Save to a folder").map {
            compose.onNodeWithText(it, substring = true).fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(send < tip && tip < folder)

        // A Kindle owner saving to a folder isn't told to wait for a Send.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        idleUntil { compose.onAllNodes(hasText("tap Send", substring = true)).fetchSemanticsNodes().isEmpty() }

        runBlocking { store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com") } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("tap Send", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSummarySaysHowEachPageIsSetAndOpensIt() {
        show()
        waitFor("About 30 minutes · 1 per source · take turns")
        compose.onNodeWithText("Off: make editions from Today").assertExists()
        val rows = SettingsPage.entries.map { compose.onNodeWithText(it.title).fetchSemanticsNode().positionInRoot.y }
        assertEquals("in the order of the pages", rows.sorted(), rows)

        compose.onNodeWithText("Schedule").assertHeightIsAtLeast(48.dp).performClick()
        waitFor("Make an edition automatically")
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("About 30 minutes")
    }

    @Test
    fun aChangeOnAPageShowsOnTheSummary() {
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.SHARE) } }
        show(SettingsPage.DELIVERY)
        waitFor("Send it myself")
        compose.onNodeWithText("Email it to your Kindle").performClick()
        waitFor("The address you send from")
        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { runBlocking { store.current().kindleEmailTarget } != null }

        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("Kindle · emailed to me_42@kindle.com")
    }

    @Test
    fun whatNeedsFixingShowsOnTheSummaryNotATapAway() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        grant("content://tree/books")
        runBlocking {
            store.update {
                it.copy(
                    device = Device.KOREADER, scheduleEnabled = true, delivery = DeliveryMethod.FOLDER, folderUri = "content://tree/books", folderName = "Books",
                )
            }
        }
        show()
        waitFor("KOReader · saved to Books")
        compose.onNodeWithText("Notifications are off.").assertExists()
        compose.onNodeWithText("Can't reach Books.").assertDoesNotExist()
    }

    @Test
    fun theBuildIsNamedAtTheBottomSoFeedbackCanSayWhichOne() {
        show()
        val shown = compose.onNodeWithText(versionLine, substring = true).performScrollTo()
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        // Read from the package, so this isn't a literal to edit on the way to every release, but
        // still a version: an empty one is how the line stops saying which build, and expected and
        // actual would both collapse to "NewspapeRSS " without this.
        assertTrue(shown, Regex("^NewspapeRSS \\d+\\.\\d+\\.\\d+(-debug.*)?$").matches(shown))
    }

    @Test
    fun aLongPressCopiesTheBuildForABugReportAndATapDoesNot() {
        show()
        val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.content.ClipboardManager::class.java)
        val version = compose.onNodeWithText(versionLine, substring = true).performScrollTo()
        val shown = version.fetchSemanticsNode().config[SemanticsProperties.Text].single().text

        version.performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(null, clipboard.primaryClip)

        version.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(shown, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals("said on screen, for e-readers with no clipboard notice", "Version copied", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals("TalkBack offers it too", "copy version", version.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnLongClick].label)
    }

    @Test
    fun perSourceCapCanBeRaised() {
        show(SettingsPage.EDITION)
        compose.onNodeWithText("+").performClick()
        waitFor("2 articles from each source, then more")
    }

    @Test
    fun aKindleReaderCanSwitchToEmailAndSetItUp() {
        show(SettingsPage.DELIVERY)
        installApp(ApplicationProvider.getApplicationContext())
        runBlocking { store.update { it.copy(device = Device.KINDLE) } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("Arrives on your Kindle by itself.").assertExists()
        compose.onNodeWithText("Kindle's email address").assertDoesNotExist()

        compose.onNodeWithText("Email it to your Kindle").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        idleUntil { runBlocking { store.current().delivery } == DeliveryMethod.KINDLE_EMAIL }
        waitFor("The address you send from must be on Amazon's approved list.")

        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { runBlocking { store.current().kindleEmail } == "me_42@kindle.com" }
        compose.onNodeWithText("Ask each time").performScrollTo().performClick()
        compose.onNodeWithText("Example Mail").performClick()

        idleUntil { runBlocking { store.current().kindleEmailTarget } == KindleEmail("me_42@kindle.com", MAIL_APP) }
    }

    @Test
    fun emailToKindleIsOfferedOnlyToKindleReadersOrWhoeverAlreadyUsesIt() {
        show(SettingsPage.DELIVERY)
        runBlocking { store.update { it.copy(device = Device.KOBO) } }
        waitFor("Send it myself")
        compose.onNodeWithText("Email it to your Kindle").assertDoesNotExist()

        // Changed their e-reader after setting it up: the choice they made stays visible.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com") } }
        waitFor("Email it to your Kindle")
        compose.onNodeWithText("Email it to your Kindle").assertIsSelected()
    }

    @Test
    fun withNotificationsOffEachDeliveryIsWarnedWhatItWontHear() {
        show(SettingsPage.SCHEDULE)
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = "me_42@kindle.com", scheduleEnabled = true) } }
        waitFor("Notifications are off")

        waitFor("you won't hear when an edition is ready to send")

        // A folder delivery that fails is only reported by notification.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree", folderName = "Books") } }
        waitFor("Notifications are off, so you won't hear if an edition fails to arrive.")
    }

    @Test
    fun emailDeliveryWithoutAnAddressSaysSendWillShareUntilOneIsAdded() {
        show(SettingsPage.DELIVERY)
        val line = "Add your Kindle's email address; until then Send opens the share sheet."
        runBlocking { store.update { it.copy(device = Device.KINDLE, delivery = DeliveryMethod.KINDLE_EMAIL, kindleEmail = null) } }
        waitFor(line)

        compose.onNode(hasSetTextAction() and hasText("Kindle's email address")).performScrollTo().performTextInput("me_42@kindle.com")
        idleUntil { compose.onAllNodes(hasText(line)).fetchSemanticsNodes().isEmpty() }

        // Still said after the e-reader changes, with an address that doesn't work.
        runBlocking { store.update { it.copy(device = Device.KOBO, kindleEmail = "me_42@kindle") } }
        waitFor(line)
    }

    private fun grant(uri: String) = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
        .takePersistableUriPermission(android.net.Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    private fun granted() = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
        .persistedUriPermissions.map { it.uri.toString() }.toSet()

    /** The delivery page, where the system folder picker answers each launch with [picked]. */
    private fun showDeliveryPicking(picked: () -> String) {
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, android.app.Activity.RESULT_OK, Intent().setData(android.net.Uri.parse(picked())))
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { SettingsPageScreen(vm, SettingsPage.DELIVERY, onBack = {}) }
        }
        idleUntil { vm.settings.value != null }
    }

    @Test
    fun pickingAnotherDeliveryFolderLetsGoOfTheOldOneButPickingTheSameKeepsIt() {
        val books = "content://com.android.externalstorage.documents/tree/primary%3ABooks"
        val kindle = "content://com.android.externalstorage.documents/tree/primary%3AKindle"
        grant(books)
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = books, folderName = "Books") } }
        var picked = books
        showDeliveryPicking { picked }

        // The same folder again: its grant is the one delivery still needs.
        compose.onNodeWithText("Choose another delivery folder").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf(books), granted())

        // Grants are capped per app, so the folder delivery moved away from is let go.
        picked = kindle
        compose.onNodeWithText("Choose another delivery folder").performScrollTo().performClick()
        idleUntil { runBlocking { store.current().folderUri } == kindle }
        assertEquals(setOf(kindle), granted())
    }

    @Test
    fun aDeliveryFolderTheAppCanNoLongerReachSaysSoAndATapPicksOneAgain() {
        // The folder's grant is gone (its app uninstalled, say): every save there would fail.
        runBlocking { store.update { it.copy(delivery = DeliveryMethod.FOLDER, folderUri = "content://tree/gone", folderName = "Books") } }
        show(SettingsPage.DELIVERY)
        waitFor("Can't reach Books. Tap to choose it again.")

        compose.onNodeWithText("Can't reach Books. Tap to choose it again.").performScrollTo().performClick()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity.action)
        assertEquals("content://tree/gone", runBlocking { store.current().folderUri })
    }

    /** At 200% the per-source count keeps the width; − and + go on the line below. */
    @Test
    @Config(application = TestApp::class, fontScale = 2f)
    fun atLargeFontSizesTheCountButtonsGoBelowItsWords() {
        show(SettingsPage.EDITION)
        val words = compose.onNodeWithText("from each source", substring = true).fetchSemanticsNode().boundsInRoot
        val fewer = compose.onNodeWithContentDescription("Fewer from each source").fetchSemanticsNode().boundsInRoot
        assertTrue("below", fewer.top >= words.bottom)
    }
}
