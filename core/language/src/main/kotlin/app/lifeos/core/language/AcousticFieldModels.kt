package app.lifeos.core.language

/** Raw local microphone/reference audio contract for the deterministic acoustic field. */
data class Pcm16MonoAudio(
    val sampleRateHz: Int,
    val samples: ShortArray,
) {
    init {
        require(sampleRateHz in 8_000..48_000)
        require(samples.isNotEmpty())
    }
}

data class AcousticFrameFeatures(
    val frameIndex: Int,
    val startSample: Int,
    val rms: Double,
    val zeroCrossingRate: Double,
    val spectralCentroidHz: Double,
    val lowBandFraction: Double,
    val midBandFraction: Double,
    val highBandFraction: Double,
    val voicing: Double,
) {
    init {
        require(frameIndex >= 0)
        require(startSample >= 0)
        require(listOf(rms, zeroCrossingRate, spectralCentroidHz, lowBandFraction, midBandFraction, highBandFraction, voicing).all { it.isFinite() })
        require(rms >= 0.0)
        require(zeroCrossingRate in 0.0..1.0)
        require(lowBandFraction in 0.0..1.0)
        require(midBandFraction in 0.0..1.0)
        require(highBandFraction in 0.0..1.0)
        require(voicing in 0.0..1.0)
    }
}

/** Broad acoustic classes; lexical/sentence fields refine these into concrete language phonemes. */
enum class AcousticPhonemeClass {
    SILENCE,
    VOICED_VOWEL,
    NASAL_LIKE,
    SONORANT,
    FRICATIVE_HIGH,
    FRICATIVE_BROAD,
    STOP_LIKE,
    UNKNOWN,
}

data class AcousticPhonemeCandidate(
    val phonemeClass: AcousticPhonemeClass,
    val activation: Double,
    val acousticDistance: Double,
    val continuityForce: Double,
) {
    init {
        require(activation in 0.0..1.0)
        require(acousticDistance.isFinite() && acousticDistance >= 0.0)
        require(continuityForce.isFinite())
    }
}

data class AcousticPhonemeFrame(
    val frameIndex: Int,
    val features: AcousticFrameFeatures,
    val candidates: List<AcousticPhonemeCandidate>,
) {
    init {
        require(frameIndex == features.frameIndex)
        require(candidates.isNotEmpty())
    }

    val winner: AcousticPhonemeCandidate get() = candidates.first()
}

data class AcousticPhonemeLattice(
    val sampleRateHz: Int,
    val frameLengthSamples: Int,
    val hopLengthSamples: Int,
    val frames: List<AcousticPhonemeFrame>,
    val totalEnergy: Double,
) {
    init {
        require(sampleRateHz > 0)
        require(frameLengthSamples > 0)
        require(hopLengthSamples > 0)
        require(totalEnergy.isFinite() && totalEnergy >= 0.0)
    }

    fun collapsedWinnerClasses(): List<AcousticPhonemeClass> {
        val result = mutableListOf<AcousticPhonemeClass>()
        frames.forEach { frame ->
            val value = frame.winner.phonemeClass
            if (result.lastOrNull() != value) result += value
        }
        return result
    }
}
