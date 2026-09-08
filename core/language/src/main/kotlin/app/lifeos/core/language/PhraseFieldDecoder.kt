package app.lifeos.core.language

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A lexical span placed over immutable speech frames. */
data class PhraseWordCandidate(
    val conceptId: String,
    val canonical: String,
    val semanticTag: String,
    val startSpeechFrame: Int,
    val endSpeechFrameExclusive: Int,
    val startFrameIndex: Int,
    val endFrameIndexExclusive: Int,
    val acousticCompatibility: Double,
    val durationCompatibility: Double,
    val semanticForce: Double,
    val photonForce: Double,
    val boundaryEvidence: Double,
    val activation: Double,
) {
    init {
        require(conceptId.isNotBlank())
        require(canonical.isNotBlank())
        require(semanticTag.isNotBlank())
        require(startSpeechFrame >= 0)
        require(endSpeechFrameExclusive > startSpeechFrame)
        require(startFrameIndex >= 0)
        require(endFrameIndexExclusive > startFrameIndex)
        require(
            listOf(
                acousticCompatibility,
                durationCompatibility,
                semanticForce,
                photonForce,
                boundaryEvidence,
                activation,
            ).all { it in 0.0..1.0 },
        )
    }

    val speechFrameCount: Int get() = endSpeechFrameExclusive - startSpeechFrame
}

data class PhraseHypothesis(
    val words: List<PhraseWordCandidate>,
    val unresolvedSpeechFrames: Int,
    val coverage: Double,
    val coherence: Double,
    val activation: Double,
) {
    init {
        require(words.isNotEmpty())
        require(unresolvedSpeechFrames >= 0)
        require(coverage in 0.0..1.0)
        require(coherence in 0.0..1.0)
        require(activation in 0.0..1.0)
        words.zipWithNext().forEach { (left, right) ->
            require(left.endSpeechFrameExclusive <= right.startSpeechFrame)
        }
    }

    val transcript: String get() = words.joinToString(" ") { it.canonical }
}

data class PhraseFrameRevision(
    val frameIndex: Int,
    val wordIndex: Int,
    val conceptId: String,
    val phonemeClass: AcousticPhonemeClass,
    val beforeActivation: Double,
    val afterActivation: Double,
    val lexicalForce: Double,
) {
    init {
        require(frameIndex >= 0)
        require(wordIndex >= 0)
        require(conceptId.isNotBlank())
        require(beforeActivation in 0.0..1.0)
        require(afterActivation in 0.0..1.0)
        require(lexicalForce in 0.0..1.0)
    }
}

data class PhraseFieldResult(
    val rawLattice: AcousticPhonemeLattice,
    val revisedLattice: AcousticPhonemeLattice,
    val hypotheses: List<PhraseHypothesis>,
    val revisions: List<PhraseFrameRevision>,
) {
    init {
        require(rawLattice.sampleRateHz == revisedLattice.sampleRateHz)
        require(rawLattice.frames.size == revisedLattice.frames.size)
    }

    val winner: PhraseHypothesis? get() = hypotheses.firstOrNull()

    /** Alternative lexical readings that overlap the same acoustic region. */
    fun alternativesFor(word: PhraseWordCandidate, limit: Int = 3): List<Pair<String, Double>> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        return hypotheses.asSequence()
            .flatMap { it.words.asSequence() }
            .filter { candidate ->
                candidate.conceptId != word.conceptId &&
                    candidate.startSpeechFrame < word.endSpeechFrameExclusive &&
                    word.startSpeechFrame < candidate.endSpeechFrameExclusive
            }
            .groupBy { it.conceptId }
            .values
            .map { group -> group.maxBy { it.activation } }
            .sortedWith(compareByDescending<PhraseWordCandidate> { it.activation }.thenBy { it.conceptId })
            .take(limit)
            .map { it.canonical to it.activation }
    }
}

/**
 * Deterministic beam decoder over broad acoustic fields.
 *
 * It does not mutate microphone evidence. Raw acoustic features and their original lattice remain
 * available in [PhraseFieldResult.rawLattice]. Sentence/Photon context only changes the separate
 * interpretation lattice returned as [PhraseFieldResult.revisedLattice].
 */
class PhraseFieldDecoder(
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val templateEncoder: CoarsePhonemeTemplateEncoder = CoarsePhonemeTemplateEncoder(),
    private val beamWidth: Int = 12,
    private val maxWords: Int = 16,
) {
    init {
        require(beamWidth in 2..64)
        require(maxWords in 1..64)
    }

    fun decode(
        lattice: AcousticPhonemeLattice,
        semanticField: Map<String, Double> = emptyMap(),
        context: LanguageContext = LanguageContext(),
    ): PhraseFieldResult {
        val speechFrames = lattice.frames.filter { it.winner.phonemeClass != AcousticPhonemeClass.SILENCE }
        if (speechFrames.isEmpty()) {
            return PhraseFieldResult(lattice, lattice, emptyList(), emptyList())
        }

        val candidateCache = mutableMapOf<Int, List<PhraseWordCandidate>>()
        fun candidatesAt(position: Int): List<PhraseWordCandidate> = candidateCache.getOrPut(position) {
            buildCandidatesAt(position, speechFrames, semanticField, context)
        }

        val beams = Array(speechFrames.size + 1) { mutableListOf<BeamState>() }
        beams[0] += BeamState(position = 0)
        for (position in speechFrames.indices) {
            if (beams[position].isEmpty()) continue
            val activeStates = beams[position]
                .sortedByDescending(BeamState::priority)
                .take(beamWidth)
            activeStates.forEach { state ->
                offer(
                    beams[position + 1],
                    state.copy(
                        position = position + 1,
                        skippedFrames = state.skippedFrames + 1,
                    ),
                )
                if (state.words.size < maxWords) {
                    candidatesAt(position).forEach { word ->
                        val pairForce = state.words.lastOrNull()?.let { pairCoherence(it, word) } ?: 0.0
                        offer(
                            beams[word.endSpeechFrameExclusive],
                            state.copy(
                                position = word.endSpeechFrameExclusive,
                                words = state.words + word,
                                coveredFrames = state.coveredFrames + word.speechFrameCount,
                                wordActivationSum = state.wordActivationSum + word.activation,
                                coherenceSum = state.coherenceSum + pairForce,
                                coherenceEdges = state.coherenceEdges + if (state.words.isEmpty()) 0 else 1,
                            ),
                        )
                    }
                }
            }
        }

        val hypotheses = beams[speechFrames.size]
            .asSequence()
            .filter { it.words.isNotEmpty() }
            .map { it.toHypothesis(speechFrames.size) }
            .sortedWith(
                compareByDescending<PhraseHypothesis> { it.activation }
                    .thenByDescending { it.coverage }
                    .thenBy { it.transcript },
            )
            .distinctBy { it.transcript }
            .take(MAX_HYPOTHESES)
            .toList()

        val winner = hypotheses.firstOrNull()
            ?: return PhraseFieldResult(lattice, lattice, hypotheses, emptyList())
        val feedback = applyPhraseFeedback(lattice, speechFrames, winner)
        return PhraseFieldResult(
            rawLattice = lattice,
            revisedLattice = feedback.first,
            hypotheses = hypotheses,
            revisions = feedback.second,
        )
    }

    private fun buildCandidatesAt(
        start: Int,
        speechFrames: List<AcousticPhonemeFrame>,
        semanticField: Map<String, Double>,
        context: LanguageContext,
    ): List<PhraseWordCandidate> {
        val remaining = speechFrames.size - start
        if (remaining <= 0) return emptyList()
        return lexicon.concepts.asSequence()
            .flatMap { concept ->
                val expected = templateEncoder.encode(concept.canonical)
                if (expected.isEmpty()) return@flatMap emptySequence()
                candidateLengths(expected.size, remaining).asSequence().mapNotNull { length ->
                    val end = start + length
                    val acoustic = acousticCompatibility(speechFrames, start, end, expected)
                    if (acoustic < MIN_ACOUSTIC_COMPATIBILITY) return@mapNotNull null
                    val targetLength = max(MIN_WORD_FRAMES, expected.size * FRAMES_PER_TEMPLATE_CLASS)
                    val duration = durationCompatibility(length, targetLength)
                    val semantic = semanticForce(concept, semanticField)
                    val photon = photonForce(concept, context)
                    val boundary = boundaryEvidence(speechFrames, start, end)
                    val activation = (
                        acoustic * ACOUSTIC_WEIGHT +
                            duration * DURATION_WEIGHT +
                            semantic * SEMANTIC_WEIGHT +
                            photon * PHOTON_WEIGHT +
                            boundary * BOUNDARY_WEIGHT
                        ).coerceIn(0.0, 1.0)
                    if (activation < MIN_WORD_ACTIVATION) return@mapNotNull null
                    PhraseWordCandidate(
                        conceptId = concept.id,
                        canonical = concept.canonical,
                        semanticTag = concept.semanticTag,
                        startSpeechFrame = start,
                        endSpeechFrameExclusive = end,
                        startFrameIndex = speechFrames[start].frameIndex,
                        endFrameIndexExclusive = speechFrames[end - 1].frameIndex + 1,
                        acousticCompatibility = acoustic,
                        durationCompatibility = duration,
                        semanticForce = semantic,
                        photonForce = photon,
                        boundaryEvidence = boundary,
                        activation = activation,
                    )
                }.sortedByDescending { it.activation }.take(MAX_SPANS_PER_CONCEPT)
            }
            .sortedWith(
                compareByDescending<PhraseWordCandidate> { it.activation }
                    .thenByDescending { it.acousticCompatibility }
                    .thenBy { it.conceptId },
            )
            .take(MAX_CANDIDATES_PER_START)
            .toList()
    }

    private fun candidateLengths(templateSize: Int, remaining: Int): List<Int> {
        val target = max(MIN_WORD_FRAMES, templateSize * FRAMES_PER_TEMPLATE_CLASS)
        return (LENGTH_SCALES.map { scale -> (target * scale).roundToInt() } + target + minOf(target, remaining))
            .map { it.coerceIn(MIN_WORD_FRAMES, minOf(MAX_WORD_FRAMES, remaining).coerceAtLeast(MIN_WORD_FRAMES)) }
            .filter { it <= remaining }
            .distinct()
            .sorted()
    }

    private fun acousticCompatibility(
        frames: List<AcousticPhonemeFrame>,
        start: Int,
        endExclusive: Int,
        expected: List<AcousticPhonemeClass>,
    ): Double {
        val length = endExclusive - start
        if (length <= 0 || expected.isEmpty()) return 0.0
        var score = 0.0
        for (offset in 0 until length) {
            val expectedClass = expectedAt(expected, offset, length)
            val frame = frames[start + offset]
            score += frame.candidates.firstOrNull { it.phonemeClass == expectedClass }?.activation ?: 0.0
        }
        return (score / length.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun durationCompatibility(actual: Int, target: Int): Double =
        minOf(actual, target).toDouble() / maxOf(actual, target).toDouble()

    private fun expectedAt(
        expected: List<AcousticPhonemeClass>,
        offset: Int,
        spanLength: Int,
    ): AcousticPhonemeClass {
        if (expected.size == 1 || spanLength <= 1) return expected.first()
        val normalized = offset.toDouble() / (spanLength - 1).toDouble()
        val index = (normalized * (expected.size - 1)).roundToInt().coerceIn(expected.indices)
        return expected[index]
    }

    private fun boundaryEvidence(
        frames: List<AcousticPhonemeFrame>,
        start: Int,
        endExclusive: Int,
    ): Double {
        val startEvidence = when {
            start == 0 -> 1.0
            frames[start].frameIndex - frames[start - 1].frameIndex > 1 -> 1.0
            frames[start].winner.phonemeClass != frames[start - 1].winner.phonemeClass -> 0.35
            else -> 0.08
        }
        val endEvidence = when {
            endExclusive == frames.size -> 1.0
            frames[endExclusive].frameIndex - frames[endExclusive - 1].frameIndex > 1 -> 1.0
            frames[endExclusive].winner.phonemeClass != frames[endExclusive - 1].winner.phonemeClass -> 0.35
            else -> 0.08
        }
        return ((startEvidence + endEvidence) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun semanticForce(concept: LinguisticConcept, field: Map<String, Double>): Double {
        val direct = field[concept.semanticTag] ?: 0.0
        val related = concept.attractsTags.maxOfOrNull { field[it] ?: 0.0 } ?: 0.0
        return maxOf(direct, related * 0.72).coerceIn(0.0, 1.0)
    }

    private fun photonForce(concept: LinguisticConcept, context: LanguageContext): Double {
        if (context.items.isEmpty()) return 0.0
        val forms = concept.allForms + normalizeFieldText(concept.semanticTag)
        return context.items.asSequence()
            .filter { it.active || it.confidence >= 0.65 }
            .map { item ->
                val terms = item.contentTerms.map(::normalizeFieldText).toSet()
                val direct = if (forms.any { it in terms }) 1.0 else 0.0
                val related = if (concept.attractsTags.any { normalizeFieldText(it) in terms }) 1.0 else 0.0
                (direct * 0.82 + related * 0.42).coerceAtMost(1.0) * item.confidence * if (item.active) 1.0 else 0.8
            }
            .maxOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
    }

    private fun pairCoherence(left: PhraseWordCandidate, right: PhraseWordCandidate): Double {
        val leftConcept = lexicon.byId(left.conceptId) ?: return 0.0
        val rightConcept = lexicon.byId(right.conceptId) ?: return 0.0
        val repels = right.semanticTag in leftConcept.repelsTags || left.semanticTag in rightConcept.repelsTags
        if (repels) return -1.0
        val attracts = right.semanticTag in leftConcept.attractsTags || left.semanticTag in rightConcept.attractsTags
        if (attracts) return 1.0
        if (left.semanticTag == right.semanticTag) return -0.18
        val sharedIntent = leftConcept.intentBias.keys.intersect(rightConcept.intentBias.keys).isNotEmpty()
        return if (sharedIntent) 0.25 else 0.0
    }

    private fun offer(bucket: MutableList<BeamState>, state: BeamState) {
        bucket += state
        if (bucket.size <= beamWidth) return
        bucket.sortByDescending(BeamState::priority)
        while (bucket.size > beamWidth) bucket.removeAt(bucket.lastIndex)
    }

    private fun applyPhraseFeedback(
        lattice: AcousticPhonemeLattice,
        speechFrames: List<AcousticPhonemeFrame>,
        phrase: PhraseHypothesis,
    ): Pair<AcousticPhonemeLattice, List<PhraseFrameRevision>> {
        val revisedByFrameIndex = mutableMapOf<Int, AcousticPhonemeFrame>()
        val revisions = mutableListOf<PhraseFrameRevision>()
        phrase.words.forEachIndexed { wordIndex, word ->
            val expected = templateEncoder.encode(word.canonical)
            if (expected.isEmpty()) return@forEachIndexed
            for (speechIndex in word.startSpeechFrame until word.endSpeechFrameExclusive) {
                val rawFrame = speechFrames[speechIndex]
                val offset = speechIndex - word.startSpeechFrame
                val expectedClass = expectedAt(expected, offset, word.speechFrameCount)
                val lexicalForce = (word.activation * TOP_DOWN_FRAME_WEIGHT).coerceIn(0.0, 1.0)
                val existing = rawFrame.candidates.firstOrNull { it.phonemeClass == expectedClass }
                val before = existing?.activation ?: 0.0
                val after = (before + lexicalForce * (1.0 - before)).coerceIn(0.0, 1.0)
                val candidates = rawFrame.candidates
                    .filterNot { it.phonemeClass == expectedClass }
                    .plus(
                        existing?.copy(activation = after)
                            ?: AcousticPhonemeCandidate(
                                phonemeClass = expectedClass,
                                activation = after,
                                acousticDistance = 1.0,
                                continuityForce = 0.0,
                            ),
                    )
                    .sortedWith(compareByDescending<AcousticPhonemeCandidate> { it.activation }.thenBy { it.phonemeClass.name })
                revisedByFrameIndex[rawFrame.frameIndex] = rawFrame.copy(candidates = candidates)
                if (abs(after - before) >= REVISION_TRACE_DELTA) {
                    revisions += PhraseFrameRevision(
                        frameIndex = rawFrame.frameIndex,
                        wordIndex = wordIndex,
                        conceptId = word.conceptId,
                        phonemeClass = expectedClass,
                        beforeActivation = before,
                        afterActivation = after,
                        lexicalForce = lexicalForce,
                    )
                }
            }
        }
        return lattice.copy(
            frames = lattice.frames.map { revisedByFrameIndex[it.frameIndex] ?: it },
        ) to revisions.sortedWith(compareBy<PhraseFrameRevision> { it.frameIndex }.thenBy { it.wordIndex })
    }

    private data class BeamState(
        val position: Int,
        val words: List<PhraseWordCandidate> = emptyList(),
        val coveredFrames: Int = 0,
        val skippedFrames: Int = 0,
        val wordActivationSum: Double = 0.0,
        val coherenceSum: Double = 0.0,
        val coherenceEdges: Int = 0,
    ) {
        fun priority(): Double =
            wordActivationSum + coveredFrames * 0.010 - skippedFrames * 0.040 + coherenceSum * 0.12

        fun toHypothesis(totalSpeechFrames: Int): PhraseHypothesis {
            val coverage = (coveredFrames.toDouble() / totalSpeechFrames.toDouble()).coerceIn(0.0, 1.0)
            val unresolvedRatio = (skippedFrames.toDouble() / totalSpeechFrames.toDouble()).coerceIn(0.0, 1.0)
            val meanWordActivation = (wordActivationSum / words.size.toDouble()).coerceIn(0.0, 1.0)
            val coherence = if (coherenceEdges == 0) {
                0.5
            } else {
                ((coherenceSum / coherenceEdges.toDouble()) + 1.0).div(2.0).coerceIn(0.0, 1.0)
            }
            val activation = (
                meanWordActivation * 0.62 +
                    coverage * 0.25 +
                    coherence * 0.13 -
                    unresolvedRatio * 0.08
                ).coerceIn(0.0, 1.0)
            return PhraseHypothesis(words, skippedFrames, coverage, coherence, activation)
        }
    }

    companion object {
        private const val FRAMES_PER_TEMPLATE_CLASS = 4
        private const val MIN_WORD_FRAMES = 2
        private const val MAX_WORD_FRAMES = 160
        private val LENGTH_SCALES = listOf(0.58, 0.78, 1.0, 1.28, 1.58, 1.90)
        private const val MIN_ACOUSTIC_COMPATIBILITY = 0.14
        private const val MIN_WORD_ACTIVATION = 0.18
        private const val MAX_SPANS_PER_CONCEPT = 2
        private const val MAX_CANDIDATES_PER_START = 12
        private const val MAX_HYPOTHESES = 8
        private const val ACOUSTIC_WEIGHT = 0.70
        private const val DURATION_WEIGHT = 0.08
        private const val SEMANTIC_WEIGHT = 0.10
        private const val PHOTON_WEIGHT = 0.07
        private const val BOUNDARY_WEIGHT = 0.05
        private const val TOP_DOWN_FRAME_WEIGHT = 0.20
        private const val REVISION_TRACE_DELTA = 0.005
    }
}
