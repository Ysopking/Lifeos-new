package app.lifeos.core.language

import kotlin.math.abs

data class AcousticLexemeCandidate(
    val conceptId: String,
    val canonical: String,
    val semanticTag: String,
    val expectedClasses: List<AcousticPhonemeClass>,
    val acousticCompatibility: Double,
    val sentenceForce: Double,
    val photonForce: Double,
    val activation: Double,
) {
    init {
        require(conceptId.isNotBlank())
        require(canonical.isNotBlank())
        require(semanticTag.isNotBlank())
        require(expectedClasses.isNotEmpty())
        require(acousticCompatibility in 0.0..1.0)
        require(sentenceForce in 0.0..1.0)
        require(photonForce in 0.0..1.0)
        require(activation in 0.0..1.0)
    }
}

data class AcousticFrameRevision(
    val frameIndex: Int,
    val phonemeClass: AcousticPhonemeClass,
    val beforeActivation: Double,
    val afterActivation: Double,
    val lexicalForce: Double,
) {
    init {
        require(frameIndex >= 0)
        require(beforeActivation in 0.0..1.0)
        require(afterActivation in 0.0..1.0)
        require(lexicalForce in 0.0..1.0)
    }
}

data class AcousticLexicalFieldResult(
    val lexicalCandidates: List<AcousticLexemeCandidate>,
    val revisedLattice: AcousticPhonemeLattice,
    val revisions: List<AcousticFrameRevision>,
) {
    val winner: AcousticLexemeCandidate? get() = lexicalCandidates.firstOrNull()
}

/** Maps the deterministic text phonetic alphabet into broad acoustic field classes. */
class CoarsePhonemeTemplateEncoder(
    private val phoneticField: DeterministicPhoneticField = DeterministicPhoneticField(),
) {
    fun encode(word: String): List<AcousticPhonemeClass> = phoneticField.signature(word)
        .mapNotNull(::classForSymbol)
        .fold(mutableListOf()) { acc, value ->
            if (acc.lastOrNull() != value) acc += value
            acc
        }

    private fun classForSymbol(symbol: Char): AcousticPhonemeClass? = when (symbol) {
        'A' -> AcousticPhonemeClass.VOICED_VOWEL
        'N' -> AcousticPhonemeClass.NASAL_LIKE
        'L', 'R' -> AcousticPhonemeClass.SONORANT
        'S', 'X' -> AcousticPhonemeClass.FRICATIVE_HIGH
        'F' -> AcousticPhonemeClass.FRICATIVE_BROAD
        'P', 'T', 'K' -> AcousticPhonemeClass.STOP_LIKE
        else -> null
    }
}

/**
 * Bidirectional bridge between immutable acoustic observations and lexical/semantic fields.
 * Raw AcousticFrameFeatures are never modified. Only candidate activations are revised.
 */
class AcousticLexicalFieldBridge(
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val templateEncoder: CoarsePhonemeTemplateEncoder = CoarsePhonemeTemplateEncoder(),
    private val acousticWeight: Double = 0.72,
    private val sentenceWeight: Double = 0.16,
    private val photonWeight: Double = 0.16,
    private val topDownFrameWeight: Double = 0.20,
) {
    init {
        require(listOf(acousticWeight, sentenceWeight, photonWeight, topDownFrameWeight).all { it >= 0.0 && it.isFinite() })
    }

    fun resolve(
        lattice: AcousticPhonemeLattice,
        semanticField: Map<String, Double> = emptyMap(),
        context: LanguageContext = LanguageContext(),
    ): AcousticLexicalFieldResult {
        val speechFrames = lattice.frames.filter { it.winner.phonemeClass != AcousticPhonemeClass.SILENCE }
        if (speechFrames.isEmpty()) {
            return AcousticLexicalFieldResult(emptyList(), lattice, emptyList())
        }

        val candidates = lexicon.concepts.mapNotNull { concept ->
            val template = templateEncoder.encode(concept.canonical)
            if (template.isEmpty()) return@mapNotNull null
            val acoustic = acousticCompatibility(speechFrames, template)
            if (acoustic < MIN_ACOUSTIC_COMPATIBILITY) return@mapNotNull null
            val sentenceForce = semanticForce(concept, semanticField)
            val photonForce = photonForce(concept, context)
            val activation = (
                acoustic * acousticWeight +
                    sentenceForce * sentenceWeight +
                    photonForce * photonWeight
                ).coerceIn(0.0, 1.0)
            AcousticLexemeCandidate(
                conceptId = concept.id,
                canonical = concept.canonical,
                semanticTag = concept.semanticTag,
                expectedClasses = template,
                acousticCompatibility = acoustic,
                sentenceForce = sentenceForce,
                photonForce = photonForce,
                activation = activation,
            )
        }.sortedWith(
            compareByDescending<AcousticLexemeCandidate> { it.activation }
                .thenByDescending { it.acousticCompatibility }
                .thenBy { it.conceptId },
        ).take(MAX_LEXICAL_CANDIDATES)

        val activeWords = candidates.take(TOP_DOWN_WORDS).filter { it.activation >= MIN_TOP_DOWN_WORD_ACTIVATION }
        if (activeWords.isEmpty()) {
            return AcousticLexicalFieldResult(candidates, lattice, emptyList())
        }
        val revisions = mutableListOf<AcousticFrameRevision>()
        var speechIndex = 0
        val revisedFrames = lattice.frames.map { frame ->
            if (frame.winner.phonemeClass == AcousticPhonemeClass.SILENCE) return@map frame
            val forceByClass = linkedMapOf<AcousticPhonemeClass, Double>()
            activeWords.forEach { word ->
                val expected = expectedAt(word.expectedClasses, speechIndex, speechFrames.size)
                val wordForce = word.activation * topDownFrameWeight
                forceByClass[expected] = ((forceByClass[expected] ?: 0.0) + wordForce).coerceIn(0.0, 1.0)
            }
            val revisedCandidates = frame.candidates.map { candidate ->
                val lexicalForce = forceByClass[candidate.phonemeClass] ?: 0.0
                val revisedActivation = (candidate.activation + lexicalForce * (1.0 - candidate.activation)).coerceIn(0.0, 1.0)
                if (abs(revisedActivation - candidate.activation) >= REVISION_TRACE_DELTA) {
                    revisions += AcousticFrameRevision(
                        frameIndex = frame.frameIndex,
                        phonemeClass = candidate.phonemeClass,
                        beforeActivation = candidate.activation,
                        afterActivation = revisedActivation,
                        lexicalForce = lexicalForce,
                    )
                }
                candidate.copy(activation = revisedActivation)
            }.sortedWith(compareByDescending<AcousticPhonemeCandidate> { it.activation }.thenBy { it.phonemeClass.name })
            speechIndex++
            frame.copy(candidates = revisedCandidates)
        }

        return AcousticLexicalFieldResult(
            lexicalCandidates = candidates,
            revisedLattice = lattice.copy(frames = revisedFrames),
            revisions = revisions.sortedWith(compareBy<AcousticFrameRevision> { it.frameIndex }.thenBy { it.phonemeClass.name }),
        )
    }

    private fun acousticCompatibility(
        frames: List<AcousticPhonemeFrame>,
        expected: List<AcousticPhonemeClass>,
    ): Double {
        if (frames.isEmpty() || expected.isEmpty()) return 0.0
        var score = 0.0
        frames.forEachIndexed { index, frame ->
            val expectedClass = expectedAt(expected, index, frames.size)
            val activation = frame.candidates.firstOrNull { it.phonemeClass == expectedClass }?.activation ?: 0.0
            score += activation
        }
        val durationRatio = minOf(frames.size, expected.size * EXPECTED_FRAMES_PER_SYMBOL).toDouble() /
            maxOf(frames.size, expected.size * EXPECTED_FRAMES_PER_SYMBOL).toDouble()
        return ((score / frames.size.toDouble()) * 0.86 + durationRatio * 0.14).coerceIn(0.0, 1.0)
    }

    private fun expectedAt(
        expected: List<AcousticPhonemeClass>,
        frameIndex: Int,
        frameCount: Int,
    ): AcousticPhonemeClass {
        if (expected.size == 1 || frameCount <= 1) return expected.first()
        val normalized = frameIndex.toDouble() / (frameCount - 1).toDouble()
        val expectedIndex = (normalized * (expected.size - 1)).toInt().coerceIn(expected.indices)
        return expected[expectedIndex]
    }

    private fun semanticForce(concept: LinguisticConcept, semanticField: Map<String, Double>): Double {
        val direct = semanticField[concept.semanticTag] ?: 0.0
        val attracted = concept.attractsTags.maxOfOrNull { semanticField[it] ?: 0.0 } ?: 0.0
        return maxOf(direct, attracted * 0.72).coerceIn(0.0, 1.0)
    }

    private fun photonForce(concept: LinguisticConcept, context: LanguageContext): Double {
        if (context.items.isEmpty()) return 0.0
        val forms = concept.allForms + normalizeFieldText(concept.canonical) + normalizeFieldText(concept.semanticTag)
        return context.items.asSequence()
            .filter { it.active || it.confidence >= 0.65 }
            .map { item ->
                val terms = item.contentTerms.map(::normalizeFieldText).toSet()
                val direct = forms.count { it in terms }.coerceAtMost(1).toDouble()
                val related = concept.attractsTags.count { normalizeFieldText(it) in terms }.coerceAtMost(1).toDouble()
                (direct * 0.80 + related * 0.45).coerceAtMost(1.0) * item.confidence * if (item.active) 1.0 else 0.8
            }
            .maxOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
    }

    companion object {
        private const val MIN_ACOUSTIC_COMPATIBILITY = 0.16
        private const val MAX_LEXICAL_CANDIDATES = 12
        private const val TOP_DOWN_WORDS = 3
        private const val MIN_TOP_DOWN_WORD_ACTIVATION = 0.28
        private const val EXPECTED_FRAMES_PER_SYMBOL = 2
        private const val REVISION_TRACE_DELTA = 0.005
    }
}
