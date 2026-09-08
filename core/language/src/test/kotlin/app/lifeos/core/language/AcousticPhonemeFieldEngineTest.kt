package app.lifeos.core.language

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcousticPhonemeFieldEngineTest {
    private val sampleRate = 16_000
    private val engine = AcousticPhonemeFieldEngine()

    @Test
    fun `silence remains explicit instead of inventing phonemes`() {
        val audio = Pcm16MonoAudio(sampleRate, ShortArray(640))
        val lattice = engine.analyze(audio)

        assertTrue(lattice.frames.isNotEmpty())
        assertTrue(lattice.frames.all { it.winner.phonemeClass == AcousticPhonemeClass.SILENCE })
        assertEquals(listOf(AcousticPhonemeClass.SILENCE), lattice.collapsedWinnerClasses())
    }

    @Test
    fun `periodic voiced signal enters voiced phoneme family`() {
        val audio = sineAudio(220.0, 1_280)
        val lattice = engine.analyze(audio)
        val voicedFamily = setOf(
            AcousticPhonemeClass.VOICED_VOWEL,
            AcousticPhonemeClass.NASAL_LIKE,
            AcousticPhonemeClass.SONORANT,
        )

        assertTrue(lattice.frames.count { it.winner.phonemeClass in voicedFamily } >= lattice.frames.size / 2)
        assertTrue(lattice.frames.map { it.features.voicing }.average() > 0.65)
    }

    @Test
    fun `broad deterministic noise attracts fricative field`() {
        var state = 0x12345678
        val samples = ShortArray(1_280) {
            state = state * 1_103_515_245 + 12_345
            ((state ushr 16).toShort().toInt() / 2).toShort()
        }
        val lattice = engine.analyze(Pcm16MonoAudio(sampleRate, samples))
        val fricatives = setOf(AcousticPhonemeClass.FRICATIVE_HIGH, AcousticPhonemeClass.FRICATIVE_BROAD)

        assertTrue(lattice.frames.any { it.winner.phonemeClass in fricatives })
        assertTrue(lattice.frames.any { it.features.highBandFraction > 0.25 })
    }

    @Test
    fun `all acoustic values stay finite and bounded`() {
        val lattice = engine.analyze(sineAudio(330.0, 960))

        lattice.frames.forEach { frame ->
            assertTrue(frame.features.rms.isFinite())
            assertTrue(frame.features.zeroCrossingRate in 0.0..1.0)
            assertTrue(frame.features.voicing in 0.0..1.0)
            assertTrue(frame.candidates.all { it.activation in 0.0..1.0 && it.acousticDistance.isFinite() })
        }
    }

    @Test
    fun `acoustic lattice is deterministic for identical pcm`() {
        val audio = sineAudio(180.0, 800)

        assertEquals(engine.analyze(audio), engine.analyze(audio))
    }

    private fun sineAudio(frequencyHz: Double, sampleCount: Int): Pcm16MonoAudio {
        val samples = ShortArray(sampleCount) { index ->
            val value = sin(2.0 * PI * frequencyHz * index / sampleRate.toDouble())
            (value * 12_000.0).toInt().toShort()
        }
        return Pcm16MonoAudio(sampleRate, samples)
    }
}
