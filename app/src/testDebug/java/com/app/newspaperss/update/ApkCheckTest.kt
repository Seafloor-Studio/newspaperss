package com.app.newspaperss.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class ApkCheckTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private val installed = ApkFacts("com.app.newspaperss.debug", 4552, "0.1.0-debug.455+abc1234", setOf("our-key"))
    private val newer = installed.copy(versionCode = 4642, versionName = "0.1.0-debug.464+def5678")

    private fun bad(apk: ApkFacts?) = verdict(apk, installed) is Verdict.Bad
    private fun stale(apk: ApkFacts?) = verdict(apk, installed) is Verdict.Stale

    @Test
    fun onlyANewerBuildOfThisAppWithItsKeyPasses() {
        assertEquals(Verdict.Good, verdict(newer, installed))
        assertTrue("another app", bad(newer.copy(packageName = "com.app.newspaperss")))
        assertTrue("an older build", stale(newer.copy(versionCode = 4502, versionName = "0.1.0-debug.450+0000000")))
        assertTrue("the same build", stale(installed))
        assertTrue("an older build with another key", bad(installed.copy(versionCode = 4502, signers = setOf("someone-else"))))
        assertTrue("another key", bad(newer.copy(signers = setOf("someone-else"))))
        assertTrue("another key beside ours", bad(newer.copy(signers = setOf("our-key", "someone-else"))))
        assertTrue("no signature read", bad(newer.copy(signers = emptySet())))
        assertTrue("nothing Android could parse", bad(null))
    }

    @Test
    fun aKeyWhoseLineageNamesOursPassesAndNoOtherWay() {
        // CI's own debug key, rotated from the shared one that signed the installed build.
        val rotated = newer.copy(signers = setOf("ci-key"), lineage = setOf("our-key", "ci-key"))
        assertEquals(Verdict.Good, verdict(rotated, installed))
        assertTrue("a lineage that doesn't name ours", bad(rotated.copy(lineage = setOf("someone-else", "ci-key"))))
        // Once on CI's key, a build signed with the old one is refused: it can't name the newer key.
        val onCiKey = installed.copy(signers = setOf("ci-key"), lineage = setOf("our-key", "ci-key"))
        assertTrue("back to the old key", verdict(newer, onCiKey) is Verdict.Bad)
        assertTrue("someone else's rotation from the old key", verdict(newer.copy(signers = setOf("x"), lineage = setOf("our-key", "x")), onCiKey) is Verdict.Bad)
    }

    /** x86_64's code ends in 4, arm64's in 2: the same build for another ABI has a higher one. */
    @Test
    fun theSameBuildForAnotherAbiIsntNewer() {
        assertTrue(stale(installed.copy(versionCode = 4554)))
        assertTrue("no build number to compare", stale(newer.copy(versionName = "0.1.0")))
    }

    @Test
    fun aFileThatIsntAnApkHasNoFacts() {
        val junk = tmp.newFile("download.part").apply { writeText("<html>Rate limited</html>") }
        assertNull(app.packageManager.apkFacts(junk))
    }

    /** As far as a test can go: Android's side of the install needs a phone (see DELIVERY-CHECKS). */
    @Test
    fun theApkIsHandedToPackageInstallerWithItsAnswerComingBackHere() {
        val apk = tmp.newFile("build-464.apk").apply { writeText("apk bytes") }
        val installer = app.packageManager.packageInstaller

        runBlocking { ApkInstaller(app).commit(apk, 465) { false } }
        assertTrue("busy by the time it was copied: dropped", installer.allSessions.isEmpty())

        runBlocking { ApkInstaller(app).commit(apk, 464) { true } }

        val session = installer.allSessions.single()
        assertEquals(app.packageName, session.appPackageName)
        shadowOf(installer).setSessionFails(session.sessionId)
        val answer = shadowOf(app).broadcastIntents.single()
        assertEquals(InstallResultReceiver::class.java.name, answer.component?.className)
        assertEquals(464, answer.getIntExtra(InstallResultReceiver.EXTRA_BUILD, 0))
    }
}
