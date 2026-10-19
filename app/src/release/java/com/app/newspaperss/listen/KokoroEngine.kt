package com.app.newspaperss.listen

import com.app.newspaperss.settings.PodcastVoice

/** Release builds don't offer Kokoro, and leave out sherpa-onnx's 30 MB of native code. */
@Suppress("UNUSED_PARAMETER")
internal fun kokoroEngine(install: KokoroInstall, voice: PodcastVoice, threads: Int = KOKORO_THREADS): PodcastEngine =
    throw UnsupportedOperationException("Release builds have no Kokoro")
