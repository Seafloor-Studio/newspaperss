package com.app.newspaperss.testutil

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.app.newspaperss.AppContainer
import com.app.newspaperss.NewspaperssApp
import com.app.newspaperss.data.AppDatabase
import com.app.newspaperss.core.listen.KokoroFile
import com.app.newspaperss.listen.KokoroDownload
import com.app.newspaperss.listen.KokoroInstall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File

/**
 * The app with an in-memory database, a fake network, a software cipher in place of the
 * Android Keystore (Robolectric has none) and no background work.
 *
 * Its DataStores are this test's own, in its files folder, and stop when Robolectric terminates
 * the app at the end of the test, before that folder is deleted.
 */
class TestApp : NewspaperssApp() {
    val http = FakeHttp()
    /** Editions [com.app.newspaperss.data.EditionRepository] asked to mark read in tt-rss. */
    val markedTtrssRead = mutableListOf<Long>()
    /** Saved links and curated-list picks whose titles the app asked to look up in the background. */
    val titlesRequested = mutableListOf<Long>()
    /** How many times the app asked for the work that moves phone feeds into tt-rss. */
    var movesRequested = 0
    /** The voice Listen reads with. */
    val speaker = FakeSpeaker()

    /**
     * Where Kokoro's download goes and what it asks for. The real voice is 325 MB from Hugging
     * Face, so a test serves a few bytes from [kokoroBaseUrl] instead; read when the download
     * starts, so a test can set it after the container is built.
     */
    var kokoroFiles: List<KokoroFile> = emptyList()
    var kokoroBaseUrl: String = "http://127.0.0.1:1"
    val kokoroDir: File by lazy { File(cacheDir, "kokoro-test").apply { mkdirs() } }
    val kokoroInstall by lazy { KokoroInstall(kokoroDir) { kokoroFiles } }

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = storeScope) { File(filesDir, "datastore/$name.preferences_pb") }

    override fun createContainer() = AppContainer(
        this,
        http = http,
        db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java).allowMainThreadQueries().build(),
        cipher = testCipher(),
        markTtrssRead = { markedTtrssRead += it },
        fetchReadingListTitles = { titlesRequested += it },
        moveFeeds = { movesRequested++ },
        speaker = { speaker },
        connectListening = {},
        kokoroInstall = kokoroInstall,
        newKokoroDownload = { install -> KokoroDownload(OkHttpClient(), install, kokoroBaseUrl) },
        // CI's own builds carry a build number: tests shouldn't depend on whether they run there.
        installedBuild = null,
        settingsData = store("settings"),
        ttrssData = store("ttrss"),
        feedMovesData = store("feed_moves"),
    )

    override fun scheduleWork() {}

    override fun onTerminate() {
        stopStores(storeScope)
        super.onTerminate()
    }
}
