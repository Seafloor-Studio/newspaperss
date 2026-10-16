package com.app.newspaperss.testutil

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config

/**
 * Each test's app starts with nothing stored by the test before it. Run in name order, so the
 * second test reads what the first one wrote, if anything of it is left.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TestAppTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val container = app.container

    @Test fun a_storesSomething() = runBlocking {
        container.settings.update { it.copy(onboarded = true) }
        FakeTtrss(app.http)
        assertNull(container.ttrss.connect("rss.example.com/tt-rss", "reader", "secret"))
        assertNotNull(container.ttrss.savedLogin())
    }

    @Test fun b_findsNothingStored() = runBlocking {
        assertFalse("settings", container.settings.current().onboarded)
        assertNull("tt-rss account", container.ttrss.savedLogin())
    }
}
