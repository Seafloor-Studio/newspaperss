package com.app.newspaperss.probe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.app.newspaperss.core.net.OkHttpHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith

/** Throwaway: what NYT answers Android's network stack. Never merged. */
@RunWith(AndroidJUnit4::class)
class NytProbeTest {
    @Test
    fun probe() = run("android") { Log.i("NytProbe", it) }

    fun run(stack: String, say: (String) -> Unit) {
        val client = OkHttpHttpClient.defaultClient()
        val app = OkHttpHttpClient.USER_AGENT
        fun get(url: String, agent: String) = client.newCall(Request.Builder().url(url).header("User-Agent", agent).build()).execute()
        val feed = get("https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml", app).use { say("PROBE $stack feed ${it.code} via ${it.protocol}"); it.body.string() }
        val links = Regex("<link>(https://www\\.nytimes\\.com/20[^<]+)</link>").findAll(feed).map { it.groupValues[1] }.distinct().take(3).toList()
        for (url in links) for ((name, agent) in listOf("app" to app, "browser" to app.removeSuffix(" newspaperss"))) {
            try {
                get(url, agent).use { r ->
                    val body = r.body.string()
                    say("PROBE $stack $name ${r.code} ${r.protocol} ${url.substringAfter("nytimes.com")} bytes=${body.length}")
                    r.headers.forEach { (k, v) -> say("PROBE   $k: ${v.take(80)}") }
                    if (r.code != 200) say("PROBE   body: ${body.take(300).replace(Regex("\\s+"), " ")}")
                }
            } catch (e: Exception) {
                say("PROBE $stack $name failed ${url.substringAfter("nytimes.com")}: $e")
            }
        }
    }
}
