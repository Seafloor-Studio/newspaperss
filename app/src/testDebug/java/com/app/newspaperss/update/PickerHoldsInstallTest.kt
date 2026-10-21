package com.app.newspaperss.update

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.app.newspaperss.AwaitedAnswers
import com.app.newspaperss.MainActivity
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A picker on top pauses the app as leaving it does. A background install waits for the picker's
 * answer, through a rotation and a restart, and goes once it's in.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class PickerHoldsInstallTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val prefs = app.getSharedPreferences("picker-holds-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val keys = setOf("our-key")
    private val commits = mutableListOf<Int>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        AwaitedAnswers(app).forget()
        runBlocking { app.container.settings.update { it.copy(onboarded = true) } }
    }

    @After fun closeWork() = WorkManagerTestInitHelper.closeWorkDatabase()

    /** Build 464 downloaded and checked; the app neither in front nor running anything. */
    private fun updates(): LiveUpdates {
        val dir = tmp.newFolder()
        dir.resolve("build-464.apk").writeText("apk-464")
        prefs.edit().putInt(LiveUpdates.READY, 464).commit()
        return LiveUpdates(
            app, 455, CoroutineScope(Dispatchers.Unconfined), listenPlaying = { false }, prefs = prefs, dir = dir,
            inspect = { ApkFacts(app.packageName, 4642, "0.1.0-debug.464+0000000", keys) },
            installed = { ApkFacts(app.packageName, 4552, "0.1.0-debug.455+abc1234", keys) },
            commit = { _, build, stillIdle -> if (stillIdle()) commits += build },
            canInstall = { true },
            foreground = { false },
        )
    }

    private fun backgroundInstall(updates: LiveUpdates) = runBlocking { updates.install(userAsked = false) }

    /** Opens Sources › Export, Android's file picker; returns its request code. */
    private fun openExportPicker(scenario: ActivityScenario<MainActivity>): Int {
        idleUntil { compose.onAllNodes(hasText("Sources")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Sources"))[0].performClick()
        idleUntil { compose.onAllNodes(hasContentDescription("More options")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("More options")).performClick()
        compose.onNode(hasText("Export your sites (OPML)")).performClick()
        compose.waitForIdle()
        var code = -1
        scenario.onActivity { code = shadowOf(it).nextStartedActivityForResult.requestCode }
        return code
    }

    @Test
    fun aBackgroundInstallWaitsForThePickersAnswerThroughARotation() {
        val updates = updates()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val code = openExportPicker(scenario)
        // The picker covers the app: paused and stopped, as when the reader leaves it.
        scenario.moveToState(Lifecycle.State.CREATED)

        assertTrue(backgroundInstall(updates) is LiveUpdates.Outcome.Busy)
        scenario.recreate()
        assertTrue("a rotation doesn't let it go", backgroundInstall(updates) is LiveUpdates.Outcome.Busy)
        assertTrue(commits.isEmpty())

        scenario.onActivity { shadowOf(it).callOnActivityResult(code, Activity.RESULT_CANCELED, null) }
        assertEquals(LiveUpdates.Outcome.Started, backgroundInstall(updates))
        assertEquals(listOf(464), commits)
    }

    @Test
    fun aPickerLeftOpenStillHoldsUntilTheAppStartsAfresh() {
        val updates = updates()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // A picker out when Android ended the app; restored to take the answer.
            AwaitedAnswers(app).sent(7)
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(backgroundInstall(updates) is LiveUpdates.Outcome.Busy)
        }

        // A launcher tap after a notification opened the app: Android starts a second instance on
        // top of the task, with the picker still below it.
        val onTop = Robolectric.buildActivity(MainActivity::class.java, Intent.makeMainActivity(ComponentName(app, MainActivity::class.java)))
        shadowOf(onTop.get()).setIsTaskRoot(false)
        onTop.setup().pause().stop()
        assertTrue("the picker below may still answer", backgroundInstall(updates) is LiveUpdates.Outcome.Busy)
        onTop.destroy()

        // Started afresh, from anywhere: a new task, with no picker in it.
        val notification = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_LISTENING)
        ActivityScenario.launch<MainActivity>(notification).use { it.moveToState(Lifecycle.State.CREATED) }
        assertFalse(AwaitedAnswers(app).any)
        assertEquals(LiveUpdates.Outcome.Started, backgroundInstall(updates))
    }

    @Test
    fun anotherAppOpenedWithoutWaitingForAnAnswerDoesntHoldIt() {
        val updates = updates()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        // A share, a link or a settings screen: Android's startActivity goes through
        // startActivityForResult with -1, and no answer ever comes.
        scenario.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/"))) }
        scenario.moveToState(Lifecycle.State.CREATED)

        assertEquals(LiveUpdates.Outcome.Started, backgroundInstall(updates))
    }

    @Test
    fun aTapInstallsWhileAPickerIsOut() = runBlocking {
        AwaitedAnswers(app).sent(7)
        val updates = updates()
        assertEquals(LiveUpdates.Outcome.Started, updates.install(userAsked = true))
    }
}
