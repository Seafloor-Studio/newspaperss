package com.app.newspaperss.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.newspaperss.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The "Making the audio" notification's Stop: stops making audio, keeping what's made. */
class StopPodcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val pending = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.stopMakingPodcasts()
            } finally {
                pending.finish()
            }
        }
    }
}
