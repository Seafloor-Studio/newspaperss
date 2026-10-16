package com.app.newspaperss

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.FakeHttp
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class DebugUpdatesTest {
    private val prefs = ApplicationProvider.getApplicationContext<TestApp>().getSharedPreferences("debug-updates-test", Context.MODE_PRIVATE)
    private val http = FakeHttp()
    private var asked = 0
    private var clock = 1_000_000_000_000L

    init {
        prefs.edit().clear().commit()
        http.beforeResponse = { if (it == DebugUpdates.RELEASE_API) asked++ }
    }

    private fun release(build: Int) = http.page(
        DebugUpdates.RELEASE_API,
        """{"tag_name":"latest-debug","assets":[{"name":"newspapeRSS-debug-$build.apk","browser_download_url":"https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug-$build.apk"},{"name":"newspapeRSS-debug.apk"}]}""",
    )

    private fun updates(installed: Int?) = DebugUpdates(http, prefs, installed) { clock }

    @Test
    fun aNewerBuildIsNoticedAtMostEveryThreeHours() = runTest {
        release(464)
        val updates = updates(installed = 455)
        updates.checkIfDue()
        assertEquals(464, updates.newer.value)

        release(470)
        clock += TimeUnit.HOURS.toMillis(2)
        updates.checkIfDue()
        assertEquals("not asked again within three hours", 1, asked)
        assertEquals(464, updates.newer.value)

        clock += TimeUnit.HOURS.toMillis(1)
        updates.checkIfDue()
        assertEquals(2, asked)
        assertEquals(470, updates.newer.value)
    }

    @Test
    fun theNewestBuildIsRememberedUntilItIsInstalled() = runTest {
        release(464)
        updates(installed = 455).checkIfDue()
        assertEquals("after a restart, without asking", 464, updates(installed = 455).newer.value)
        assertEquals(1, asked)
        assertNull("once it's installed", updates(installed = 464).newer.value)
    }

    @Test
    fun theSameBuildIsNothingNew() = runTest {
        release(464)
        val updates = updates(installed = 464)
        updates.checkIfDue()
        assertNull(updates.newer.value)
    }

    @Test
    fun offlineItAsksAgainNextTime() = runTest {
        http.unreachable += DebugUpdates.RELEASE_API
        val updates = updates(installed = 455)
        updates.checkIfDue()
        http.unreachable.clear()
        release(464)
        updates.checkIfDue()
        assertEquals(464, updates.newer.value)
    }

    @Test
    fun aRefusalWaitsThreeHoursLikeAnAnswer() = runTest {
        http.page(DebugUpdates.RELEASE_API, "rate limited", code = 403)
        val updates = updates(installed = 455)
        updates.checkIfDue()
        release(464)
        updates.checkIfDue()
        assertEquals("not again on the next return to the app", 1, asked)
        assertNull(updates.newer.value)
        clock += DebugUpdates.INTERVAL_MILLIS
        updates.checkIfDue()
        assertEquals(464, updates.newer.value)
    }

    @Test
    fun aLocalOrReleaseBuildNeverAsks() = runTest {
        release(464)
        val updates = updates(installed = DebugUpdates.buildOf("0.1.0-debug"))
        updates.checkIfDue()
        updates(installed = DebugUpdates.buildOf("0.1.0")).checkIfDue()
        assertEquals(0, asked)
        assertNull(updates.newer.value)
        assertEquals(464, DebugUpdates.buildOf("0.1.0-debug.464+012eb32"))
    }

    /** Each device is sent an APK it can install, and every one is a file CI publishes. */
    @Test
    fun theDownloadIsTheApkForTheDevicesOwnAbi() {
        val phone = DebugUpdates.downloadFor(arrayOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        val olderEReader = DebugUpdates.downloadFor(arrayOf("armeabi-v7a", "armeabi"))
        val emulator = DebugUpdates.downloadFor(arrayOf("x86_64", "x86", "arm64-v8a"))
        assertEquals("https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug.apk", phone)
        assertEquals("https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug-armeabi-v7a.apk", olderEReader)
        assertEquals("https://github.com/madCode/newspaperss/releases/download/latest-debug/newspapeRSS-debug-x86_64.apk", emulator)

        // The names CI uploads to latest-debug, so a renamed file can't leave a link to nothing.
        val published = java.io.File("../.github/workflows/build-and-test.yml").readText()
            .lines().single { "gh release upload latest-debug" in it }
        for (link in listOf(phone, olderEReader, emulator, DebugUpdates.downloadFor(arrayOf("x86")))) {
            val name = link.substringAfterLast('/')
            assertTrue("$name is uploaded", Regex("""(^|\s)${Regex.escape(name)}(\s|$)""").containsMatchIn(published))
        }
    }
}
