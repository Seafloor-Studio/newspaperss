package com.app.newspaperss.listen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder

/** Where encoded audio goes, a block of samples at a time. */
interface AudioSink : Closeable {
    fun write(samples: FloatArray)
}

/** Opens an [AudioSink] writing mono audio at [sampleRate] to [file]. */
fun interface AudioEncoder {
    fun open(file: File, sampleRate: Int): AudioSink
}

/**
 * The encoder was taken from under the app, [why]: Android's resource manager reclaims codecs
 * from apps in the background when something else wants one. What it held is lost; trying again
 * later may well work, and the audio being made isn't at fault.
 */
class EncoderLost(val why: String, cause: Exception) : Exception(cause)

/**
 * Speech as AAC in an MP4 file, through Android's own encoder: every phone has one from API 16,
 * where Opus encoding needs API 29. At 32 kbit/s mono, half an hour is about 7 MB.
 */
object AacEncoder : AudioEncoder {
    private const val BITRATE = 32_000
    private const val TIMEOUT_US = 10_000L

    /** Waits of [TIMEOUT_US] in a row before giving up on a stuck encoder: 5 seconds. */
    private const val WAITS = 500

    override fun open(file: File, sampleRate: Int): AudioSink {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            lossSaid {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        val muxer = try {
            MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        return Sink(codec, muxer, sampleRate)
    }

    /**
     * Why [e] means the codec was taken from under the app, or null if it's a fault of its own.
     * A codec released under the app answers any call with IllegalStateException; the app's own
     * use of it is the same in every run, so a misuse would fail in the foreground too, and
     * [PodcastMaker] counts a page that keeps losing it as failing all the same.
     */
    internal fun lost(e: Exception): String? = when {
        e is MediaCodec.CodecException -> when {
            e.errorCode == MediaCodec.CodecException.ERROR_RECLAIMED -> "reclaimed"
            e.isTransient -> "transient"
            e.isRecoverable -> "recoverable"
            else -> null
        }
        // Only from MediaCodec itself: the muxer's and this file's own checks throw it too.
        e is IllegalStateException && e.stackTrace.firstOrNull()?.className?.startsWith(MediaCodec::class.java.name) == true -> "released"
        else -> null
    }

    private inline fun <T> lossSaid(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw lost(e)?.let { EncoderLost(it, e) } ?: e
    }

    private class Sink(private val codec: MediaCodec, private val muxer: MediaMuxer, private val rate: Int) : AudioSink {
        private val info = MediaCodec.BufferInfo()
        private var track = -1
        private var fed = 0L
        private var closed = false

        override fun write(samples: FloatArray) = lossSaid { feed(samples) }

        private fun feed(samples: FloatArray) {
            var at = 0
            var waits = 0
            while (at < samples.size) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    check(++waits <= WAITS) { "The encoder took no more" }
                    drain(false)
                    continue
                }
                waits = 0
                // PCM is in the phone's byte order; a ByteBuffer starts big-endian.
                val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
                buffer.clear()
                val n = minOf(samples.size - at, buffer.remaining() / 2)
                for (i in at until at + n) {
                    buffer.putShort((samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                }
                codec.queueInputBuffer(index, 0, n * 2, fed * 1_000_000 / rate, 0)
                fed += n
                at += n
                drain(false)
            }
        }

        /** Moves what's encoded into the file; at the end, until the encoder says it's done. */
        private fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    // An encoder that never finishes mustn't hold the work forever.
                    index == MediaCodec.INFO_TRY_AGAIN_LATER && end && ++idle > WAITS -> throw IllegalStateException("The encoder didn't finish")
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val data = codec.getOutputBuffer(index)!!
                        // The codec's own setup data is in the track's format already.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            if (track >= 0) muxer.writeSampleData(track, data, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    !end -> return
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                lossSaid { finish() }
            } finally {
                codec.release()
                muxer.release()
            }
        }

        private fun finish() {
            var index = -1
            for (i in 0 until WAITS) {
                index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) break
                drain(false)
            }
            check(index >= 0) { "The encoder took no more" }
            codec.queueInputBuffer(index, 0, 0, fed * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (track >= 0) muxer.stop()
        }
    }
}
