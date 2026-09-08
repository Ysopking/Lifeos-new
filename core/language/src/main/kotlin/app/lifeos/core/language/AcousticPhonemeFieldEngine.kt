package app.lifeos.core.language

import kotlin.math.abs
import kotlin.math.exp

/**
 * Deterministic acoustic-field reference engine. It intentionally emits a lattice of broad
 * phoneme classes rather than pretending that one 20 ms frame uniquely identifies a phoneme.
 */
class AcousticPhonemeFieldEngine(
    private val featureExtractor: DeterministicAcousticFeatureExtractor = DeterministicAcousticFeatureExtractor(),
    private val maxCandidatesPerFrame: Int = 4,
) {
    init { require(maxCandidatesPerFrame in 2..AcousticPhonemeClass.entries.size) }

    fun analyze(audio: Pcm16MonoAudio): AcousticPhonemeLattice {
        val extracted = featureExtractor.extract(audio)
        var previousCandidates: List<AcousticPhonemeCandidate> = emptyList()
        val frames = extracted.frames.map { features ->
            val candidates = candidatesFor(features, audio.sampleRateHz, previousCandidates)
            previousCandidates = candidates
            AcousticPhonemeFrame(features.frameIndex, features, candidates)
        }
        return AcousticPhonemeLattice(
            sampleRateHz = audio.sampleRateHz,
            frameLengthSamples = extracted.frameLengthSamples,
            hopLengthSamples = extracted.hopLengthSamples,
            frames = frames,
            totalEnergy = frames.sumOf { it.features.rms },
        )
    }

    private fun candidatesFor(
        features: AcousticFrameFeatures,
        sampleRateHz: Int,
        previous: List<AcousticPhonemeCandidate>,
    ): List<AcousticPhonemeCandidate> {
        if (features.rms < SILENCE_RMS) {
            return listOf(
                AcousticPhonemeCandidate(AcousticPhonemeClass.SILENCE, 1.0, 0.0, 0.0),
                AcousticPhonemeCandidate(AcousticPhonemeClass.UNKNOWN, 0.05, 1.0, 0.0),
            )
        }
        val nyquist = sampleRateHz / 2.0
        val centroid = (features.spectralCentroidHz / nyquist.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
        return PROTOTYPES.map { prototype ->
            val distance =
                abs(features.zeroCrossingRate - prototype.zeroCrossing) * 0.18 +
                abs(centroid - prototype.centroid) * 0.16 +
                abs(features.lowBandFraction - prototype.low) * 0.10 +
                abs(features.midBandFraction - prototype.mid) * 0.10 +
                abs(features.highBandFraction - prototype.high) * 0.16 +
                abs(features.voicing - prototype.voicing) * 0.30
            val continuity = continuityForce(prototype.phonemeClass, previous)
            val acoustic = exp(-4.2 * distance)
            AcousticPhonemeCandidate(
                phonemeClass = prototype.phonemeClass,
                activation = (acoustic + continuity).coerceIn(0.0, 1.0),
                acousticDistance = distance.coerceAtLeast(0.0),
                continuityForce = continuity,
            )
        }.sortedWith(
            compareByDescending<AcousticPhonemeCandidate> { it.activation }
                .thenBy { it.phonemeClass.name },
        ).take(maxCandidatesPerFrame)
    }

    private fun continuityForce(
        candidate: AcousticPhonemeClass,
        previous: List<AcousticPhonemeCandidate>,
    ): Double {
        if (previous.isEmpty()) return 0.0
        val exact = previous.firstOrNull { it.phonemeClass == candidate }
        if (exact != null) return exact.activation * 0.10
        val previousWinner = previous.first().phonemeClass
        val compatible = candidate in voicedFamily && previousWinner in voicedFamily ||
            candidate in fricativeFamily && previousWinner in fricativeFamily
        return if (compatible) previous.first().activation * 0.035 else 0.0
    }

    private data class Prototype(
        val phonemeClass: AcousticPhonemeClass,
        val zeroCrossing: Double,
        val centroid: Double,
        val low: Double,
        val mid: Double,
        val high: Double,
        val voicing: Double,
    )

    companion object {
        private const val SILENCE_RMS = 0.004
        private val voicedFamily = setOf(
            AcousticPhonemeClass.VOICED_VOWEL,
            AcousticPhonemeClass.NASAL_LIKE,
            AcousticPhonemeClass.SONORANT,
        )
        private val fricativeFamily = setOf(
            AcousticPhonemeClass.FRICATIVE_HIGH,
            AcousticPhonemeClass.FRICATIVE_BROAD,
        )
        private val PROTOTYPES = listOf(
            Prototype(AcousticPhonemeClass.VOICED_VOWEL, 0.08, 0.18, 0.45, 0.48, 0.07, 0.88),
            Prototype(AcousticPhonemeClass.NASAL_LIKE, 0.07, 0.12, 0.72, 0.25, 0.03, 0.86),
            Prototype(AcousticPhonemeClass.SONORANT, 0.13, 0.28, 0.35, 0.55, 0.10, 0.76),
            Prototype(AcousticPhonemeClass.FRICATIVE_HIGH, 0.58, 0.74, 0.05, 0.20, 0.75, 0.05),
            Prototype(AcousticPhonemeClass.FRICATIVE_BROAD, 0.43, 0.53, 0.10, 0.40, 0.50, 0.08),
            Prototype(AcousticPhonemeClass.STOP_LIKE, 0.30, 0.44, 0.20, 0.45, 0.35, 0.22),
            Prototype(AcousticPhonemeClass.UNKNOWN, 0.25, 0.40, 0.30, 0.40, 0.30, 0.40),
        )
    }
}
