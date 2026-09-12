package app.lifeos.core.language

import java.security.MessageDigest
import java.util.Locale

enum class PerceptionModality {
    TEXT,
    SPEECH,
    WRITING,
}

data class SpeechRecognitionToken(
    val segmentIndex: Int,
    val startSample: Int,
    val endSampleExclusive: Int,
    val conceptId: String,
    val canonical: String,
    val activation: Double,
    val acousticCompatibility: Double,
) {
    init {
        require(segmentIndex >= 0)
        require(startSample >= 0)
        require(endSampleExclusive > startSample)
        require(conceptId.isNotBlank())
        require(canonical.isNotBlank())
        require(activation in 0.0..1.0)
        require(acousticCompatibility in 0.0..1.0)
    }
}

data class SpeechFieldRecognitionResult(
    val tokens: List<SpeechRecognitionToken>,
    val recognizedText: String?,
    val confidence: Double,
    val traceFingerprint: String,
    val segmentCount: Int,
) {
    init {
        require(confidence in 0.0..1.0)
        require(traceFingerprint.isNotBlank())
        require(segmentCount >= 0)
        require(recognizedText == null || recognizedText.isNotBlank())
    }
}

/**
 * Productive LIFEOS speech recognizer built on the existing model-free acoustic/lexical field.
 * Raw PCM is immutable. Voice activity only defines observation windows; lexical and semantic
 * forces may revise candidate activations, never the measured acoustic features themselves.
 */
class DeterministicSpeechRecognitionEngine(
    private val segmenter: DeterministicVoiceActivitySegmenter = DeterministicVoiceActivitySegmenter(),
    private val fieldEngine: BidirectionalSpeechFieldEngine = BidirectionalSpeechFieldEngine(),
) {
    fun recognize(
        audio: Pcm16MonoAudio,
        semanticField: Map<String, Double> = emptyMap(),
        context: LanguageContext = LanguageContext(),
    ): SpeechFieldRecognitionResult {
        val detected = segmenter.segment(audio)
        val windows = if (detected.isEmpty()) {
            // Preserve uncertain audio as one evidence window instead of fabricating silence or text.
            listOf(ObservationWindow(0, audio.samples.size, audio))
        } else {
            detected.map { segment ->
                ObservationWindow(
                    startSample = segment.startSample,
                    endSampleExclusive = segment.endSampleExclusive,
                    audio = segment.extract(audio),
                )
            }
        }

        val tokens = windows.mapIndexedNotNull { index, window ->
            val field = fieldEngine.understand(window.audio, semanticField, context)
            val winner = field.lexicalField.winner ?: return@mapIndexedNotNull null
            SpeechRecognitionToken(
                segmentIndex = index,
                startSample = window.startSample,
                endSampleExclusive = window.endSampleExclusive,
                conceptId = winner.conceptId,
                canonical = winner.canonical,
                activation = winner.activation,
                acousticCompatibility = winner.acousticCompatibility,
            )
        }
        val recognized = tokens.joinToString(" ") { it.canonical }.trim().ifBlank { null }
        val confidence = if (tokens.isEmpty()) 0.0 else tokens.map { it.activation }.average().coerceIn(0.0, 1.0)
        val trace = stableFingerprint(
            buildList {
                add("speech-field/v1")
                add(audio.sampleRateHz.toString())
                add(audio.samples.size.toString())
                add(windows.size.toString())
                tokens.forEach { token ->
                    add(token.segmentIndex.toString())
                    add(token.startSample.toString())
                    add(token.endSampleExclusive.toString())
                    add(token.conceptId)
                    add(formatScore(token.activation))
                    add(formatScore(token.acousticCompatibility))
                }
            }
        )
        return SpeechFieldRecognitionResult(
            tokens = tokens,
            recognizedText = recognized,
            confidence = confidence,
            traceFingerprint = trace,
            segmentCount = windows.size,
        )
    }

    private data class ObservationWindow(
        val startSample: Int,
        val endSampleExclusive: Int,
        val audio: Pcm16MonoAudio,
    )
}

data class GraphemeCandidate(
    val value: Char,
    val confidence: Double,
) {
    init { require(confidence in 0.0..1.0) }
}

data class GraphemeObservation(
    val index: Int,
    val candidates: List<GraphemeCandidate>,
) {
    init {
        require(index >= 0)
        require(candidates.isNotEmpty())
        require(candidates.map { it.value }.distinct().size == candidates.size)
    }
}

data class GraphemeCandidateLattice(
    val observations: List<GraphemeObservation>,
) {
    init {
        require(observations.isNotEmpty())
        require(observations.map { it.index } == observations.indices.toList()) {
            "Grapheme observations must be contiguous and ordered"
        }
    }
}

data class WritingLexemeCandidate(
    val observed: String,
    val conceptId: String,
    val canonical: String,
    val visualConfidence: Double,
    val orthographicAffinity: Double,
    val phoneticAffinity: Double,
    val activation: Double,
) {
    init {
        require(observed.isNotBlank())
        require(conceptId.isNotBlank())
        require(canonical.isNotBlank())
        require(visualConfidence in 0.0..1.0)
        require(orthographicAffinity in 0.0..1.0)
        require(phoneticAffinity in 0.0..1.0)
        require(activation in 0.0..1.0)
    }
}

data class WritingFieldRecognitionResult(
    val rawWinner: String,
    val lexicalCandidates: List<WritingLexemeCandidate>,
    val recognizedText: String,
    val confidence: Double,
    val traceFingerprint: String,
) {
    init {
        require(rawWinner.isNotBlank())
        require(recognizedText.isNotBlank())
        require(confidence in 0.0..1.0)
        require(traceFingerprint.isNotBlank())
    }
}

/**
 * Deterministic writing-field resolver. A visual frontend supplies an immutable lattice of glyph
 * observations. This engine performs the LIFEOS-specific grapheme/phonetic/lexical convergence.
 * It deliberately does not claim to be a camera pixel detector; that boundary remains explicit.
 */
class WritingFieldRecognitionEngine(
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val graphemeField: GraphemeFieldEngine = GraphemeFieldEngine(),
    private val beamWidth: Int = 48,
    private val candidatesPerObservation: Int = 4,
) {
    init {
        require(beamWidth > 0)
        require(candidatesPerObservation > 0)
    }

    fun recognize(lattice: GraphemeCandidateLattice): WritingFieldRecognitionResult {
        val beams = buildBeams(lattice)
        val rawWinner = beams.first().text.trim().ifBlank { "?" }
        val lexical = beams.flatMap { beam ->
            lexicon.concepts.mapNotNull { concept ->
                val forms = (concept.allForms + concept.canonical).filter { it.isNotBlank() }
                val bestTrace = forms
                    .map { form -> graphemeField.compare(beam.text, form) }
                    .maxWithOrNull(
                        compareBy<GraphemeFieldTrace> { it.orthographicAffinity * 0.68 + it.phoneticAffinity * 0.32 }
                            .thenBy { it.expected }
                    ) ?: return@mapNotNull null
                val activation = (
                    beam.meanConfidence * VISUAL_WEIGHT +
                        bestTrace.orthographicAffinity * ORTHOGRAPHIC_WEIGHT +
                        bestTrace.phoneticAffinity * PHONETIC_WEIGHT
                    ).coerceIn(0.0, 1.0)
                if (activation < MIN_LEXICAL_ACTIVATION) return@mapNotNull null
                WritingLexemeCandidate(
                    observed = beam.text,
                    conceptId = concept.id,
                    canonical = concept.canonical,
                    visualConfidence = beam.meanConfidence,
                    orthographicAffinity = bestTrace.orthographicAffinity,
                    phoneticAffinity = bestTrace.phoneticAffinity,
                    activation = activation,
                )
            }
        }.sortedWith(
            compareByDescending<WritingLexemeCandidate> { it.activation }
                .thenByDescending { it.orthographicAffinity }
                .thenBy { it.conceptId }
                .thenBy { it.observed }
        ).distinctBy { it.conceptId }.take(MAX_LEXICAL_CANDIDATES)

        val bestLexical = lexical.firstOrNull()
        val recognized = if (bestLexical != null && bestLexical.activation >= CORRECTION_THRESHOLD) {
            bestLexical.canonical
        } else {
            rawWinner
        }
        val confidence = bestLexical?.activation ?: beams.first().meanConfidence
        val trace = stableFingerprint(
            buildList {
                add("writing-field/v1")
                lattice.observations.forEach { observation ->
                    add(observation.index.toString())
                    observation.candidates
                        .sortedWith(compareByDescending<GraphemeCandidate> { it.confidence }.thenBy { it.value })
                        .forEach { candidate ->
                            add(candidate.value.code.toString())
                            add(formatScore(candidate.confidence))
                        }
                }
                add(rawWinner)
                lexical.take(4).forEach { candidate ->
                    add(candidate.conceptId)
                    add(formatScore(candidate.activation))
                }
            }
        )
        return WritingFieldRecognitionResult(
            rawWinner = rawWinner,
            lexicalCandidates = lexical,
            recognizedText = recognized,
            confidence = confidence.coerceIn(0.0, 1.0),
            traceFingerprint = trace,
        )
    }

    private fun buildBeams(lattice: GraphemeCandidateLattice): List<Beam> {
        var beams = listOf(Beam("", 0.0, 0))
        lattice.observations.forEach { observation ->
            val alternatives = observation.candidates
                .sortedWith(compareByDescending<GraphemeCandidate> { it.confidence }.thenBy { it.value })
                .take(candidatesPerObservation)
            beams = beams.flatMap { beam ->
                alternatives.map { candidate ->
                    Beam(
                        text = beam.text + candidate.value,
                        confidenceSum = beam.confidenceSum + candidate.confidence,
                        observations = beam.observations + 1,
                    )
                }
            }.sortedWith(
                compareByDescending<Beam> { it.meanConfidence }
                    .thenBy { it.text }
            ).take(beamWidth)
        }
        return beams.ifEmpty { listOf(Beam("?", 0.0, 1)) }
    }

    private data class Beam(
        val text: String,
        val confidenceSum: Double,
        val observations: Int,
    ) {
        val meanConfidence: Double
            get() = if (observations == 0) 0.0 else (confidenceSum / observations.toDouble()).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val VISUAL_WEIGHT = 0.50
        const val ORTHOGRAPHIC_WEIGHT = 0.32
        const val PHONETIC_WEIGHT = 0.18
        const val MIN_LEXICAL_ACTIVATION = 0.24
        const val CORRECTION_THRESHOLD = 0.62
        const val MAX_LEXICAL_CANDIDATES = 12
    }
}

internal fun stablePerceptionFingerprint(parts: Iterable<String>): String = stableFingerprint(parts)

private fun stableFingerprint(parts: Iterable<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        digest.update(part.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private fun formatScore(value: Double): String = String.format(Locale.ROOT, "%.8f", value)
