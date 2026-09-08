package app.lifeos.core.language

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceActivitySegmenterTest {
    private val segmenter = DeterministicVoiceActivitySegmenter()

    @Test
    fun silenceProducesNoSegments() {
        val audio = Pcm16MonoAudio(16_000, ShortArray(16_000))
        assertTrue(segmenter.segment(audio).isEmpty())
    }

    @Test
    fun speechIslandIsDetectedWithBoundedEdges() {
        val sampleRate = 16_000
        val samples = ShortArray(sampleRate * 2)
        val start = sampleRate / 2
        val end = start + sampleRate / 2
        for (index in start until end) {
            val value = sin(2.0 * PI * 220.0 * index.toDouble() / sampleRate.toDouble())
            samples[index] = (value * 8_000.0).toInt().toShort()
        }
        val result = segmenter.segment(Pcm16MonoAudio(sampleRate, samples))
        assertEquals(1, result.size)
        val segment = result.single()
        assertTrue(segment.startSample <= start)
        assertTrue(segment.startSample >= start - 2_000)
        assertTrue(segment.endSampleExclusive >= end - 400)
        assertTrue(segment.endSampleExclusive <= end + 800)
        assertTrue(segment.peakRms > 0.10)
    }

    @Test
    fun twoSpeechIslandsSeparatedByPauseStaySeparate() {
        val sampleRate = 16_000
        val samples = ShortArray(sampleRate * 3)
        fillTone(samples, sampleRate, 4_000, 11_000, 200.0)
        fillTone(samples, sampleRate, 22_000, 30_000, 280.0)
        val result = segmenter.segment(Pcm16MonoAudio(sampleRate, samples))
        assertEquals(2, result.size)
        assertTrue(result[0].endSampleExclusive < result[1].startSample)
    }

    @Test
    fun repeatedInputProducesIdenticalSegments() {
        val sampleRate = 16_000
        val samples = ShortArray(sampleRate * 2)
        fillTone(samples, sampleRate, 3_000, 14_000, 180.0)
        val audio = Pcm16MonoAudio(sampleRate, samples)
        assertEquals(segmenter.segment(audio), segmenter.segment(audio))
    }

    private fun fillTone(samples: ShortArray, sampleRate: Int, start: Int, end: Int, frequency: Double) {
        for (index in start until end) {
            val value = sin(2.0 * PI * frequency * index.toDouble() / sampleRate.toDouble())
            samples[index] = (value * 7_000.0).toInt().toShort()
        }
    }
}
