package com.app.newspaperss.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fetches the newest live build's APK, about 50 MB. */
internal class ApkDownload(
    private val url: () -> String,
    // Its own client: the shared one's cache would try to keep the APK. No https-to-http redirect
    // either. The debug key is committed, so the signature check only proves a file is a debug
    // build: what says it is this project's is HTTPS to github.com.
    private val client: OkHttpClient = OkHttpClient.Builder().followSslRedirects(false).readTimeout(1, TimeUnit.MINUTES).build(),
) {
    /** Writes the APK to [into], whole or not at all: any failure throws and leaves no file. */
    suspend fun fetch(into: File) = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url()).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val expected = response.body.contentLength()
                if (expected > MAX_BYTES) throw IOException("$expected bytes is more than an APK")
                var written = 0L
                into.outputStream().use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            // Stopped by WorkManager (Wi-Fi lost, the 10-minute limit): give up now,
                            // not after the rest of the file.
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_BYTES) throw IOException("more than $MAX_BYTES bytes")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                if (expected >= 0 && written != expected) throw IOException("cut short at $written of $expected bytes")
            }
        } catch (e: Throwable) {
            into.delete()
            throw e
        }
    }

    companion object {
        /** Several times the real size: past it, the answer isn't the APK. */
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
