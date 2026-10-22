package com.app.newspaperss.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.WorkManager
import com.app.newspaperss.MainActivity
import com.app.newspaperss.work.EditionWorker
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Google's accessibility checks (touch targets, labels, contrast, text that doesn't fit) on each
 * screen a new reader meets, in the real app. They need a real Android: under Robolectric the
 * checks run on nothing and pass, so ScreenshotTest can't carry them.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {
    // Fails with the stuck thread's stack, rather than holding the emulator job for hours.
    @get:Rule(order = 0) val timeout: Timeout = Timeout.builder().withTimeout(10, TimeUnit.MINUTES).withLookingForStuckThread(true).build()
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    // Not createAndroidComposeRule<MainActivity>: its launch waits for the main thread to go idle,
    // which the splash screen holds off until settings load, which needs a frame from the test's
    // clock, which the waiting launch never lets it give.
    @Before fun launch() {
        compose.enableAccessibilityChecks()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasText("Get started") or hasText("Today")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // After the test, the Compose rule waits for the main thread to go idle. An edition still being
    // made keeps a spinner turning, so that wait never ends and a 10-minute timeout hides the
    // test's own result: close the app first.
    @After fun close() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED, Stage.CREATED)
                .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
                .forEach { it.finish() }
        }
    }

    /** Until the first edition is made, so Today is checked as a reader finds it, not mid-spinner. */
    private fun waitForTheEdition() {
        val work = WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
        val deadline = System.currentTimeMillis() + 8 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val infos = work.getWorkInfosForUniqueWork(EditionWorker.UNIQUE).get()
            if (infos.isNotEmpty() && infos.all { it.state.isFinished }) return
            Thread.sleep(1_000)
        }
        error("the first edition wasn't made within 8 minutes")
    }

    private fun resumed(): Activity? {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return activity
    }

    // In logcat, so a failure that names no line of this test still says which screen it was on.
    private fun step(what: String) = Log.i("AccessibilityWalk", what)

    private fun checkScreen() {
        step("check")
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        (0 until roots).forEach { compose.onAllNodes(isRoot())[it].tryPerformAccessibilityChecks() }
    }

    private fun tap(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        step("tap $text")
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring)).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(hasText(text, substring)).onFirst()
        // Below the fold on a small screen; a node outside any scrolling list has nothing to scroll.
        runCatching { node.performScrollTo() }
        return node.performClick()
    }

    private fun tab(label: String) {
        step("tab $label")
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick()
    }

    companion object {
        private fun shell(command: String) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).close()
        }

        // Asked for when the first edition is made; Android's dialog would cover the screen.
        @BeforeClass @JvmStatic fun allowNotifications() {
            if (Build.VERSION.SDK_INT >= 33) {
                val app = InstrumentationRegistry.getInstrumentation().targetContext.packageName
                shell("pm grant $app ${Manifest.permission.POST_NOTIFICATIONS}")
            }
        }
    }

    @Test fun everyScreenANewReaderMeetsPasses() {
        // Run against an app already set up (a second run on a phone), it starts on Today.
        if (compose.onAllNodes(hasText("Get started")).fetchSemanticsNodes().isNotEmpty()) {
            checkScreen()
            tap("Get started"); checkScreen()
            tap("Something else", substring = true); checkScreen()
            tap("Next")
            tap("I'll pick some sites"); checkScreen()
            compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("All of", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            val pack = compose.onAllNodes(hasContentDescription("All of", substring = true)).onFirst()
            runCatching { pack.performScrollTo() }
            pack.performClick()
            // Next stays disabled until the pick lands.
            compose.waitUntil(30_000) { compose.onAllNodes(hasText("Next") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            checkScreen()
            tap("Next"); checkScreen()
            tap("Make my first edition")
            waitForTheEdition()
        }
        for (label in listOf("Today", "Sources", "Settings")) {
            tab(label); checkScreen()
        }
        // The same at the largest text Android offers, where labels wrap and buttons grow.
        // Android recreates the activity for the new font scale by itself. Wait for the new one:
        // a tap on the old one lands while the new one's splash holds every frame back until
        // settings load, which takes a frame from the test's clock that only waitUntil advances,
        // so the tap's wait for idle never ends.
        val before = resumed()
        shell("settings put system font_scale 2.0")
        try {
            compose.waitUntil(30_000) {
                resumed().let { it != null && it !== before } &&
                    compose.onAllNodes(hasText("Settings") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
            }
            for (label in listOf("Today", "Sources", "Settings")) {
                tab(label); checkScreen()
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }
}
