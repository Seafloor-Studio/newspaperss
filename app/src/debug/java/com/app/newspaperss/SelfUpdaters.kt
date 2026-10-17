package com.app.newspaperss

import android.content.Context
import com.app.newspaperss.update.LiveUpdates
import kotlinx.coroutines.CoroutineScope

/**
 * A debug build from CI keeps itself current. One built locally has no build number to compare,
 * so it never does, as it never asks GitHub.
 */
internal fun selfUpdater(context: Context, installed: Int?, scope: CoroutineScope, listenPlaying: () -> Boolean): SelfUpdater =
    if (installed == null) SelfUpdater.NONE else LiveUpdates(context.applicationContext, installed, scope, listenPlaying)
