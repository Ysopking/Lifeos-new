package app.lifeos.core.runtime.life

import app.lifeos.core.language.DeterministicLinguisticFieldLexicon
import app.lifeos.core.language.GraphemeFieldEngine
import app.lifeos.core.model.StableCognitiveIds

data class WritingFieldResolution(
    val rawText: String,
    val recognizedText: String,
    val confidence: Double,
    val lexicalCandidates: List<PerceptionCandidate>,
    val traceFingerprint: String,
) {
    init {
        require(rawText.isNotBlank())
        require(recognizedText.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(traceFingerprint.isNotBlank())
    }
}

/**
 * LIFEOS-native writing resolver. The visual frontend supplies immutable grapheme distributions;
 * this layer only applies the existing deterministic grapheme/phonetic/lexical fields.
 */
class WritingFieldPerceptionResolver(
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val graphemeField: GraphemeFieldEngine = GraphemeFieldEngine(),
    private val beamWidth: Int = 48,
    private val candidatesPerGrapheme: Int = 4,
) {
    init {
        require(beamWidth > 0)
        require(candidatesPerGrapheme > 0)
    }

    fun resolve(observation: WritingObservation): WritingFieldResolution {
        val beams = buildBeams(observation)
        val raw = beams.first().text.trim().ifBlank { "?" }
        val lexical = beams.flatMap { beam ->
            lexicon.concepts.mapNotNull { concept ->
                val best = concept.allForms
                    .map { form -> graphemeField.compare(beam.text, form) }
                    .maxWithOrNull(
                        compareBy<app.lifeos.core.language.GraphemeFieldTrace> {
                            it.orthographicAffinity * ORTHOGRAPHIC_BLEND + it.phoneticAffinity * PHONETIC_BLEND
                        }.thenBy { it.expected }
                    ) ?: return@mapNotNull null
                val activation = (
                    beam.meanConfidence * VISUAL_WEIGHT +
                        best.orthographicAffinity * ORTHOGRAPHIC_WEIGHT +
                        best.phoneticAffinity * PHONETIC_WEIGHT
                    ).coerceIn(0.0, 1.0)
                if (activation < MIN_LEXICAL_ACTIVATION) return@mapNotNull null
                PerceptionCandidate(
                    value = concept.canonical,
                    confidence = activation,
                    semanticTag = concept.semanticTag,
                )
            }
        }.sortedWith(
            compareByDescending<PerceptionCandidate> { it.confidence }
                .thenBy { it.value }
                .thenBy { it.semanticTag.orEmpty() }
        ).distinctBy { it.value to it.semanticTag }
            .take(MAX_LEXICAL_CANDIDATES)

        val best = lexical.firstOrNull()
        val recognized = if (best != null && best.confidence >= CORRECTION_THRESHOLD) best.value else raw
        val confidence = (best?.confidence ?: beams.first().meanConfidence).coerceIn(0.0, 1.0)
        val trace = StableCognitiveIds.fingerprint(
            "writing-field-perception/v1",
            observation.toSignal().fingerprint,
            raw,
            recognized,
            java.lang.Double.toHexString(confidence),
            *lexical.flatMap { candidate ->
                listOf(
                    candidate.value,
                    candidate.semanticTag.orEmpty(),
                    java.lang.Double.toHexString(candidate.confidence),
                )
            }.toTypedArray(),
        )
        return WritingFieldResolution(raw, recognized, confidence, lexical, trace)
    }

    private fun buildBeams(observation: WritingObservation): List<Beam> {
        var beams = listOf(Beam("", 0.0, 0))
        observation.graphemes.forEach { grapheme ->
            val alternatives = grapheme.canonicalCandidates.take(candidatesPerGrapheme)
            beams = beams.flatMap { beam ->
                alternatives.map { candidate ->
                    Beam(
                        text = beam.text + candidate.value,
                        confidenceSum = beam.confidenceSum + candidate.confidence,
                        observations = beam.observations + 1,
                    )
                }
            }.sortedWith(
                compareByDescending<Beam> { it.meanConfidence }.thenBy { it.text }
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
            get() = if (observations == 0) 0.0
            else (confidenceSum / observations.toDouble()).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val VISUAL_WEIGHT = 0.50
        const val ORTHOGRAPHIC_WEIGHT = 0.32
        const val PHONETIC_WEIGHT = 0.18
        const val ORTHOGRAPHIC_BLEND = 0.68
        const val PHONETIC_BLEND = 0.32
        const val MIN_LEXICAL_ACTIVATION = 0.24
        const val CORRECTION_THRESHOLD = 0.62
        const val MAX_LEXICAL_CANDIDATES = 12
    }
}
