package com.app.newspaperss.testutil

import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * DataStores for a test, each in this rule's own folder and on a scope cancelled when the test
 * ends.
 *
 * Made without a scope, a DataStore writes on one of its own that outlives the test, so a write
 * can still be running when the folder it writes to is deleted -- "Unable to rename …tmp" under
 * full-suite load. Its own folder is what lets this be one rule rather than a chain: nothing else
 * deletes it, so cancelling doesn't have to beat another rule's cleanup, and @get:Rule fields have
 * no order between them.
 */
class TestDataStores : ExternalResource() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dir: File

    override fun before() {
        dir = createTempDirectory("test-datastores").toFile()
    }

    /** A preferences store kept in [name].preferences_pb. */
    fun preferences(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file(name) }

    /** Where [preferences] keeps [name], for a test that reads the bytes it wrote. */
    fun file(name: String): File = File(dir, "$name.preferences_pb")

    override fun after() {
        stopStores(scope)
        dir.deleteRecursively()
    }
}

/**
 * Cancels the scope DataStores were made on and returns once their writes have stopped, so their
 * folder can be deleted.
 *
 * A DataStore runs an update's transform in the caller's context. If a screen's tap started that
 * update, the store's last write waits on the main thread, so blocking the main thread to join it
 * would hang; under Robolectric the main looper keeps running until the scope has finished.
 */
fun stopStores(scope: CoroutineScope) {
    val job = scope.coroutineContext[Job]!!
    job.cancel()
    val onMainLooper = runCatching { Looper.getMainLooper().thread == Thread.currentThread() }.getOrDefault(false)
    if (onMainLooper) idleUntil { job.isCompleted } else runBlocking { job.join() }
}
