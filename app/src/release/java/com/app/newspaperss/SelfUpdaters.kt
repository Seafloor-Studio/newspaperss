package com.app.newspaperss

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Release builds never update themselves: stores and F-Droid do that. */
@Suppress("UNUSED_PARAMETER")
internal fun selfUpdater(context: Context, installed: Int?, scope: CoroutineScope, listenPlaying: () -> Boolean): SelfUpdater = SelfUpdater.NONE
