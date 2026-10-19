package com.app.newspaperss.core.episodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLengthTest {
    private fun file(name: String) = javaClass.getResourceAsStream("/audio/$name")!!.readBytes()

    /** Times [bytes] as the app does: its first bytes, then the first bytes after its tag. */
    private fun time(bytes: ByteArray): Double? {
        val tag = AudioLength.tagSize(bytes.copyOf(AudioLength.HEAD)).toInt()
        val frames = bytes.copyOfRange(tag, minOf(bytes.size, tag + AudioLength.HEAD))
        return AudioLength.seconds(frames, bytes.size.toLong() - tag)
    }

    // The fixtures are 3 seconds of tone, made with ffmpeg; ffprobe gives the lengths compared with.
    @Test
    fun aConstantBitrateFileIsTimedByItsSize() {
        assertEquals(3.056, time(file("cbr-32k-3s.mp3"))!!, 0.05)
    }

    @Test
    fun aVariableBitrateFileIsTimedByItsFrameCount() {
        assertEquals(3.030, time(file("vbr-xing-3s.mp3"))!!, 0.05)
    }

    @Test
    fun aLargeTagIsSkippedToReachTheAudio() {
        val bytes = file("id3-40k-cbr-3s.mp3")
        assertTrue("the tag is longer than the first read", AudioLength.tagSize(bytes) > AudioLength.HEAD)
        assertEquals(3.056, time(bytes)!!, 0.05)
    }

    @Test
    fun somethingThatIsntAnMp3HasNoLength() {
        assertNull(time("<html><body>Not found</body></html>".toByteArray()))
        assertEquals(0L, AudioLength.tagSize(ByteArray(3)))
    }

    @Test
    fun aReadingIsWithinAQuarterOfItsTextReadAloud() {
        // 1,600 words at 160 a minute: ten minutes.
        assertTrue(AudioLength.isReading(600, 1_600))
        assertTrue(AudioLength.isReading(740, 1_600))
        assertFalse("an episode an hour long", AudioLength.isReading(3_600, 1_600))
        assertFalse("a preview", AudioLength.isReading(60, 1_600))
        assertFalse(AudioLength.isReading(600, 0))
    }
}
