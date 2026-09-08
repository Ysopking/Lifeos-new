package app.lifeos.core.language

import kotlin.math.max
import kotlin.math.sqrt

data class VoiceActivityConfig(
    val frameMillis: Int = 20,
    val hopMillis: Int = 10,
    val absoluteStartRms: Double = 0.012,
    val absoluteContinueRms: Double = 0.007,
    val noiseStartMultiplier: Double = 3.0,
    val noiseContinueMultiplier: Double = 1.8,
    val minSpeechMillis: Int = 120,
    val endSilenceMillis: Int = 240,
    val preRollMillis: Int = 80,
) {
    init {
        require(frameMillis > 0)
        require(hopMillis > 0 && hopMillis <= frameMillis)
        require(absoluteStartRms > 0.0)
        require(absoluteContinueRms > 0.0)
        require(noiseStartMultiplier >= 1.0)
        require(noiseContinueMultiplier >= 1.0)
        require(minSpeechMillis >= frameMillis)
        require(endSilenceMillis >= hopMillis)
        require(preRollMillis >= 0)
    }
}

data class VoiceActivitySegment(
    val startSample: Int,
    val endSampleExclusive: Int,
    val peakRms: Double,
    val meanRms: Double,
) {
    init {
        require(startSample >= 0)
        require(endSampleExclusive > startSample)
        require(peakRms.isFinite() && peakRms >= 0.0)
        require(meanRms.isFinite() && meanRms >= 0.0)
    }

    fun extract(audio: Pcm16MonoAudio): Pcm16MonoAudio = Pcm16MonoAudio(
        sampleRateHz = audio.sampleRateHz,
        samples = audio.samples.copyOfRange(startSample, endSampleExclusive.coerceAtMost(audio.samples.size)),
    )
}

/**
 * Deterministic energy-based voice activity segmenter.
 *
 * The noise floor is learned only from frames that are currently outside a speech segment. This
 * keeps the measured PCM immutable while making the threshold adapt to a quiet or moderately noisy
 * local environment. It is intentionally conservative: uncertain low-energy audio remains audio
 * evidence but is not promoted to a speech segment.
 */
class DeterministicVoiceActivitySegmenter(
    private val config: VoiceActivityConfig = VoiceActivityConfig(),
) {
    fun segment(audio: Pcm16MonoAudio): List<VoiceActivitySegment> {
        val frameSamples = millisToSamples(config.frameMillis, audio.sampleRateHz).coerceAtLeast(1)
        val hopSamples = millisToSamples(config.hopMillis, audio.sampleRateHz).coerceAtLeast(1)
        if (audio.samples.size < frameSamples) return emptyList()

        val frames = buildList {
            var start = 0
            var index = 0
            while (start + frameSamples <= audio.samples.size) {
                add(Frame(index++, start, rms(audio.samples, start, start + frameSamples)))
                start += hopSamples
            }
        }
        if (frames.isEmpty()) return emptyList()

        var noiseFloor = initialNoiseFloor(frames)
        var activeStartFrame: Int? = null
        var lastSpeechFrame = -1
        var silenceFrames = 0
        val segments = mutableListOf<VoiceActivitySegment>()
        val endSilenceFrames = (config.endSilenceMillis / config.hopMillis).coerceAtLeast(1)
        val minSpeechFrames = (config.minSpeechMillis / config.hopMillis).coerceAtLeast(1)
        val preRollFrames = (config.preRollMillis / config.hopMillis).coerceAtLeast(0)

        frames.forEachIndexed { frameIndex, frame ->
            val startThreshold = max(config.absoluteStartRms, noiseFloor * config.noiseStartMultiplier)
            val continueThreshold = max(config.absoluteContinueRms, noiseFloor * config.noiseContinueMultiplier)
            val active = activeStartFrame != null
            val speechLike = frame.rms >= if (active) continueThreshold else startThreshold

            if (!active) {
                if (speechLike) {
                    activeStartFrame = (frameIndex - preRollFrames).coerceAtLeast(0)
                    lastSpeechFrame = frameIndex
                    silenceFrames = 0
                } else {
                    noiseFloor = updateNoiseFloor(noiseFloor, frame.rms)
                }
            } else if (speechLike) {
                lastSpeechFrame = frameIndex
                silenceFrames = 0
            } else {
                silenceFrames++
                if (silenceFrames >= endSilenceFrames) {
                    val startFrame = activeStartFrame ?: frameIndex
                    if (lastSpeechFrame - startFrame + 1 >= minSpeechFrames) {
                        segments += createSegment(audio, frames, startFrame, lastSpeechFrame, frameSamples)
                    }
                    activeStartFrame = null
                    lastSpeechFrame = -1
                    silenceFrames = 0
                    noiseFloor = updateNoiseFloor(noiseFloor, frame.rms)
                }
            }
        }

        val remainingStart = activeStartFrame
        if (remainingStart != null && lastSpeechFrame >= remainingStart &&
            lastSpeechFrame - remainingStart + 1 >= minSpeechFrames
        ) {
            segments += createSegment(audio, frames, remainingStart, lastSpeechFrame, frameSamples)
        }
        return segments
    }

    private fun createSegment(
        audio: Pcm16MonoAudio,
        frames: List<Frame>,
        startFrame: Int,
        endFrame: Int,
        frameSamples: Int,
    ): VoiceActivitySegment {
        val selected = frames.subList(startFrame, endFrame + 1)
        val startSample = selected.first().startSample
        val endSample = (selected.last().startSample + frameSamples).coerceAtMost(audio.samples.size)
        return VoiceActivitySegment(
            startSample = startSample,
            endSampleExclusive = endSample,
            peakRms = selected.maxOf { it.rms },
            meanRms = selected.map { it.rms }.average(),
        )
    }

    private fun initialNoiseFloor(frames: List<Frame>): Double {
        val quiet = frames.take(20).map { it.rms }.sorted()
        if (quiet.isEmpty()) return MIN_NOISE_FLOOR
        val median = quiet[quiet.size / 2]
        return median.coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
    }

    private fun updateNoiseFloor(current: Double, observed: Double): Double =
        (current * 0.95 + observed.coerceAtMost(MAX_NOISE_FLOOR) * 0.05)
            .coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)

    private fun rms(samples: ShortArray, start: Int, endExclusive: Int): Double {
        var sum = 0.0
        for (index in start until endExclusive) {
            val normalized = samples[index].toDouble() / Short.MAX_VALUE.toDouble()
            sum += normalized * normalized
        }
        return sqrt(sum / (endExclusive - start).toDouble())
    }

    private fun millisToSamples(millis: Int, sampleRateHz: Int): Int =
        ((sampleRateHz.toLong() * millis.toLong()) / 1000L).toInt()

    private data class Frame(val index: Int, val startSample: Int, val rms: Double)

    private companion object {
        const val MIN_NOISE_FLOOR = 0.0008
        const val MAX_NOISE_FLOOR = 0.04
    }
}
