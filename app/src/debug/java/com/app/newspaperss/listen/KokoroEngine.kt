package com.app.newspaperss.listen

import com.app.newspaperss.settings.PodcastVoice
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig

/** Kokoro, which only debug builds offer and carry the native code for. */
internal fun kokoroEngine(install: KokoroInstall, voice: PodcastVoice, threads: Int = KOKORO_THREADS): PodcastEngine =
    KokoroEngine(install, voice, threads)

/** Kokoro through sherpa-onnx, on [KokoroThread]. Loading takes a couple of seconds. */
class KokoroEngine(install: KokoroInstall, voice: PodcastVoice, threads: Int = KOKORO_THREADS) : PodcastEngine {
    private val speaker = speakerOf(voice)
    private val british = voice == PodcastVoice.EMMA || voice == PodcastVoice.GEORGE
    private val tts = KokoroThread.run { OfflineTts(
        null,
        OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = install.file("model.onnx").path,
                    voices = install.file("voices.bin").path,
                    tokens = install.file("tokens.txt").path,
                    dataDir = install.file("espeak-ng-data").path,
                    lexicon = install.file(if (british) "lexicon-gb-en.txt" else "lexicon-us-en.txt").path,
                    // espeak's data has no British voice; plain English with the British word list is.
                    lang = if (british) "en" else "en-us",
                ),
                numThreads = threads,
            ),
        ),
    ) }

    override fun speak(text: String): Speech = KokoroThread.run { tts.generate(text, speaker, 1f).let { Speech(it.samples, it.sampleRate) } }

    override fun release() = KokoroThread.run { tts.release() }

    companion object {
        /** The voice's number in the model (its metadata's speaker2id). */
        private fun speakerOf(voice: PodcastVoice) = when (voice) {
            PodcastVoice.HEART -> 3
            PodcastVoice.MICHAEL -> 16
            PodcastVoice.EMMA -> 21
            PodcastVoice.GEORGE -> 26
        }
    }
}
