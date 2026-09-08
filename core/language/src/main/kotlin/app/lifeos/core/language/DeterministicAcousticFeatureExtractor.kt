package app.lifeos.core.language

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure JVM reference frontend: PCM16 -> deterministic short-time acoustic features. */
class DeterministicAcousticFeatureExtractor(
    private val frameDurationMs: Int = 20,
    private val hopDurationMs: Int = 10,
) {
    init {
        require(frameDurationMs in 10..40)
        require(hopDurationMs in 5..frameDurationMs)
    }

    data class Extraction(
        val frameLengthSamples: Int,
        val hopLengthSamples: Int,
        val frames: List<AcousticFrameFeatures>,
    )

    fun extract(audio: Pcm16MonoAudio): Extraction {
        val frameLength = max(16, audio.sampleRateHz * frameDurationMs / 1_000)
        val hopLength = max(8, audio.sampleRateHz * hopDurationMs / 1_000)
        val starts = buildList {
            var start = 0
            while (start < audio.samples.size) {
                add(start)
                if (start + frameLength >= audio.samples.size) break
                start += hopLength
            }
        }
        val frames = starts.mapIndexed { frameIndex, start ->
            analyzeFrame(audio, frameIndex, start, frameLength)
        }
        return Extraction(frameLength, hopLength, frames)
    }

    private fun analyzeFrame(
        audio: Pcm16MonoAudio,
        frameIndex: Int,
        start: Int,
        frameLength: Int,
    ): AcousticFrameFeatures {
        val windowed = DoubleArray(frameLength)
        var squareSum = 0.0
        var crossings = 0
        var previous = 0.0
        for (i in 0 until frameLength) {
            val sampleIndex = start + i
            val raw = if (sampleIndex < audio.samples.size) audio.samples[sampleIndex].toDouble() / 32768.0 else 0.0
            squareSum += raw * raw
            if (i > 0 && ((raw >= 0.0) != (previous >= 0.0))) crossings++
            previous = raw
            val hann = if (frameLength == 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * i / (frameLength - 1).toDouble())
            windowed[i] = raw * hann
        }
        val rms = sqrt(squareSum / frameLength.toDouble())
        val zeroCrossing = crossings.toDouble() / (frameLength - 1).coerceAtLeast(1).toDouble()

        var weightedFrequency = 0.0
        var spectralPower = 0.0
        var lowPower = 0.0
        var midPower = 0.0
        var highPower = 0.0
        val maxBin = frameLength / 2
        for (bin in 1..maxBin) {
            var real = 0.0
            var imaginary = 0.0
            val angular = 2.0 * PI * bin / frameLength.toDouble()
            for (n in windowed.indices) {
                val angle = angular * n
                val sample = windowed[n]
                real += sample * cos(angle)
                imaginary -= sample * sin(angle)
            }
            val power = real * real + imaginary * imaginary
            val frequency = bin.toDouble() * audio.sampleRateHz / frameLength.toDouble()
            spectralPower += power
            weightedFrequency += frequency * power
            when {
                frequency < 500.0 -> lowPower += power
                frequency < 2_000.0 -> midPower += power
                else -> highPower += power
            }
        }
        val safePower = spectralPower.coerceAtLeast(1e-18)
        val centroid = weightedFrequency / safePower
        val lowFraction = (lowPower / safePower).coerceIn(0.0, 1.0)
        val midFraction = (midPower / safePower).coerceIn(0.0, 1.0)
        val highFraction = (highPower / safePower).coerceIn(0.0, 1.0)
        val voicing = estimateVoicing(windowed, audio.sampleRateHz, rms)

        return AcousticFrameFeatures(
            frameIndex = frameIndex,
            startSample = start,
            rms = rms,
            zeroCrossingRate = zeroCrossing,
            spectralCentroidHz = centroid.coerceAtLeast(0.0),
            lowBandFraction = lowFraction,
            midBandFraction = midFraction,
            highBandFraction = highFraction,
            voicing = voicing,
        )
    }

    private fun estimateVoicing(samples: DoubleArray, sampleRateHz: Int, rms: Double): Double {
        if (rms < SILENCE_RMS) return 0.0
        val minLag = (sampleRateHz / 400).coerceAtLeast(1)
        val maxLag = (sampleRateHz / 80).coerceAtMost(samples.size - 2)
        if (maxLag <= minLag) return 0.0
        var best = 0.0
        for (lag in minLag..maxLag) {
            var cross = 0.0
            var energyA = 0.0
            var energyB = 0.0
            for (i in 0 until samples.size - lag) {
                val a = samples[i]
                val b = samples[i + lag]
                cross += a * b
                energyA += a * a
                energyB += b * b
            }
            val denominator = sqrt(energyA * energyB).coerceAtLeast(1e-18)
            best = max(best, cross / denominator)
        }
        return best.coerceIn(0.0, 1.0)
    }

    companion object {
        private const val SILENCE_RMS = 0.003
    }
}
