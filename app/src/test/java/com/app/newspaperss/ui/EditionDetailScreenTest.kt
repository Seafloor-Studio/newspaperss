package com.app.newspaperss.ui

import com.app.newspaperss.settings.offersOpen
import com.app.newspaperss.settings.Settings
import com.app.newspaperss.settings.Device
import android.app.Application
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.episodes.EpisodeKind
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.ArticleEntity
import com.app.newspaperss.data.ArticleState
import com.app.newspaperss.data.EditionArticleEntity
import com.app.newspaperss.data.EditionEntity
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.data.EditionStatus
import com.app.newspaperss.data.SourceEntity
import com.app.newspaperss.edition.EditionBuilder
import com.app.newspaperss.delivery.KindleSend
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.ViewModels
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.clearFileProviderCache
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.edition.EditionDetailScreen
import com.app.newspaperss.ui.edition.KINDLE_NOTE
import com.app.newspaperss.ui.edition.MARK_NOT_SENT
import com.app.newspaperss.ui.edition.MARK_SENT_WAITING
import com.app.newspaperss.ui.edition.EditionDetailViewModel
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import com.app.newspaperss.ui.today.BuildState
import com.app.newspaperss.ui.today.BUILD_STATUS
import com.app.newspaperss.work.EditionWorker
import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(AndroidJUnit4::class)
// Tall enough that every row of these short editions is composed without scrolling.
@Config(application = TestApp::class, qualifiers = "w411dp-h891dp")
class EditionDetailScreenTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()
    @get:Rule(order = 3) val viewModels = ViewModels()

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    // In the app's files dir: Open and Send hand the file out through its FileProvider.
    private val editionsDir by lazy { File(ApplicationProvider.getApplicationContext<Application>().filesDir, "editions").apply { mkdirs() } }
    private val repo by lazy { EditionRepository(db, editionsDir) }

    @Before @After fun freshFileProvider() = clearFileProviderCache()

    /** An edition of [titles] (in reading order), all in [articleState]; returns the edition and article ids. */
    private fun edition(
        status: EditionStatus,
        titles: List<String>,
        articleState: ArticleState = if (status == EditionStatus.DELIVERED) ArticleState.DELIVERED else ArticleState.IN_EDITION,
        withFile: Boolean = true,
        error: String? = null,
        name: String = "e",
        minutes: (Int) -> Double = { 4.0 },
    ): Pair<Long, List<Long>> = runBlocking {
        val source = db.sources().insert(SourceEntity(url = "https://example.com/$name/feed", title = "Example News"))
        val ids = titles.map { db.articles().insertIgnoring(ArticleEntity(sourceId = source, guid = it, url = "https://example.com/$it", title = it, state = articleState)) }
        if (withFile) editionsDir.resolve("$name.epub").writeText("epub")
        val editionId = db.editions().insert(
            EditionEntity(title = "Tuesday Morning Edition", status = status, fileName = "$name.epub", articleCount = titles.size, minutes = 12.0, error = error),
        )
        db.editions().insertArticles(
            titles.mapIndexed { i, title ->
                EditionArticleEntity(editionId = editionId, articleId = ids[i], position = i, title = title, sourceTitle = "Example News", minutes = minutes(i))
            }.reversed(),
        )
        editionId to ids
    }

    private fun show(editionId: Long, preferOpen: Boolean = false, offerOpen: Boolean = true): EditionDetailViewModel {
        val vm = viewModels.add(EditionDetailViewModel(repo, editionId) {})
        compose.setContent { EditionDetailScreen(vm, onBack = {}, preferOpen = preferOpen, offerOpen = offerOpen) }
        compose.idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        return vm
    }

    private fun waitFor(text: String) = compose.idleUntil {
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun episodePostsSayWhatTheyAre() {
        val titles = listOf("October 15", "Corruption as a Driving Force", "An essay", "Episode 12")
        val (id, ids) = edition(EditionStatus.READY, titles)
        runBlocking {
            val source = db.articles().byId(ids[0])!!.sourceId
            db.articles().pairEpisode(source, "October 15", "Corruption as a Driving Force", EpisodeKind.ARTICLE)
            db.articles().pairEpisode(source, "Corruption as a Driving Force", "October 15", EpisodeKind.AUDIO_VERSION)
            db.articles().setEpisodeKind(source, "An essay", EpisodeKind.ARTICLE)
            db.articles().setEpisodeKind(source, "Episode 12", EpisodeKind.NOTES)
        }
        show(id)
        waitFor("Episode notes")

        compose.onNodeWithText("Audio version of “October 15”").assertIsDisplayed()
        compose.onNodeWithText("Has an audio version").assertIsDisplayed()
        // Each under its own article, and none under the essay.
        val top = { text: String -> compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Has an audio version") in top("October 15")..top("Corruption as a Driving Force"))
        assertTrue(top("Episode notes") > top("An essay"))
        assertEquals(3, compose.onAllNodes(hasText("Episode notes") or hasText("audio version", substring = true, ignoreCase = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun contentsAreListedInReadingOrderWithAtLeastAMinuteEach() {
        val (id, _) = edition(EditionStatus.READY, listOf("First story", "Second story", "Third story")) { if (it == 1) 0.2 else 6.4 }
        show(id)

        val tops = listOf("First story", "Second story", "Third story").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText("Example News · 1 min").assertIsDisplayed()
        compose.onNodeWithText("3 articles · about 12 min").assertIsDisplayed()
    }

    @Test
    fun theStarOnADeliveredEditionBringsAnArticleBackAndUnstarringUndoesIt() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("Read it", "Missed one"))
        show(id)
        compose.onNodeWithText("Didn't get to one? Tap ☆ to bring it back.").assertIsDisplayed()

        compose.onNodeWithContentDescription("Put Missed one in your next edition").assertIsOff().performClick()

        waitFor("Starred for your next edition")
        compose.onNodeWithContentDescription("Put Missed one in your next edition").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Starred"))
        assertEquals(listOf(articles[1]), runBlocking { db.articles().buildCandidates() }.map { it.id })

        compose.onNodeWithContentDescription("Put Missed one in your next edition").performClick()
        compose.idleUntil { runBlocking { db.articles().buildCandidates() }.isEmpty() }
        assertEquals("unstarred, it's delivered as before", ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[1]) }?.state)
    }

    @Test
    fun articlesThatWentInStarredSaySoInWords() {
        val (id, _) = edition(EditionStatus.READY, listOf("Starred story", "Plain story"))
        db.openHelper.writableDatabase.execSQL("UPDATE edition_articles SET starred = 1 WHERE title = 'Starred story'")
        show(id)

        waitFor("Example News · 4 min · You starred it")
        compose.onNodeWithText("Example News · 4 min").assertIsDisplayed()
    }

    @Test
    fun aReadyEditionHasNoStarToggles() {
        val (id, _) = edition(EditionStatus.READY, listOf("Unsent story"))
        show(id)

        compose.onNodeWithText("Unsent story").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText("Next edition", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("in your next edition", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun anArticleWhoseSourceWasRemovedHasNoStar() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("Gone story"))
        runBlocking { db.sources().delete(db.sources().all().single()) }
        show(id)

        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasContentDescription("in your next edition", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun todaySaysHowManyStarredArticlesAreWaitingOnlyWhenThereAreSome() {
        val (_, articles) = edition(EditionStatus.DELIVERED, listOf("One", "Two"))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null)) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.editions != null }
        assertEquals(0, compose.onAllNodes(hasContentDescription("starred", substring = true)).fetchSemanticsNodes().size)

        runBlocking { articles.forEach { repo.setStarred(it, true) } }

        compose.idleUntil { compose.onAllNodes(hasContentDescription("2 starred articles are waiting for your next edition")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("2 starred articles are waiting for your next edition").assertIsDisplayed()
    }

    @Test
    fun openingOnAPhoneDoesntCountAsDelivered() {
        val (onPhone, _) = edition(EditionStatus.READY, listOf("A story"))
        show(onPhone)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Open on this phone").performClick()
        assertEquals(Intent.ACTION_VIEW, shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity.action)
        assertEquals("a phone may just be previewing it", EditionStatus.READY, runBlocking { db.editions().byId(onPhone) }?.status)
    }

    @Test
    fun aKindleReaderIsOfferedSendButNotOpen() {
        // Open would put the book on the phone, where a Kindle reader doesn't read it.
        val (id, _) = edition(EditionStatus.READY, listOf("A story"))
        show(id, offerOpen = Device.KINDLE.offersOpen)
        compose.onNodeWithText("Send").assertExists()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Open on this phone").assertDoesNotExist()
    }

    @Test
    fun todayOffersAKindleReaderSendButNotOpen() {
        edition(EditionStatus.READY, listOf("A story"))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null), settings = flowOf(Settings(device = Device.KINDLE))) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { compose.onAllNodes(hasText("Send")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open").assertDoesNotExist()
    }

    @Test
    fun openingOnABooxCountsAsDelivered() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        show(id, preferOpen = true)

        compose.onNodeWithText("Read").performClick()

        compose.idleUntil { runBlocking { db.editions().byId(id) }?.status == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun sayingItWasSentUsesUpTheArticles() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        show(id)

        compose.onNodeWithText("Mark as sent").performClick()

        // Not waitFor("Sent"): a ready edition already shows "Sent it another way?".
        compose.idleUntil { runBlocking { db.editions().byId(id) }?.status == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun anEditionReleasedAsNotSentCanStillBeMarkedAsSent() {
        // A newer edition was made first: this one was released, its articles back to waiting.
        val (id, articles) = edition(EditionStatus.FAILED, listOf("A story"), articleState = ArticleState.NEW)
        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(error = EditionBuilder.NOT_SENT)) }
        show(id)

        compose.onNodeWithText("Mark as sent").performClick()

        compose.idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    /**
     * What a page offers by its edition's status, its note and whether its book is still here. A
     * released edition, in either wording of its note, offers what a ready one does while its book
     * is here, and says so when it's gone; a build that failed has no book to offer.
     */
    @Test
    fun eachKindOfEditionOffersWhatItsBookAllows() {
        data class Case(val status: EditionStatus, val error: String?, val withFile: Boolean, val markAsSent: Boolean, val fileGone: Boolean)
        val cases = listOf(
            Case(EditionStatus.READY, null, withFile = true, markAsSent = true, fileGone = false),
            Case(EditionStatus.READY, null, withFile = false, markAsSent = true, fileGone = true),
            Case(EditionStatus.DELIVERED, null, withFile = true, markAsSent = false, fileGone = false),
            Case(EditionStatus.DELIVERED, null, withFile = false, markAsSent = false, fileGone = true),
            Case(EditionStatus.FAILED, EditionBuilder.NOT_SENT, withFile = true, markAsSent = true, fileGone = false),
            Case(EditionStatus.FAILED, EditionBuilder.OLD_NOT_SENT, withFile = true, markAsSent = true, fileGone = false),
            Case(EditionStatus.FAILED, EditionBuilder.NOT_SENT, withFile = false, markAsSent = false, fileGone = true),
            Case(EditionStatus.FAILED, EditionBuilder.OLD_NOT_SENT, withFile = false, markAsSent = false, fileGone = true),
            Case(EditionStatus.FAILED, EditionBuilder.INTERRUPTED, withFile = false, markAsSent = false, fileGone = false),
            Case(EditionStatus.FAILED, EditionBuilder.STOPPED, withFile = false, markAsSent = false, fileGone = false),
            Case(EditionStatus.FAILED, EditionBuilder.UNEXPECTED, withFile = false, markAsSent = false, fileGone = false),
        )
        // One screen, shown each edition in turn: a test can set its content only once.
        var shown by mutableStateOf<EditionDetailViewModel?>(null)
        compose.setContent { shown?.let { vm -> key(vm) { EditionDetailScreen(vm, onBack = {}) } } }
        cases.forEachIndexed { i, case ->
            val state = if (case.status == EditionStatus.FAILED) ArticleState.NEW else ArticleState.IN_EDITION
            val (id, _) = edition(case.status, listOf("Story $i"), articleState = state, withFile = case.withFile, error = case.error, name = "e$i")
            val vm = viewModels.add(EditionDetailViewModel(repo, id) {})
            shown = vm
            val label = case.toString()
            // Through the compose rule, which applies the change of screen; the looper alone doesn't.
            compose.idleUntil { vm.detail.value?.edition?.id == id }
            compose.waitForIdle()
            assertEquals(label, case.markAsSent, compose.onAllNodesWithText("Mark as sent").fetchSemanticsNodes().isNotEmpty())
            assertEquals(label, case.fileGone, compose.onAllNodesWithText("has been deleted", substring = true).fetchSemanticsNodes().isNotEmpty())
            if (case.markAsSent) compose.onNodeWithText("Mark as sent").assertIsEnabled()
        }
    }

    /**
     * A released edition can't be marked as sent while another is being made, which may be taking
     * its articles: its button is greyed out and says why, and comes back once the build is over.
     * A ready edition's isn't held back.
     */
    @Test
    fun aReleasedEditionsMarkAsSentWaitsForAnEditionBeingMade() {
        val (id, articles) = edition(EditionStatus.FAILED, listOf("A story"), articleState = ArticleState.NEW, error = EditionBuilder.NOT_SENT)
        val building = runBlocking { db.editions().insert(EditionEntity(title = "Wednesday Morning Edition", status = EditionStatus.BUILDING)) }
        show(id)
        waitFor(MARK_SENT_WAITING)
        compose.onNodeWithText("Mark as sent").assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, MARK_SENT_WAITING))

        runBlocking { db.editions().update(db.editions().byId(building)!!.copy(status = EditionStatus.READY)) }
        compose.idleUntil { compose.onAllNodesWithText(MARK_SENT_WAITING).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Mark as sent").assertIsEnabled().performClick()
        compose.idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun anEditionReleasedUnderTheOlderWordingCanBeMarkedAsSent() {
        val (id, articles) = edition(EditionStatus.FAILED, listOf("A story"), articleState = ArticleState.NEW, error = EditionBuilder.OLD_NOT_SENT)
        show(id)
        compose.onNodeWithText("Mark as sent").performClick()
        compose.idleUntil { statusOf(id) == EditionStatus.DELIVERED }
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun aReadyEditionCanBeMarkedAsSentWhileAnotherIsBeingMade() {
        val (id, _) = edition(EditionStatus.READY, listOf("A story"))
        runBlocking { db.editions().insert(EditionEntity(title = "Wednesday Morning Edition", status = EditionStatus.BUILDING)) }
        show(id)
        compose.onNodeWithText("Mark as sent").assertIsEnabled()
        compose.onNodeWithText(MARK_SENT_WAITING).assertDoesNotExist()
    }

    @Test
    fun aFailedBuildCantBeMarkedAsSent() {
        val (id, _) = edition(EditionStatus.FAILED, listOf("A story"), articleState = ArticleState.NEW)
        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(error = EditionBuilder.INTERRUPTED)) }
        show(id)

        compose.onNodeWithText("Mark as sent").assertDoesNotExist()
    }

    @Test
    fun aDeletedEpubCanNeitherBeSentNorOpened() {
        val (id, _) = edition(EditionStatus.READY, listOf("A story"), withFile = false)
        show(id)

        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("has been deleted", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Mark as sent").assertIsEnabled()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Open on this phone").assertDoesNotExist()
    }

    @Test
    fun tappingAnEarlierEditionOnTodayOpensIt() {
        val older = runBlocking {
            db.editions().insert(EditionEntity(title = "Monday Morning Edition", createdAt = Instant.parse("2026-09-28T06:30:00Z"), status = EditionStatus.DELIVERED))
        }
        runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", createdAt = Instant.parse("2026-09-29T06:30:00Z"), status = EditionStatus.READY)) }
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null)) {})
        var opened: Long? = null
        compose.setContent { TodayScreen(vm, onOpenEdition = { opened = it }) }
        compose.idleUntil { vm.state.value.editions?.size == 2 }

        compose.onNodeWithText("Monday Morning Edition").performClick()

        assertEquals(older, opened)
    }

    private fun work(state: WorkInfo.State, progress: androidx.work.Data = androidx.work.Data.EMPTY, output: androidx.work.Data = androidx.work.Data.EMPTY) =
        WorkInfo(UUID.randomUUID(), state, emptySet(), outputData = output, progress = progress)

    @Test
    fun talkBackHearsTheBuildGoFromCheckingToItsResultButNotEveryCount() {
        // A new reader's first build: the "make your first edition" prompt goes away as it starts.
        val work = MutableStateFlow<WorkInfo?>(null)
        val vm = viewModels.add(TodayViewModel(repo, work) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.editions != null }
        // TalkBack announces a live region whose text changes, not one that appears, so the status
        // must stay the same node from the tap to the result.
        val status = compose.onNodeWithTag(BUILD_STATUS).fetchSemanticsNode().id
        fun statusNode() = compose.onNodeWithTag(BUILD_STATUS).fetchSemanticsNode()
        fun text() = statusNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }

        work.value = work(WorkInfo.State.ENQUEUED)
        compose.idleUntil { vm.state.value.build == BuildState.Syncing }
        compose.waitForIdle()
        assertEquals(status, statusNode().id)
        assertEquals("Checking your sources for new articles…", text())
        assertTrue(SemanticsProperties.LiveRegion in statusNode().config)

        work.value = work(WorkInfo.State.RUNNING, progress = workDataOf(EditionWorker.STAGE to EditionWorker.STAGE_FETCHING, EditionWorker.FETCHED to 3))
        compose.idleUntil { vm.state.value.build is BuildState.Fetching }
        compose.waitForIdle()
        assertEquals(status, statusNode().id)
        assertEquals("Making your edition", text())
        compose.onNode(hasText("3 articles so far") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertDoesNotExist()

        work.value = work(WorkInfo.State.SUCCEEDED)
        compose.idleUntil { vm.state.value.build == BuildState.Idle }
        compose.waitForIdle()
        assertEquals(status, statusNode().id)
        assertEquals("Your edition is ready.", text())
    }

    @Test
    fun aFailedBuildIsAnnounced() {
        val work = MutableStateFlow<WorkInfo?>(work(WorkInfo.State.ENQUEUED))
        val vm = viewModels.add(TodayViewModel(repo, work) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.build == BuildState.Syncing }
        compose.waitForIdle()

        work.value = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        compose.idleUntil { vm.state.value.build is BuildState.Failed }
        compose.waitForIdle()
        compose.onNode(hasText("Couldn't reach any of your sources.") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
    }

    @Test
    fun aBuildQueuedWithoutAConnectionSaysItsWaitingForOne() {
        val online = MutableStateFlow(false)
        val vm = viewModels.add(TodayViewModel(repo, flowOf(work(WorkInfo.State.ENQUEUED)), online = online) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }

        compose.idleUntil { vm.state.value.build == BuildState.WaitingForNetwork }
        compose.onNodeWithText("Waiting for an internet connection…").assertExists()

        online.value = true
        compose.idleUntil { vm.state.value.build == BuildState.Syncing }
        compose.onNodeWithText("Checking your sources for new articles…").assertExists()
    }

    @Test
    fun anOldFailureIsShownButNotAnnouncedEachTimeTodayOpens() {
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(failed)) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.build is BuildState.Failed }

        compose.onNodeWithText("Couldn't reach any of your sources.").assertExists()
        compose.onNode(hasText("Couldn't reach any of your sources.") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertDoesNotExist()
    }

    @Test
    fun onceAnEditionIsSentAnotherIsOfferedButNotPushed() {
        // An edition that ends: after today's is sent, making another is a quiet option.
        edition(EditionStatus.DELIVERED, listOf("A story"))
        var started = 0
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null)) { started++ })
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.editions?.size == 1 }

        compose.onNodeWithText("Make an edition now").assertDoesNotExist()
        compose.onNodeWithText("Make another edition").performClick()
        assertEquals(1, started)
    }

    @Test
    fun aFailedEditionOffersOneWayToTryAgain() {
        // The build that made it failed too, with the same reason.
        runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.FAILED, error = EditionBuilder.UNEXPECTED)) }
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to EditionBuilder.UNEXPECTED))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(failed)) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.editions?.size == 1 && vm.state.value.build is BuildState.Failed }

        assertEquals("said once", 1, compose.onAllNodes(hasText(EditionBuilder.UNEXPECTED)).fetchSemanticsNodes().size)
        // In the status line, where TalkBack announces a build's result.
        compose.onNodeWithTag(BUILD_STATUS).assertTextEquals(EditionBuilder.UNEXPECTED)

        assertEquals(1, compose.onAllNodes(hasText("Try again") and hasClickAction()).fetchSemanticsNodes().size)
        compose.onNodeWithText("Make another edition").assertDoesNotExist()
        compose.onNodeWithText("Make an edition now").assertDoesNotExist()
    }

    @Test
    fun aReadyEditionsSendComesBeforeARetry() {
        // A run can fail after making an edition; sending that one comes first.
        edition(EditionStatus.READY, listOf("A story"))
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Something went wrong."))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(failed)) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.build is BuildState.Failed && vm.state.value.editions?.size == 1 }

        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Make another edition").assertExists()
    }

    @Test
    fun aFailedBuildAfterASentEditionOffersToTryAgain() {
        edition(EditionStatus.DELIVERED, listOf("A story"))
        var started = 0
        val failed = work(WorkInfo.State.FAILED, output = workDataOf(EditionWorker.ERROR to "Couldn't reach any of your sources."))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(failed)) { started++ })
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.build is BuildState.Failed }

        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, started)
    }

    @Test
    fun makeAnotherEditionSitsBelowTheLatestWhetherSentOrNot() {
        // One place for it, whether the edition is waiting to be sent or already sent.
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null)) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.editions?.size == 1 }
        fun below() = compose.onNodeWithText("Make another edition").fetchSemanticsNode().boundsInRoot.top >
            compose.onNodeWithText("Tuesday Morning Edition").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("below a sent edition", below())

        runBlocking { db.editions().update(db.editions().byId(id)!!.copy(status = EditionStatus.READY)) }
        compose.idleUntil { vm.state.value.editions?.single()?.status == EditionStatus.READY }
        compose.waitForIdle()
        assertTrue("below a ready edition", below())
    }

    @Test
    fun nothingNewAfterAnEditionSaysSoAsFinished() {
        edition(EditionStatus.DELIVERED, listOf("A story"))
        val nothing = work(WorkInfo.State.SUCCEEDED, output = workDataOf(EditionWorker.NOTHING_NEW to true))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(nothing)) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        compose.idleUntil { vm.state.value.build == BuildState.NothingNew && vm.state.value.editions?.size == 1 }

        // Not "add sources": this reader has an edition, and there's just nothing since.
        compose.onNodeWithTag(BUILD_STATUS).assertTextEquals("Nothing new since your last edition. Check back later.")
    }

    @Test
    fun tappingTodaysReadyCardOpensTheLatestEdition() {
        val latest = runBlocking {
            db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.READY, articleCount = 5, minutes = 30.0))
        }
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null)) {})
        var opened: Long? = null
        compose.setContent { TodayScreen(vm, onOpenEdition = { opened = it }) }
        compose.idleUntil { vm.state.value.editions?.size == 1 }

        compose.onNodeWithText("See what's inside").assertDoesNotExist()
        compose.onNode(hasText("Tuesday Morning Edition") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(latest, opened)
    }

    private fun statusOf(id: Long) = runBlocking { db.editions().byId(id) }?.status

    /** Mark as not sent from the menu, [options] being its button's description. */
    private fun markNotSent(options: String) {
        compose.onNodeWithContentDescription(options).performClick()
        compose.onNodeWithText(MARK_NOT_SENT).performClick()
        compose.onNode(hasText("Mark as not sent") and hasAnyAncestor(isDialog())).performClick()
    }

    private val todaysOptions = "More options for Tuesday Morning Edition"

    @Test
    fun aSentEditionThatNeverArrivedCanBeMarkedAsNotSentAfterAsking() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("A story"))
        show(id)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(MARK_NOT_SENT).performClick()
        compose.onNodeWithText("Mark “Tuesday Morning Edition” as not sent?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals("cancelling changes nothing", EditionStatus.DELIVERED, statusOf(id))

        markNotSent("More options")

        compose.idleUntil { statusOf(id) == EditionStatus.READY }
        assertEquals(ArticleState.IN_EDITION, runBlocking { db.articles().byId(articles[0]) }?.state)
        waitFor("Mark as sent")
        compose.onNodeWithText("Send").assertExists()
    }

    @Test
    fun aSentEditionWhoseFileIsGoneCantBeMarkedAsNotSent() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"), withFile = false)
        show(id)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Send again").assertDoesNotExist()
        compose.onNodeWithText(MARK_NOT_SENT).assertDoesNotExist()
    }

    @Test
    fun todaysSentEditionCanBeMarkedAsNotSentAndSentAgain() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null), settings = flowOf(Settings(device = Device.KINDLE))) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        waitFor("· sent")

        markNotSent(todaysOptions)

        compose.idleUntil { statusOf(id) == EditionStatus.READY }
        waitFor("Mark as sent")
    }

    @Test
    fun afterASendWithTheKindleAppTodaySaysItCanTakeAFewMinutes() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        val kindle = MutableStateFlow(emptyMap<Long, KindleSend>())
        val vm = viewModels.add(TodayViewModel(repo, flowOf(null), sentToKindle = kindle) {})
        compose.setContent { TodayScreen(vm, onOpenEdition = {}) }
        waitFor("· sent")
        compose.onNodeWithText(KINDLE_NOTE).assertDoesNotExist()

        kindle.value = mapOf(id to KindleSend.APP)
        waitFor(KINDLE_NOTE)

        // Marked as not sent, it's waiting to be sent again: the note would contradict that.
        markNotSent(todaysOptions)
        waitFor("Mark as sent")
        compose.onNodeWithText(KINDLE_NOTE).assertDoesNotExist()
    }

    @Test
    fun anEditionJustSentWithTheKindleAppSaysItCanTakeAFewMinutes() {
        val (id, _) = edition(EditionStatus.DELIVERED, listOf("A story"))
        // Another edition sent with Kindle says nothing about this one.
        val kindle = MutableStateFlow(mapOf(id + 1 to KindleSend.APP))
        val vm = viewModels.add(EditionDetailViewModel(repo, id, sentToKindle = kindle) {})
        compose.setContent { EditionDetailScreen(vm, onBack = {}) }
        compose.idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        compose.waitForIdle()
        compose.onNodeWithText(KINDLE_NOTE).assertDoesNotExist()

        kindle.value = mapOf(id to KindleSend.APP)
        waitFor(KINDLE_NOTE)
        compose.onNodeWithText(KINDLE_NOTE).assertIsDisplayed()
    }

    private val dismissed = mutableListOf<Long>()

    private fun deleteFromTheScreen(id: Long) {
        var left = false
        val vm = viewModels.add(EditionDetailViewModel(repo, id) { dismissed += it })
        compose.setContent { EditionDetailScreen(vm, onBack = { left = true }) }
        compose.idleUntil { vm.detail.value?.contents?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete edition").performClick()
        compose.onNode(hasText("Delete edition") and hasAnyAncestor(isDialog())).performClick()
        // The screen leaves once it sees the deletion, on its next frame.
        compose.idleUntil { left }
    }

    private fun isGone(id: Long) = runBlocking { repo.observeAll().first().none { it.id == id } && repo.observe(id).first() == null }

    @Test
    fun deletingAnUnsentEditionGivesItsArticlesToTheNextOne() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))

        deleteFromTheScreen(id)

        assertTrue(isGone(id))
        assertEquals(ArticleState.NEW, runBlocking { db.articles().byId(articles[0]) }?.state)
        assertFalse("its EPUB is gone", editionsDir.resolve("e.epub").exists())
        assertEquals("its Send notification goes too", listOf(id), dismissed)
    }

    @Test
    fun deletingASentEditionDoesntBringItsArticlesBack() {
        val (id, articles) = edition(EditionStatus.DELIVERED, listOf("A story"))

        deleteFromTheScreen(id)

        assertTrue(isGone(id))
        assertEquals(ArticleState.DELIVERED, runBlocking { db.articles().byId(articles[0]) }?.state)
        assertFalse("its EPUB is gone", editionsDir.resolve("e.epub").exists())
    }

    @Test
    fun aFolderCopyFinishingAfterADeleteDoesntBringItBack() {
        val (id, articles) = edition(EditionStatus.READY, listOf("A story"))
        runBlocking { repo.delete(id) }

        runBlocking { repo.markDelivered(id) }

        assertTrue(isGone(id))
        assertEquals("its articles stay with the next edition", ArticleState.NEW, runBlocking { db.articles().byId(articles[0]) }?.state)
    }

    @Test
    fun deletingAsksByNameAndSaysWhatHappensToTheArticles() {
        val (id, _) = edition(EditionStatus.READY, listOf("A story"))
        show(id)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete edition").performClick()

        compose.onNodeWithText("Delete Tuesday Morning Edition?").assertIsDisplayed()
        compose.onNodeWithText("It hasn't been sent, so its articles go into your next edition.").assertIsDisplayed()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(EditionStatus.READY, runBlocking { db.editions().byId(id) }?.status)
    }

    @Test
    fun anEditionBeingMadeCantBeDeleted() {
        val building = runBlocking { db.editions().insert(EditionEntity(title = "Tuesday Morning Edition", status = EditionStatus.BUILDING)) }

        assertFalse(runBlocking { repo.delete(building) })
        val vm = viewModels.add(EditionDetailViewModel(repo, building) {})
        compose.setContent { EditionDetailScreen(vm, onBack = {}) }
        compose.idleUntil { vm.detail.value?.edition != null }
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        assertEquals(EditionStatus.BUILDING, runBlocking { db.editions().byId(building) }?.status)
    }
}
