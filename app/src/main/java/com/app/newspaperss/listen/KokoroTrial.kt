package com.app.newspaperss.listen

/**
 * Two threads or four, decided per edition.
 *
 * Four was chosen because it made speech fastest on a cool Pixel 8 — one and two were slower, nine
 * slower still. That measured the wrong phone. A night's making is not a cool one: a run clocked at
 * 1.6x realtime has been seen at 5.7x three hours in, and four threads that win every line can
 * still lose the night by driving the phone into that. Fewer threads finishing a page slowly may
 * beat more threads finishing none.
 *
 * The arm is a function of the edition, not of the run: a podcast is made over many runs across a
 * night, heat carries from one to the next, and switching partway through would measure neither.
 * It is also a function of the edition rather than a coin kept somewhere, so every run of the same
 * edition agrees without anything being stored, and a log read weeks later still says which arm a
 * night was.
 *
 * Only debug builds ever reach this: release builds leave out sherpa-onnx, and their
 * `kokoroEngine()` throws rather than making anything.
 */
object KokoroTrial {
    /** Threads for [editionId]: the same answer every time it is asked, scattered across editions. */
    fun threadsFor(editionId: Long): Int = if (mix(editionId + SEED) and 1L == 0L) FEWER else KOKORO_THREADS

    /**
     * Murmur3's finaliser, which scatters ids that differ by one across the whole range.
     * `java.util.Random(id).nextBoolean()` does not: its first draw barely depends on the seed's
     * low bits, so consecutive editions came out on the same arm every time — two hundred of two
     * hundred, which is a constant wearing an experiment's clothes.
     */
    private fun mix(value: Long): Long {
        var z = value
        z = (z xor (z ushr 33)) * -0xae502812aa7333L // 0xff51afd7ed558ccd
        z = (z xor (z ushr 33)) * -0x3b314601e57a13adL // 0xc4ceb9fe1a85ec53
        return z xor (z ushr 33)
    }

    /** The arm being tried against [KOKORO_THREADS]. */
    const val FEWER = 2

    /** Any constant: it only has to scatter consecutive edition ids, not be secret. */
    private const val SEED = 0x5EAF1005L
}
