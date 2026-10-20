package com.app.newspaperss.ui

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.newspaperss.MainActivity
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
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun enableChecks() = compose.enableAccessibilityChecks()

    private fun checkScreen() {
        compose.waitForIdle()
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        (0 until roots).forEach { compose.onAllNodes(isRoot())[it].tryPerformAccessibilityChecks() }
    }

    private fun tap(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring)).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onAllNodes(hasText(text, substring)).onFirst()
        // Below the fold on a small screen; a node outside any scrolling list has nothing to scroll.
        runCatching { node.performScrollTo() }
        return node.performClick()
    }

    private fun tab(label: String) {
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
            compose.onAllNodes(hasContentDescription("All of", substring = true)).onFirst().performClick()
            checkScreen()
            tap("Next"); checkScreen()
            tap("Make my first edition")
        }
        for (label in listOf("Today", "Sources", "Settings")) {
            tab(label); checkScreen()
        }
        // The same at the largest text Android offers, where labels wrap and buttons grow.
        shell("settings put system font_scale 2.0")
        try {
            compose.activityRule.scenario.recreate()
            for (label in listOf("Today", "Sources", "Settings")) {
                tab(label); checkScreen()
            }
        } finally {
            shell("settings put system font_scale 1.0")
        }
    }
}
