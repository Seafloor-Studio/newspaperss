package com.app.newspaperss.listen

import com.app.newspaperss.core.listen.ListenScript
import com.app.newspaperss.core.listen.PodcastPace
import com.app.newspaperss.core.listen.SpeechCheck
import com.app.newspaperss.data.EditionRepository
import com.app.newspaperss.settings.ListenVoice
import com.app.newspaperss.settings.PodcastVoice
import com.app.newspaperss.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Makes podcasts in Kokoro's voice from editions' books, a page at a time, newest edition first.
 * Run by [com.app.newspaperss.work.PodcastWorker] while the phone charges: stopped (unplugged, or
 * Android's time limit), it keeps what's made and carries on from there when it runs again.
 */
class PodcastMaker(
    private val editions: EditionRepository,
    private val store: PodcastStore,
    private val install: KokoroInstall,
    private val settings: SettingsStore,
    private val engine: (voice: PodcastVoice, threads: Int) -> PodcastEngine,
    private val encoder: AudioEncoder,
    /** Seconds of audio in a piece: kept that often, so a stop loses at most that much work. */
    private val pieceSeconds: Double = PIECE_SECONDS,
    /**
     * Seconds of work in a piece, whatever audio came of them. A cool phone takes about 190s over
     * [pieceSeconds]; a throttled one has taken 700s, and lost all of it when Android stopped the
     * run. This bounds what a stop costs in the time the phone actually spent.
     */
    private val pieceWorkSeconds: Double = PIECE_WORK_SECONDS,
    /**
     * The same, in the background: Android stops a background run within 10 minutes, which at
     * background speed is about 30s of audio for every [PIECE_WORK_SECONDS] of work.
     */
    private val backgroundPieceWorkSeconds: Double = BACKGROUND_PIECE_WORK_SECONDS,
    /**
     * What ended the last process, asked only when a line was in flight when it ended. Null means
     * Android doesn't say, which is below Android 11: then a death counts against the line, as it
     * did before anything could tell the difference.
     */
    private val lastExit: () -> LastExit? = { null },
    /**
     * Threads Kokoro is given for an edition. [KokoroTrial] gives two or four, to find out which
     * finishes a night; anything else here would give every edition the same.
     */
    private val threads: (editionId: Long) -> Int = { KOKORO_THREADS },
    private val now: () -> Long = System::nanoTime,
    private val log: PodcastLog = PodcastLog(null),
    /** What the voice says around an edition's recordings (see [Readings.spokenAround]), made as clips once its pages are. */
    private val spokenAround: suspend (editionId: Long) -> List<String> = { emptyList() },
) {
    /**
     * Makes every podcast asked for, until they're all finished, Kokoro is turned off, or it's
     * stopped. [secondsLeft] is how long the run has before it's stopped, or null with no limit
     * (in the foreground). True if it stopped itself short of that, with more to make.
     */
    suspend fun makeAll(secondsLeft: () -> Double? = { null }): Boolean = withContext(Dispatchers.Default) {
        // A run stopped mid-sentence goes on in native code until the sentence ends; a new run waits
        // for it, rather than loading Kokoro twice and writing the same piece.
        val asked = now()
        // A sentence that never ends would hold every run here: this, and no "Kokoro loaded", says so.
        if (kokoroLock.isLocked) log.add("Waiting for Kokoro: the last run finishing its sentence, or the speed check")
        kokoroLock.withLock {
            val waited = seconds(now() - asked)
            if (waited >= 1) log.add("Waited ${waited}s for the last run's sentence to end")
            // A piece a stopped run left keeping is in scratch, under the name this run would give it.
            stillKeeping?.let {
                if (it.isActive) log.add("Waiting for the last run to keep its piece")
                it.join()
            }
            store.dropScratch()
            // Best effort: without it, audio made before keys were noted is only made again.
            try {
                noteKeys()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add("Couldn't note what earlier audio says: ${e.javaClass.simpleName}")
            }
            Run(secondsLeft).also { makeUnderLock(it) }.outOfTime
        }
    }

    /** One run's limit, and how long its lines have taken, to tell when the next may not fit. */
    private inner class Run(private val secondsLeft: () -> Double?) {
        /** Seconds the last lines took, newest last. */
        private val recent = ArrayDeque<Double>()
        var outOfTime = false
        val background get() = secondsLeft() != null

        fun took(seconds: Double) {
            recent.addLast(seconds)
            if (recent.size > RECENT_LINES) recent.removeFirst()
        }

        /**
         * Why the next line shouldn't start, or null: it may not end before the run is stopped,
         * and a line in flight then is lost whole (once 178s, which took the run past Android's
         * limit and had it killed). Lines vary, so it keeps [BACKGROUND_RESERVE_SECONDS] in hand
         * whatever the slowest of late.
         */
        fun tooLate(): String? {
            val left = secondsLeft() ?: return null
            val slowest = recent.maxOrNull() ?: 0.0
            if (left >= maxOf(slowest, BACKGROUND_RESERVE_SECONDS)) return null
            return "${left.toInt()}s left in the background, the slowest of the last ${recent.size} lines ${slowest.toInt()}s"
        }
    }

    /**
     * Keeps this phone's pace from what a finished podcast cost, over all the runs it took, so
     * scheduled editions start as early as it needs. One run alone may be cut off (unplugged,
     * or Android's limits on background work), and is mostly a cool phone's.
     */
    private suspend fun learnPace(editionId: Long) {
        val (nanos, speech) = store.cost(editionId)
        if (speech < PodcastPace.LEARN_FROM_SECONDS) return
        settings.update { s ->
            val last = s.podcastPace ?: return@update s
            s.copy(podcastPace = PodcastPace.learn(last.toDouble(), s.podcastPaceMeasured, nanos / 1e9, speech).toFloat(), podcastPaceMeasured = true)
        }
    }

    /**
     * Notes the key of each page made before keys were, from its edition's book, so a later edition
     * can reuse it. Only where its audio covers the page's lines as they're read today: audio from
     * a page read differently then isn't passed on. A page that can't be keyed is marked so, and
     * not tried again; one edition's book failing doesn't stop the rest.
     */
    private suspend fun noteKeys() {
        for ((editionId, pages) in store.unsaid()) {
            try {
                val voice = store.voice(editionId)
                val book = voice?.let { ListenBook.open(editions, editionId) }
                if (book == null) {
                    pages.forEach { store.saidKey(editionId, it, PodcastStore.NO_KEY) }
                    continue
                }
                book.use {
                    for (page in pages) {
                        val lines = script(book, page)?.lines?.takeIf { it.isNotEmpty() && store.linesMade(editionId, page) == it.size }
                        store.saidKey(editionId, page, lines?.let { saidKey(voice, it) } ?: PodcastStore.NO_KEY)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.add("Couldn't note what edition $editionId's audio says: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Notes [page]'s key and, if another edition has made a page that says the same, takes its
     * audio: before Kokoro is loaded, so an edition whose articles are all reused never loads it.
     * True if the page is now made.
     */
    private fun reused(editionId: Long, asked: String?, page: Int, script: ListenScript?, voice: PodcastVoice): Boolean {
        if (script == null || script.lines.isEmpty() || !english(script.language)) return false
        val key = saidKey(voice, script.lines)
        store.saidKey(editionId, page, key)
        if (store.linesMade(editionId, page) > 0) return false
        val (other, otherPage) = store.madeElsewhere(key, editionId) ?: return false
        return try {
            store.reuse(other, otherPage, editionId, page, asked).also {
                if (it) log.add("Page $page: the same as edition $other page $otherPage, its audio reused")
            }
        } catch (e: IOException) {
            // A full disk, most likely: Kokoro makes the page instead, and meets the same.
            log.add("Page $page: couldn't reuse edition $other's audio (${e.javaClass.simpleName}), made instead")
            false
        }
    }

    private suspend fun makeUnderLock(run: Run) {
        // Loaded once, and again only for a podcast in another voice: loading takes seconds.
        var kokoro: Triple<PodcastVoice, Int, PodcastEngine>? = null
        fun kokoroFor(editionId: Long, voice: PodcastVoice): PodcastEngine {
            val threads = threads(editionId)
            if (kokoro?.first != voice || kokoro?.second != threads) {
                kokoro?.third?.release()
                kokoro = null
                // Loading is part of what a podcast costs: each of Android's stops means another.
                val began = now()
                kokoro = Triple(voice, threads, engine(voice, threads))
                store.addCost(editionId, now() - began, 0.0)
                log.add("Kokoro loaded (${voice.label}, $threads threads) in ${seconds(now() - began)}s")
            }
            return kokoro!!.third
        }
        try {
            while (true) {
                val editionId = store.waiting().firstOrNull()
                if (editionId == null) {
                    log.add("Nothing left to make")
                    break
                }
                if (!inUse()) {
                    log.add("Stopped: Kokoro turned off or removed")
                    break
                }
                store.making(editionId)
                val book = ListenBook.open(editions, editionId)
                if (book == null) {
                    // Its book is gone, so is the podcast's reason to be.
                    store.delete(editionId)
                    continue
                }
                val voice = store.voice(editionId) ?: continue
                val asked = store.asked(editionId)
                book.use {
                    for (page in book.pages.indices) {
                        if (store.settled(editionId, page)) continue
                        if (!inUse()) {
                            log.add("Stopped: Kokoro turned off or removed")
                            return
                        }
                        // A newer edition asked for since: it goes first, and this one carries on after.
                        if (store.waiting().firstOrNull() != editionId || !store.wanted(editionId, asked)) return@use
                        val script = script(book, page)
                        if (reused(editionId, asked, page, script, voice)) continue
                        makePage(editionId, asked, page, script, kokoroFor(editionId, voice), run)
                        if (run.outOfTime) return
                    }
                    // Best effort: without its clips, what's said around a recording is the phone's voice.
                    val around = try {
                        spokenAround(editionId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.add("Couldn't find what's said around the recordings: ${e.javaClass.simpleName}")
                        emptyList()
                    }
                    for (text in around) {
                        // One that took the app down, as a line can, is the phone's from then on.
                        if (store.clip(editionId, text) != null || store.clipTried(editionId, text)) continue
                        currentCoroutineContext().ensureActive()
                        if (!inUse() || !store.wanted(editionId, asked)) return@use
                        run.tooLate()?.let {
                            log.add("Stopped before a clip: $it")
                            run.outOfTime = true
                            return
                        }
                        makeClip(editionId, asked, text, kokoroFor(editionId, voice))
                    }
                    // Stopped during its last page: not finished, so Make the rest can carry on.
                    if (!store.wanted(editionId, asked)) return@use
                    store.finish(editionId)
                    log.add("Edition $editionId: audio finished")
                    // Failing to keep the pace mustn't stop the podcasts behind this one.
                    runCatching { learnPace(editionId) }
                }
            }
        } finally {
            store.making(null)
            kokoro?.third?.release()
        }
    }

    // Asked before each page, so turning Kokoro off or removing it stops the making within a page.
    private suspend fun inUse() = install.complete && settings.current().listenVoice == ListenVoice.PODCAST

    /** The page's script, or null if it can't be read: the phone's voice will make what it can of it. */
    private suspend fun script(book: ListenBook, page: Int): ListenScript? = try {
        book.script(page)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // An article nested deeper than the parser's stack.
        null
    }

    /** A clip of [text], in one go: a sentence or two, so nothing to keep part way. A failure leaves it to the phone's voice. */
    private fun makeClip(editionId: Long, asked: String?, text: String, kokoro: PodcastEngine) {
        val file = store.clipScratch(editionId, text)
        store.tryingClip(editionId, text, true)
        try {
            val speech = kokoro.speak(text)
            val sink = encoder.open(file, speech.sampleRate)
            try {
                // An encoder given no audio at all can't finish its file.
                sink.write(speech.samples.takeIf { it.isNotEmpty() } ?: FloatArray(speech.sampleRate / 10))
            } finally {
                sink.close()
            }
            store.keepClip(editionId, asked, text, file)
        } catch (e: Exception) {
            file.delete()
            log.add("A clip wasn't made: ${e.javaClass.name}")
        } finally {
            store.tryingClip(editionId, text, false)
        }
    }

    private suspend fun makePage(editionId: Long, asked: String?, page: Int, script: ListenScript?, kokoro: PodcastEngine, run: Run) {
        if (script == null || script.lines.isEmpty() || !english(script.language)) {
            store.leaveLive(editionId, page)
            return
        }
        val lines = script.lines
        store.lines(editionId, page, lines.size)
        val (died, deaths) = store.deaths(editionId, page)
        // The marker says which line was in flight when the process ended, not that the line
        // ended it. Android taking the app for memory doesn't make a line unsayable, and two of
        // those would otherwise hand the page to the phone's voice for good.
        val exit = if (died >= 0) lastExit() else null
        val taken = exit != null && !exit.crash
        val deadAt = if (taken) -1 else died
        val from = store.linesMade(editionId, page)
        log.add(
            "Edition $editionId page $page: from line $from of ${lines.size}" + when {
                died < 0 -> ""
                taken -> ", line $died was in flight when the app ended (${exit?.name}); not counted against it"
                else -> ", line $died took the app down $deaths times (${exit?.name ?: "Android doesn't say why"})"
            },
        )
        var piece: Piece? = null
        var next = from
        try {
            for (i in from until lines.size) {
                // Native code can't be stopped mid-sentence: between sentences is the soonest.
                currentCoroutineContext().ensureActive()
                if (!store.wanted(editionId, asked)) {
                    // Stopped by the reader: what's made is kept. Deleted, the piece is dropped,
                    // even if it's been asked for again since: that one starts afresh.
                    piece?.keep(editionId, page, asked)
                    piece = null
                    log.add("Edition $editionId stopped or deleted on page $page before line $i")
                    return
                }
                run.tooLate()?.let {
                    piece?.keep(editionId, page, asked)
                    piece = null
                    run.outOfTime = true
                    log.add("Stopped on page $page before line $i: $it")
                    return
                }
                val death = if (i == deadAt) deaths + 1 else 1
                if (death > CRASHES) throw Unsayable(IllegalStateException("Line $i took the app down $deaths times"))
                val began = now()
                store.saying(editionId, page, i, death)
                val speech = try {
                    kokoro.speak(lines[i].spoken)
                } catch (e: Exception) {
                    throw Unsayable(e)
                }
                val current = piece ?: Piece(i, store.scratch(editionId, page, i), speech.sampleRate).also { piece = it }
                if (i > 0 && lines[i - 1].block != lines[i].block) current.write(FloatArray((current.rate * PAUSE).toInt()))
                current.starts += current.seconds
                SpeechCheck.problems(speech.samples, speech.sampleRate, lines[i].spoken.length)?.let {
                    log.add("Page $page line $i, ${"%.1f".format(Locale.ROOT, current.seconds)}s into piece ${current.firstLine}: $it; Kokoro was given: ${SpeechCheck.exactly(lines[i].spoken)}")
                }
                current.write(speech.samples)
                current.spoken += speech.samples.size
                current.work += now() - began
                val took = seconds(now() - began)
                run.took((now() - began) / 1e9)
                if (took >= SLOW_LINE_SECONDS) log.add("Page $page line $i took ${took}s (${lines[i].spoken.length} characters)")
                next = i + 1
                val workBound = if (run.background) minOf(pieceWorkSeconds, backgroundPieceWorkSeconds) else pieceWorkSeconds
                if ((current.seconds >= pieceSeconds || current.workSeconds >= workBound) && i < lines.lastIndex) {
                    current.keep(editionId, page, asked)
                    piece = null
                }
            }
            piece?.keep(editionId, page, asked)
            piece = null
            // Stopped or deleted while its last line was said.
            if (!store.wanted(editionId, asked)) return
            store.complete(editionId, page)
            log.add("Page $page made")
        } catch (e: Unsayable) {
            // Something in this page Kokoro couldn't say: the phone's voice reads it, rather than
            // the podcast stopping at it every time.
            log.add("Page $page left to the phone's voice: ${e.cause?.message ?: e.cause?.javaClass?.name}")
            store.leaveLive(editionId, page)
        } catch (e: CancellationException) {
            // Every line in the piece is whole, the one in flight at the stop included: Kokoro
            // finished it before the stop could be seen.
            val stopped = piece
            piece = null
            val resumes = if (stopped == null) "resumes at line $next" else when (keepOnStop(stopped, editionId, page, asked)) {
                Kept.KEPT -> "resumes at line $next"
                Kept.FAILED -> "${stopped.seconds.toInt()}s of audio couldn't be kept, resumes at line ${stopped.firstLine}"
                Kept.LATE -> "still keeping lines ${stopped.firstLine}–${next - 1} after ${KEEP_ON_STOP_MS / 1000}s; resumes at line $next if they're kept"
            }
            log.add("Stopped on page $page before line $next; $resumes")
            throw e
        } catch (e: IOException) {
            // A full disk, most likely: tried again later, as it is.
            log.add("Page $page: ${e.javaClass.simpleName}, tried again next run")
            throw e
        } catch (e: Exception) {
            // The encoder, say, failing the same way each time: given up after a few runs, so
            // the pages after it, and older podcasts, aren't held up for good.
            val failures = store.failed(editionId, page)
            log.add("Page $page failed ($failures of $FAILURES): ${e.javaClass.name}")
            if (failures < FAILURES) throw PageFailed(e)
            log.add("Page $page left to the phone's voice")
            store.leaveLive(editionId, page)
        } finally {
            piece?.drop()
            store.said(editionId, page)
        }
    }

    private enum class Kept { KEPT, FAILED, LATE }

    /**
     * Keeps [piece] as the run is stopped, whatever the stop: closing the file and the one write
     * that keeps it can't be cut short part way. Waited for only [KEEP_ON_STOP_MS], so the run
     * ends within Android's grace after a stop; one still keeping then finishes on its own, and the
     * next run waits for it ([stillKeeping]).
     */
    private suspend fun keepOnStop(piece: Piece, editionId: Long, page: Int, asked: String?): Kept = withContext(NonCancellable) {
        val keeping = keepers.async {
            runCatching { piece.keep(editionId, page, asked) }.onFailure {
                piece.drop()
                log.add("Couldn't keep page $page lines from ${piece.firstLine} as the run stopped: ${it.javaClass.simpleName}")
            }.isSuccess
        }
        stillKeeping = keeping
        when (withTimeoutOrNull(KEEP_ON_STOP_MS) { keeping.await() }) {
            null -> Kept.LATE
            true -> Kept.KEPT
            false -> Kept.FAILED
        }
    }

    /** A piece being written: opened on its first line, kept once it's long enough or the page ends. */
    private inner class Piece(val firstLine: Int, val file: File, val rate: Int) {
        /** Time spent making this piece: Kokoro, the encoder and the files. */
        var work = 0L
        /** Samples of speech, not counting the breaths between paragraphs: what the check timed too. */
        var spoken = 0L
        private val sink = encoder.open(file, rate)
        val starts = mutableListOf<Double>()
        private var samples = 0L
        val seconds: Double get() = samples.toDouble() / rate
        val workSeconds: Double get() = work / 1e9

        fun write(audio: FloatArray) {
            sink.write(audio)
            samples += audio.size
        }

        /** Keeps it, unless the podcast was deleted (and maybe asked for again) since [asked]: then it's dropped. */
        fun keep(editionId: Long, page: Int, asked: String?) {
            if (store.asked(editionId) != asked) return drop()
            // Lines Kokoro made nothing of (a row of symbols): an encoder given no audio at all
            // can't finish its file.
            if (samples == 0L) write(FloatArray(rate / 10))
            val began = now()
            sink.close()
            store.keep(editionId, page, firstLine, file, starts)
            store.addCost(editionId, work + now() - began, spoken.toDouble() / rate)
            log.add("Kept page $page lines $firstLine–${firstLine + starts.size - 1}: ${seconds.toInt()}s of audio in ${seconds(work + now() - began)}s")
        }

        fun drop() {
            runCatching { sink.close() }
            file.delete()
        }
    }

    /** Kokoro failed on a line, as opposed to the phone (a full disk, the encoder) failing around it. */
    private class Unsayable(cause: Exception) : Exception(cause)

    /** A page failed, and counted against it: the next run tries it again, until [FAILURES]. */
    class PageFailed(cause: Exception) : Exception(cause)

    companion object {
        /** Seconds of quiet between paragraphs, as a reader takes a breath. */
        private const val PAUSE = 0.35

        const val PIECE_SECONDS = 120.0

        /** Rather over what [PIECE_SECONDS] costs a cool phone, so only a throttled one meets it first. */
        const val PIECE_WORK_SECONDS = 240.0

        /** Rather over what a phone at background speed takes for a line or two, so a stop loses at most this much work. */
        const val BACKGROUND_PIECE_WORK_SECONDS = 75.0

        /**
         * No line starts in the background with less than this left before the run is stopped:
         * at 7 of the 9 minutes. The slowest line seen took 178s, and Android kills a run still
         * going at 10.
         */
        const val BACKGROUND_RESERVE_SECONDS = 120.0

        /** Lines whose time says how long the next may take. */
        private const val RECENT_LINES = 20

        /** How long a stopped run waits for its piece to be kept: milliseconds, usually. */
        private const val KEEP_ON_STOP_MS = 5_000L

        /** Where a stopped run keeps its piece, outside the run, so it isn't cancelled with it. */
        private val keepers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The last piece kept as a run stopped, which may outlast the run. */
        @Volatile
        private var stillKeeping: Job? = null

        /** A line taking this long is logged: one too long for a run would never be made. */
        private const val SLOW_LINE_SECONDS = 30

        private fun seconds(nanos: Long) = nanos / 1_000_000_000

        /** Times saying the same line took the app down before the page is left to the phone's voice. */
        private const val CRASHES = 2

        /** Runs failing on the same page before it's left to the phone's voice. */
        internal const val FAILURES = 3

        /**
         * What a page's audio says and how: the voice, each line, and where the breath between
         * paragraphs falls. Pages with the same key have the same audio, whatever edition they're in.
         * [SAID_VERSION] goes up when what's said changes in a way the lines don't show.
         */
        fun saidKey(voice: PodcastVoice, lines: List<ListenScript.Line>): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update("$SAID_VERSION\n${voice.name}\n".toByteArray())
            lines.forEachIndexed { i, line ->
                val pause = i > 0 && lines[i - 1].block != line.block
                digest.update("${if (pause) "¶" else ""}${line.spoken}\n".toByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private const val SAID_VERSION = 1

        /** Kokoro's voices here are English ones; a page with no language is taken to be English. */
        fun english(language: String?): Boolean = language == null || Locale.forLanguageTag(language).language == "en"
    }
}
