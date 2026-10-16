package com.app.newspaperss.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * [SnackbarHostState.showSnackbar], but only while [lifecycle] is started: a message that arrives
 * while the app is in the background waits for it to come back, and one on screen when the app
 * leaves is shown again in full on its return. Otherwise its time runs out where nobody can see
 * it, and an Undo with it.
 *
 * @return how the snackbar ended, or [SnackbarResult.Dismissed] if the screen was destroyed
 *   before it could be seen to the end.
 */
suspend fun SnackbarHostState.showWhileStarted(
    lifecycle: Lifecycle,
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
): SnackbarResult {
    var result = SnackbarResult.Dismissed
    coroutineScope {
        launch {
            // The block is cancelled, taking the snackbar down, when the app leaves, and run again
            // when it comes back; it cancels itself once the snackbar has been seen to its end.
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                result = showSnackbar(message, actionLabel, duration = duration)
                this@launch.cancel()
            }
        }
    }
    return result
}
