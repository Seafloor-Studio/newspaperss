package com.app.newspaperss.update

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.SelfUpdater
import com.app.newspaperss.selfUpdater
import com.app.newspaperss.testutil.TestApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The debug build can install its updates; the release build's NoSelfUpdateTest checks the opposite. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class LiveBuildManifestTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    @Test
    fun aCiDebugBuildCanInstallItsUpdatesAndALocalOneDoesnt() {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
        assertTrue(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        assertTrue(info.receivers.orEmpty().any { it.name == InstallResultReceiver::class.java.name && !it.exported })
        val scope = CoroutineScope(Dispatchers.Unconfined)
        assertTrue(selfUpdater(app, installed = 455, scope, away = { false }) { false } is LiveUpdates)
        assertSame("built locally: no build number to compare", SelfUpdater.NONE, selfUpdater(app, installed = null, scope, away = { false }) { false })
    }
}
