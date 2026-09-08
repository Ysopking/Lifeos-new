package app.lifeos.next

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.lifeos.core.language.AcousticLexemeCandidate
import app.lifeos.core.language.BidirectionalSpeechFieldEngine
import app.lifeos.core.language.DeterministicVoiceActivitySegmenter
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.Pcm16MonoAudio
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LocalVoiceCaptureResult {
    data class Success(
        val transcript: String,
        val words: List<VoiceWordHypothesis>,
        val segmentCount: Int,
        val capturedMillis: Long,
        val stoppedByLimit: Boolean,
    ) : LocalVoiceCaptureResult

    data object NoSpeech : LocalVoiceCaptureResult
    data object PermissionMissing : LocalVoiceCaptureResult
    data class Failed(val message: String) : LocalVoiceCaptureResult
}

data class VoiceWordHypothesis(
    val canonical: String,
    val semanticTag: String,
    val confidence: Double,
    val alternatives: List<Pair<String, Double>>,
)

/**
 * Foreground-only local microphone capture. No audio leaves the process and no network capability
 * is used. The raw PCM buffer is kept only for the active capture and released after analysis.
 */
class AndroidVoiceCaptureEngine(
    private val context: Context,
    private val segmenter: DeterministicVoiceActivitySegmenter = DeterministicVoiceActivitySegmenter(),
    private val speechEngine: BidirectionalSpeechFieldEngine = BidirectionalSpeechFieldEngine(),
) {
    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * Lint cannot follow the custom hasPermission() helper. The suppression is scoped to this method;
     * the runtime permission check above and SecurityException handling below remain mandatory.
     */
    @SuppressLint("MissingPermission")
    fun capture(
        stopRequested: AtomicBoolean,
        languageContext: LanguageContext,
    ): LocalVoiceCaptureResult {
        if (!hasPermission()) return LocalVoiceCaptureResult.PermissionMissing

        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBuffer <= 0) return LocalVoiceCaptureResult.Failed("AudioRecord-Puffer konnte nicht bestimmt werden.")
        val readBufferSamples = maxOf(minimumBuffer / 2, SAMPLE_RATE_HZ / 10)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer * 2, readBufferSamples * 2),
            )
        }.getOrElse {
            return LocalVoiceCaptureResult.Failed("Mikrofon konnte nicht initialisiert werden.")
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return LocalVoiceCaptureResult.Failed("Mikrofon ist auf diesem Gerät nicht initialisierbar.")
        }

        val accumulator = ShortAccumulator(MAX_CAPTURE_SAMPLES)
        var stoppedByLimit = false
        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return LocalVoiceCaptureResult.Failed("Mikrofonaufnahme konnte nicht gestartet werden.")
            }
            val readBuffer = ShortArray(readBufferSamples)
            while (!stopRequested.get() && accumulator.size < MAX_CAPTURE_SAMPLES) {
                val read = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> accumulator.append(readBuffer, read)
                    read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE ->
                        return LocalVoiceCaptureResult.Failed("Lokaler Audiostream ist abgebrochen.")
                    read == AudioRecord.ERROR_DEAD_OBJECT ->
                        return LocalVoiceCaptureResult.Failed("Android-Audiogerät wurde während der Aufnahme getrennt.")
                }
            }
            stoppedByLimit = accumulator.size >= MAX_CAPTURE_SAMPLES
            analyze(accumulator.toArray(), languageContext, stoppedByLimit)
        } catch (_: SecurityException) {
            LocalVoiceCaptureResult.PermissionMissing
        } catch (_: Exception) {
            LocalVoiceCaptureResult.Failed("Lokale Sprachaufnahme ist fehlgeschlagen.")
        } finally {
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
            accumulator.clear()
        }
    }

    private fun analyze(
        samples: ShortArray,
        languageContext: LanguageContext,
        stoppedByLimit: Boolean,
    ): LocalVoiceCaptureResult {
        if (samples.size < SAMPLE_RATE_HZ / 5) return LocalVoiceCaptureResult.NoSpeech
        val audio = Pcm16MonoAudio(SAMPLE_RATE_HZ, samples)
        val segments = segmenter.segment(audio)
        if (segments.isEmpty()) return LocalVoiceCaptureResult.NoSpeech

        val hypotheses = segments.mapNotNull { segment ->
            val result = speechEngine.understand(segment.extract(audio), context = languageContext)
            val candidates = result.lexicalField.lexicalCandidates
            val winner = candidates.firstOrNull() ?: return@mapNotNull null
            if (winner.activation < MIN_WORD_CONFIDENCE) return@mapNotNull null
            winner.toHypothesis(candidates.drop(1).take(3))
        }
        if (hypotheses.isEmpty()) return LocalVoiceCaptureResult.NoSpeech
        return LocalVoiceCaptureResult.Success(
            transcript = hypotheses.joinToString(" ") { it.canonical },
            words = hypotheses,
            segmentCount = segments.size,
            capturedMillis = samples.size.toLong() * 1000L / SAMPLE_RATE_HZ,
            stoppedByLimit = stoppedByLimit,
        )
    }

    private fun AcousticLexemeCandidate.toHypothesis(
        alternatives: List<AcousticLexemeCandidate>,
    ): VoiceWordHypothesis = VoiceWordHypothesis(
        canonical = canonical,
        semanticTag = semanticTag,
        confidence = activation,
        alternatives = alternatives.map { it.canonical to it.activation },
    )

    private class ShortAccumulator(private val capacity: Int) {
        private var data = ShortArray(minOf(capacity, 16_384))
        var size: Int = 0
            private set

        fun append(source: ShortArray, count: Int) {
            val accepted = minOf(count, capacity - size)
            if (accepted <= 0) return
            ensureCapacity(size + accepted)
            source.copyInto(data, destinationOffset = size, startIndex = 0, endIndex = accepted)
            size += accepted
        }

        fun toArray(): ShortArray = data.copyOf(size)

        fun clear() {
            data.fill(0)
            size = 0
        }

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) return
            var next = data.size
            while (next < required && next < capacity) next = minOf(capacity, next * 2)
            data = data.copyOf(next)
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_CAPTURE_SECONDS = 20
        private const val MAX_CAPTURE_SAMPLES = SAMPLE_RATE_HZ * MAX_CAPTURE_SECONDS
        private const val MIN_WORD_CONFIDENCE = 0.24
    }
}
