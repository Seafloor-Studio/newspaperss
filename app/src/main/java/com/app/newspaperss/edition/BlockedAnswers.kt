package com.app.newspaperss.edition

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What sites answered the last few times they turned the app away, by the article's address: for
 * **Send for a look…**, so an issue about a blocked article says which blocker it met (see
 * [com.app.newspaperss.core.extract.BlockedAnswer]). Debug builds only; a newer answer for the
 * same address replaces the older one, and an article that goes in without one clears it.
 */
class BlockedAnswers(private val file: File, private val keep: Int = 20) {
    private val lock = Any()

    /** Keeps [answer] for [url], or forgets the last one if null (it went in without one). Never throws: it's only for a report. */
    fun record(url: String, answer: String?): Unit = synchronized(lock) {
        val old = read()
        if (answer == null && old.none { it.first == url }) return
        val entries = old.filter { it.first != url } + listOfNotNull(answer?.let { url to it })
        val json = JSONArray()
        entries.takeLast(keep).forEach { (u, a) -> json.put(JSONObject().put("url", u).put("answer", a)) }
        file.parentFile?.mkdirs()
        // Written whole and moved over the old one: a crash part way leaves the old list, not half a file.
        val next = File(file.parentFile, file.name + ".new")
        runCatching {
            next.writeText(json.toString())
            next.renameTo(file)
        }
    }

    fun find(url: String): String? = synchronized(lock) { read().lastOrNull { it.first == url }?.second }

    private fun read(): List<Pair<String, String>> = try {
        val json = JSONArray(file.readText())
        (0 until json.length()).map { json.getJSONObject(it).let { o -> o.getString("url") to o.getString("answer") } }
    } catch (e: Exception) {
        // None yet, or a file that won't read: nothing to tell.
        emptyList()
    }
}
