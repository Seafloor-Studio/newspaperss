package com.app.newspaperss.work

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.container
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.testutil.TestApp
import com.app.newspaperss.testutil.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The "Making the audio" notification's Stop. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class StopPodcastReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<TestApp>()
    private val store get() = app.container.podcastStore

    @Test
    fun theNotificationsStopStopsThePodcastBeingMadeAndThoseWaiting() {
        store.want(7, PodcastVoice.HEART)
        store.want(8, PodcastVoice.HEART)
        store.making(8)

        val stop = app.container.notifier.makingPodcast().actions.single { it.title == "Stop" }
        val sent = shadowOf(stop.actionIntent).savedIntent
        assertEquals(StopPodcastReceiver::class.java.name, sent.component?.className)
        StopPodcastReceiver().onReceive(app, Intent(sent))

        idleUntil { store.stopped(8) && store.stopped(7) }
        assertTrue(store.waiting().isEmpty())
    }
}
