package com.app.newspaperss.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What Android itself says of an APK signed with a key rotated from the shared debug key, as CI
 * signs published debug builds: the updater lets it in only by its lineage, and only a real
 * Android parses one. The asset is EPUB sink's APK signed that way with a throwaway key:
 * `apksigner rotate` from app/debug.keystore, then `apksigner sign --next-signer … --lineage …
 * --rotation-min-sdk-version 28`.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class RotatedKeyTest {
    private val sharedDebugKey = "2013e64d5fcee3f9738a097f809ae3965e94aca6471cbd2a10bdc8e76974d5b5"
    private val rotatedTo = "e5338e8630c49ec921f91e1b32f6b9b95a9e8af953f304401e6d10aa856189a2"

    @Test
    fun aRotatedApkIsSignedByTheNewKeyAndNamesTheSharedOneInItsLineage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val apk = File(target.cacheDir, "rotated.apk")
        instrumentation.context.assets.open("rotated-from-debug-key.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }

        val facts = target.packageManager.apkFacts(apk)!!

        assertEquals(setOf(rotatedTo), facts.signers)
        assertEquals(setOf(sharedDebugKey, rotatedTo), facts.lineage)
        // As the updater meets it from a build on the shared key: its key, if not its version, passes.
        val onSharedKey = facts.copy(versionCode = facts.versionCode - 1, versionName = null, signers = setOf(sharedDebugKey), lineage = setOf(sharedDebugKey))
        assertTrue("${verdict(facts, onSharedKey)}", verdict(facts, onSharedKey) !is Verdict.Bad)
    }
}
