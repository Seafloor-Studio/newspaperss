package com.app.newspaperss.core.episodes

import kotlin.math.abs

/**
 * How long an MP3 file plays, from its first bytes: many feeds give no length (Substack's don't),
 * and downloading an episode to time it would cost tens of megabytes. Podcast MP3s start with an
 * ID3 tag, often large with cover art, so this reads in two steps: [tagSize] from the first bytes
 * says where the audio starts, and [seconds] reads the first frame there.
 */
object AudioLength {
    /** Bytes read from the start of the file, and again from the end of a tag: enough for a frame and its VBR header. */
    const val HEAD = 4_096

    /** The ID3v2 tag at the start of [head], header and footer included, or 0 if there is none. */
    fun tagSize(head: ByteArray): Long {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        // Synchsafe: seven bits a byte.
        val size = (6..9).fold(0L) { acc, i -> (acc shl 7) or (head[i].toLong() and 0x7f) }
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    /**
     * Seconds of audio in an MP3 whose audio starts with [frames], [audioBytes] long from there;
     * null if [frames] holds no MPEG audio frame. A VBR header (Xing, Info or VBRI) counts the
     * frames exactly; without one the file is taken to be constant bitrate, as most podcasts are.
     */
    fun seconds(frames: ByteArray, audioBytes: Long): Double? {
        val at = firstFrame(frames) ?: return null
        val frame = Frame.at(frames, at) ?: return null
        val vbrFrames = xingFrames(frames, at, frame) ?: vbriFrames(frames, at)
        return if (vbrFrames != null) {
            vbrFrames.toDouble() * frame.samples / frame.sampleRate
        } else {
            audioBytes * 8.0 / (frame.kbps * 1000)
        }
    }

    /** Whether [audioSeconds] is a reading of [words] aloud: within 25% of them at Listen's 160 a minute. */
    fun isReading(audioSeconds: Int, words: Int): Boolean {
        if (words <= 0 || audioSeconds <= 0) return false
        val read = words / WORDS_PER_MINUTE * 60
        return abs(audioSeconds - read) <= read * MARGIN
    }

    /**
     * Whether [audioSeconds] can be the episode a transcript of [words] records: from half to
     * twice the transcript read aloud. Talk runs near reading pace (87 minutes against 99 read
     * aloud for one show, 3 h 44 min against 3 h 22 min for another); much shorter is a preview,
     * much longer a transcript of part of it.
     */
    fun isEpisodeOf(audioSeconds: Int, words: Int): Boolean {
        if (words <= 0 || audioSeconds <= 0) return false
        val read = words / WORDS_PER_MINUTE * 60
        return audioSeconds >= read / 2 && audioSeconds <= read * 2
    }

    /**
     * Audio far too short to be the episode [words] transcribe: under a third of them read aloud,
     * where [isEpisodeOf] turns away anything under half. Said from an estimate of the words, so
     * it errs short: whatever it calls a preview, Listen won't play either.
     */
    fun isPreviewOf(audioSeconds: Int, words: Int): Boolean {
        if (words <= 0 || audioSeconds <= 0) return false
        return audioSeconds < words / WORDS_PER_MINUTE * 60 / 3
    }

    /** How long [words] take read aloud, at Listen's 160 a minute. */
    fun readSeconds(words: Int): Int = (words / WORDS_PER_MINUTE * 60).toInt()

    private const val WORDS_PER_MINUTE = 160.0
    private const val MARGIN = 0.25

    /**
     * The first frame whose header is valid and is followed by another: a lone 0xFF in cover art
     * or other data can look like a header, but rarely like two in a row.
     */
    private fun firstFrame(bytes: ByteArray): Int? {
        for (i in 0 until bytes.size - 4) {
            val frame = Frame.at(bytes, i) ?: continue
            if (Frame.at(bytes, i + frame.length) != null) return i
        }
        return null
    }

    private fun xingFrames(bytes: ByteArray, at: Int, frame: Frame): Long? {
        // After the 4-byte header and the side information, whose size depends on version and channels.
        val side = if (frame.mpeg1) (if (frame.mono) 17 else 32) else (if (frame.mono) 9 else 17)
        val tag = at + 4 + side
        if (tag + 12 > bytes.size) return null
        val id = String(bytes, tag, 4, Charsets.ISO_8859_1)
        if (id != "Xing" && id != "Info") return null
        val flags = int(bytes, tag + 4)
        return if (flags and 1 != 0L) int(bytes, tag + 8).takeIf { it > 0 } else null
    }

    private fun vbriFrames(bytes: ByteArray, at: Int): Long? {
        val tag = at + 4 + 32
        if (tag + 18 > bytes.size || String(bytes, tag, 4, Charsets.ISO_8859_1) != "VBRI") return null
        return int(bytes, tag + 14).takeIf { it > 0 }
    }

    private fun int(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { acc, i -> (acc shl 8) or (bytes[at + i].toLong() and 0xff) }

    private class Frame(val mpeg1: Boolean, val layer: Int, val kbps: Int, val sampleRate: Int, val padding: Int, val mono: Boolean) {
        val samples get() = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (mpeg1) 1152 else 576
        }
        val length get() = when (layer) {
            1 -> (12 * kbps * 1000 / sampleRate + padding) * 4
            else -> samples / 8 * kbps * 1000 / sampleRate + padding
        }

        companion object {
            fun at(b: ByteArray, i: Int): Frame? {
                if (i + 4 > b.size) return null
                val b1 = b[i + 1].toInt() and 0xff
                val b2 = b[i + 2].toInt() and 0xff
                val b3 = b[i + 3].toInt() and 0xff
                if (b[i].toInt() and 0xff != 0xff || b1 and 0xe0 != 0xe0) return null
                val version = (b1 shr 3) and 3 // 0: MPEG 2.5, 2: MPEG 2, 3: MPEG 1
                val layer = when ((b1 shr 1) and 3) { 3 -> 1; 2 -> 2; 1 -> 3; else -> return null }
                if (version == 1) return null
                val bitrateIndex = b2 shr 4
                val rateIndex = (b2 shr 2) and 3
                if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
                val mpeg1 = version == 3
                val kbps = when {
                    mpeg1 && layer == 1 -> V1L1
                    mpeg1 && layer == 2 -> V1L2
                    mpeg1 -> V1L3
                    layer == 1 -> V2L1
                    else -> V2L23
                }[bitrateIndex]
                val base = intArrayOf(44_100, 48_000, 32_000)[rateIndex]
                val rate = when (version) { 3 -> base; 2 -> base / 2; else -> base / 4 }
                return Frame(mpeg1, layer, kbps, rate, (b2 shr 1) and 1, (b3 shr 6) == 3)
            }

            private val V1L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
            private val V1L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
            private val V1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
            private val V2L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
            private val V2L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        }
    }
}
