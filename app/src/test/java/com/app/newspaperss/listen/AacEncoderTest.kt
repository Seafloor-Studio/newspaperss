package com.app.newspaperss.listen

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import com.app.newspaperss.testutil.TestApp
import java.io.ByteArrayOutputStream

/** The encoder's plumbing, against Robolectric's stand-in codec: what it's fed, and that it finishes. */
@RunWith(AndroidJUnit4::class)
@Config(application = TestApp::class)
class AacEncoderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fed = ByteArrayOutputStream()

    @After fun clear() = ShadowMediaCodec.clearCodecs()

    private fun codec() = ShadowMediaCodec.addEncoder(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        ShadowMediaCodec.CodecConfig(4096, 4096) { input, output ->
            val bytes = ByteArray(input.remaining()).also { input.get(it) }
            fed.write(bytes)
            output.put(bytes)
        },
    )

    @Test
    fun speechGoesInAsSixteenBitSamplesInThePhonesByteOrderAndTheFileIsFinished() {
        codec()
        val file = tmp.newFile("page.m4a")
        // More than one input buffer's worth, so it's fed in pieces.
        AacEncoder.open(file, 24_000).use { sink ->
            sink.write(FloatArray(3_000) { 0.5f })
            sink.write(floatArrayOf(2f, -2f))
        }

        val bytes = fed.toByteArray()
        assertEquals(3_002 * 2, bytes.size)
        // 0.5 is 16383, low byte first as on every Android phone.
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x3F.toByte(), bytes[1])
        // Anything louder than full scale is held there, not wrapped round.
        assertEquals(Short.MAX_VALUE, short(bytes, 3_000))
        assertEquals((-Short.MAX_VALUE).toShort(), short(bytes, 3_001))
        assertTrue(file.exists())
    }

    @Test
    fun closingTwiceIsHarmless() {
        codec()
        val sink = AacEncoder.open(tmp.newFile("page.m4a"), 24_000)
        sink.write(FloatArray(10))
        sink.close()
        sink.close()
    }

    /**
     * Android takes a background app's codec when something else wants one, and the codec says
     * so from the next call: the sink tells that apart from the encoder failing, which counts
     * against a page.
     */
    @Test
    fun aCodecTakenBackMidWriteIsLostNotFailed() {
        var reclaim = false
        ShadowMediaCodec.addEncoder(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            ShadowMediaCodec.CodecConfig(4096, 4096) { input, output ->
                if (reclaim) throw codecError(MediaCodec.CodecException.ERROR_RECLAIMED, action = 0)
                output.put(input)
            },
        )
        val sink = AacEncoder.open(tmp.newFile("page.m4a"), 24_000)
        sink.write(FloatArray(10))
        reclaim = true

        val lost = runCatching { sink.write(FloatArray(10)) }.exceptionOrNull()
        assertTrue("$lost", lost is EncoderLost)
        assertEquals("reclaimed", (lost as EncoderLost).why)
        runCatching { sink.close() }
    }

    @Test
    fun aReclaimedOrPassingCodecErrorIsALossAndAnyOtherIsAFault() {
        assertEquals("reclaimed", AacEncoder.lost(codecError(MediaCodec.CodecException.ERROR_RECLAIMED, action = 0)))
        assertEquals("transient", AacEncoder.lost(codecError(0, action = ACTION_TRANSIENT)))
        assertEquals("recoverable", AacEncoder.lost(codecError(0, action = ACTION_RECOVERABLE)))
        assertNull("a codec error that won't pass", AacEncoder.lost(codecError(0, action = 0)))
        // A codec released under the app answers from MediaCodec itself.
        val released = IllegalStateException().apply { stackTrace = arrayOf(StackTraceElement("android.media.MediaCodec", "native_dequeueInputBuffer", null, -2)) }
        assertEquals("released", AacEncoder.lost(released))
        // The muxer's, and the sink's own checks: faults, which count.
        assertNull(AacEncoder.lost(IllegalStateException("Failed to stop the muxer")))
        assertNull(AacEncoder.lost(java.io.IOException("No space left on device")))
    }

    /** MediaCodec.CodecException's constructor is hidden: what MediaCodec's native code builds. */
    private fun codecError(code: Int, action: Int): MediaCodec.CodecException =
        MediaCodec.CodecException::class.java.getDeclaredConstructor(Int::class.java, Int::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(code, action, "codec error")

    private fun short(bytes: ByteArray, sample: Int): Short =
        ((bytes[sample * 2].toInt() and 0xFF) or (bytes[sample * 2 + 1].toInt() shl 8)).toShort()

    private companion object {
        val ACTION_TRANSIENT = hidden("ACTION_TRANSIENT")
        val ACTION_RECOVERABLE = hidden("ACTION_RECOVERABLE")

        private fun hidden(name: String) =
            MediaCodec.CodecException::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)
    }
}
