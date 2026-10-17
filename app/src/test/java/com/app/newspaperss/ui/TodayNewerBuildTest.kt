package com.app.newspaperss.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.DebugUpdates
import com.app.newspaperss.InstallTap
import com.app.newspaperss.SelfUpdate
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.closeAfter
import com.app.newspaperss.testutil.idleUntil
import com.app.newspaperss.ui.today.TodayScreen
import com.app.newspaperss.ui.today.TodayViewModel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** A live build's line about a newer build, in each state, and where tapping it goes. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class TodayNewerBuildTest {
    @get:Rule(order = 0) val closeDb = closeAfter { db.close() }
    @get:Rule(order = 1) val tmp = TemporaryFolder()
    @get:Rule(order = 2) val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private var tap: InstallTap = InstallTap.Started
    private var installs = 0
    private val link = DebugUpdates.downloadFor(android.os.Build.SUPPORTED_ABIS)

    private fun today(selfUpdate: SelfUpdate, newerBuild: Int? = 464) {
        val vm = TodayViewModel(EditionRepository(db, tmp.newFolder()), flowOf(null)) {}
        compose.setContent {
            TodayScreen(vm, newerBuild = newerBuild, selfUpdate = selfUpdate, onInstall = {
                installs++
                tap
            })
        }
        idleUntil { compose.waitForIdle(); vm.state.value.editions != null }
    }

    private fun browserOpened() = shadowOf(app).nextStartedActivity?.takeIf { it.action == Intent.ACTION_VIEW }?.data.toString()

    @Test
    fun withNothingUnderWayItLinksToTheDownload() {
        today(SelfUpdate.None)
        compose.onNodeWithText("Test build 464 is out. Download it").performClick()
        assertEquals(link, browserOpened())
    }

    @Test
    fun whileDownloadingThereIsNothingToTap() {
        today(SelfUpdate.Downloading(464, waitingForWifi = false))
        compose.onNodeWithText("Downloading test build 464").assertHasNoClickAction()
    }

    @Test
    fun waitingForWifiItOffersTheBrowserNow() {
        today(SelfUpdate.Downloading(464, waitingForWifi = true))
        compose.onNodeWithText("Test build 464 downloads on Wi-Fi. Download it now").performClick()
        assertEquals(link, browserOpened())
    }

    @Test
    fun readyTapInstalls() {
        today(SelfUpdate.Ready(465))
        compose.onNodeWithText("Test build 465 is ready. Install it").performClick()
        compose.waitForIdle()
        assertEquals(1, installs)
        assertNull("nothing else opens: Android asks", shadowOf(app).nextStartedActivity)
    }

    @Test
    fun readyButBusySaysWhenItWillInstall() {
        tap = InstallTap.Waiting("It installs once the edition is made.")
        today(SelfUpdate.Ready(465))
        compose.onNodeWithText("Test build 465 is ready. Install it").performClick()
        idleUntil { ShadowToast.getTextOfLatestToast() != null }
        assertEquals("It installs once the edition is made.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun readyButGoneFallsBackToTheBrowser() {
        tap = InstallTap.Unavailable
        today(SelfUpdate.Ready(465))
        compose.onNodeWithText("Test build 465 is ready. Install it").performClick()
        compose.waitForIdle()
        assertEquals(link, browserOpened())
    }

    @Test
    fun withoutPermissionTapOpensInstallUnknownApps() {
        today(SelfUpdate.NeedsPermission(465))
        compose.onNodeWithText("Test build 465 is ready. Allow installing it").performClick()
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened.action)
        assertEquals("package:${app.packageName}", opened.data.toString())
    }
}
