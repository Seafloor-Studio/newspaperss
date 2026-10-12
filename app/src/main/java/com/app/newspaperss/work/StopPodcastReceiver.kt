package com.app.newspaperss.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.app.newspaperss.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The "Making the podcast" notification's Stop: stops that edition's podcast, keeping what's made. */
class StopPodcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val pending = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.stopMakingPodcast()
            } finally {
                pending.finish()
            }
        }
    }
}
