package com.app.newspaperss.listen

import android.app.ActivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.testutil.TestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which phones are offered Kokoro at all. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class KokoroSupportTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private fun lowMemory(yes: Boolean) =
        shadowOf(app.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(yes)

    private fun why(installed: Boolean = false, sixtyFourBit: Boolean = true) =
        KokoroSupport.why(app, installed = installed, sixtyFourBit = sixtyFourBit)

    @Test
    fun aPhoneThatCanMakeAPodcastIsOfferedOne() {
        assertNull(why())
    }

    @Test
    fun aThirtyTwoBitPhoneIsToldWhyNot() {
        assertEquals("Kokoro needs a 64-bit phone, and this one is 32-bit.", why(sixtyFourBit = false))
    }

    @Test
    fun aLowMemoryPhoneIsNotOfferedIt() {
        lowMemory(true)
        assertEquals("Kokoro needs more memory than this phone has.", why())
    }

    @Test
    @Config(sdk = [28])
    fun belowAndroidTenItIsNotOfferedBecauseTheHeatCantBeRead() {
        assertTrue(why()!!.contains("Android 10"))
    }

    /**
     * The bars went up after Kokoro shipped. A phone that already downloaded it keeps it, and the
     * heat is handled by the making stopping instead.
     */
    @Test
    fun aPhoneThatAlreadyHasItKeepsIt() {
        lowMemory(true)
        assertNull(why(installed = true))
    }

    /** 32-bit is the one bar that holds even then: there is no 32-bit code to run. */
    @Test
    fun evenInstalledAThirtyTwoBitPhoneCantRunIt() {
        assertEquals(
            "Kokoro needs a 64-bit phone, and this one is 32-bit.",
            why(installed = true, sixtyFourBit = false),
        )
    }
}
