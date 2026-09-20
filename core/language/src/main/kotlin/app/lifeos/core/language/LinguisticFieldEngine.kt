package app.lifeos.core.language

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LinguisticFieldEngine(
    private val lexicon: LinguisticLexicon = DeterministicLinguisticFieldLexicon(),
    private val weights: LinguisticFieldWeights = LinguisticFieldWeights(),
    private val graphemeFieldEngine: GraphemeFieldEngine = GraphemeFieldEngine(),
    private val morphologyEngine: GermanMorphologyEngine = GermanMorphologyEngine(),
    private val lexicalIndex: LinguisticFieldIndexV2 =
        LinguisticFieldIndexV2(lexicon, morphologyEngine),
    private val compoundResolver: CompoundFieldResolver =
        CompoundFieldResolver(lexicon, lexicalIndex),
    private val maxIterations: Int = 6,
    private val convergenceDelta: Double = 1e-4,
    private val resolutionThreshold: Double = 0.55,
    private val resolutionMargin: Double = 0.04,
) {
    init {
        require(maxIterations > 0)
        require(convergenceDelta > 0.0)
        require(resolutionThreshold in 0.0..1.0)
        require(resolutionMargin in 0.0..1.0)
    }

    fun converge(
        utterance: NormalizedUtterance,
        context: LanguageContext = LanguageContext(),
    ): LinguisticFieldResult {
        val compounds = compoundResolver.resolve(utterance)
        val lexicalTokens = utterance.tokens.withIndex().filter { it.value.kind != TokenKind.PUNCTUATION }
        if (lexicalTokens.isEmpty()) {
            return LinguisticFieldResult(
                resolutions = emptyList(),
                intentField = emptyList(),
                interactions = emptyList(),
                converged = true,
                iterations = 0,
                totalEnergy = 0.0,
                compoundBindings = compounds,
            )
        }

        val candidates = lexicalTokens.associate { indexed ->
            indexed.index to seedCandidates(indexed.index, indexed.value.normalized)
        }.toMutableMap()
        val interactions = mutableListOf<LinguisticFieldInteraction>()
        val revisions = mutableListOf<TopDownFieldRevision>()
        var previous = snapshot(candidates)
        var converged = false
        var iterations = 0

        for (iteration in 1..maxIterations) {
            iterations = iteration
            val intentField = aggregateIntentActivation(candidates)
            val updated = linkedMapOf<Int, List<FieldCandidate>>()
            lexicalTokens.forEach { indexed ->
                val peers = candidates[indexed.index].orEmpty()
                updated[indexed.index] = peers.map { candidate ->
                    val concept = lexicon.byId(candidate.conceptId) ?: return@map candidate
                    val sentence = sentenceAttraction(indexed.index, concept, candidates, interactions)
                    val composition = compositionAttraction(indexed.index, concept, compounds, interactions)
                    val photon = photonContextAttraction(concept, context)
                    val intent = concept.intentBias.entries.maxOfOrNull { (type, bias) ->
                        bias * (intentField[type] ?: 0.0)
                    } ?: 0.0
                    val repulsion = peers.asSequence()
                        .filter { it.conceptId != candidate.conceptId }
                        .maxOfOrNull { it.activation }
                        ?: 0.0
                    val bottomUpEnergy =
                        weights.grapheme * candidate.graphemeAffinity +
                        weights.phonetic * candidate.phoneticAffinity +
                        weights.morphology * candidate.morphologyAffinity +
                        weights.lexical * candidate.lexicalAffinity
                    val fieldEnergy =
                        bottomUpEnergy +
                        weights.compositionContext * composition +
                        weights.sentenceContext * sentence +
                        weights.photonContext * photon +
                        weights.intentCoherence * intent -
                        weights.competitionRepulsion * repulsion
                    val bottomUpActivation = (bottomUpEnergy * concept.semanticMass).coerceIn(0.0, 1.0)
                    val resolvedActivation = (fieldEnergy * concept.semanticMass).coerceIn(0.0, 1.0)
                    if (abs(resolvedActivation - bottomUpActivation) >= TOP_DOWN_TRACE_DELTA) {
                        revisions += TopDownFieldRevision(
                            iteration = iteration,
                            tokenIndex = indexed.index,
                            conceptId = concept.id,
                            bottomUpActivation = bottomUpActivation,
                            resolvedActivation = resolvedActivation,
                            semanticForce = sentence,
                            photonForce = photon,
                            intentForce = intent,
                            compositionForce = composition,
                        )
                    }
                    candidate.copy(
                        activation = resolvedActivation,
                        compositionAttraction = composition,
                        sentenceAttraction = sentence,
                        photonAttraction = photon,
                        intentAttraction = intent,
                        repulsion = repulsion,
                    )
                }.sortedByDescending { it.activation }.take(MAX_CANDIDATES_PER_TOKEN)
            }
            candidates.clear()
            candidates.putAll(updated)
            val current = snapshot(candidates)
            if (maxDelta(previous, current) <= convergenceDelta) {
                converged = true
                break
            }
            previous = current
        }

        val resolutions = candidates.entries.sortedBy { it.key }.mapNotNull { (tokenIndex, tokenCandidates) ->
            val sorted = tokenCandidates.sortedByDescending { it.activation }
            val winner = sorted.firstOrNull() ?: return@mapNotNull null
            val runnerUp = sorted.getOrNull(1)
            val margin = winner.activation - (runnerUp?.activation ?: 0.0)
            if (winner.activation < resolutionThreshold || margin < resolutionMargin) return@mapNotNull null
            val concept = lexicon.byId(winner.conceptId) ?: return@mapNotNull null
            LinguisticFieldResolution(
                tokenIndex = tokenIndex,
                rawToken = utterance.tokens[tokenIndex].original,
                canonical = concept.canonical,
                semanticTag = concept.semanticTag,
                entityType = concept.entityType,
                confidence = winner.activation,
                alternatives = sorted.drop(1).take(3).map { it.canonical to it.activation },
            )
        }

        val intentField = aggregateIntentActivation(candidates).entries
            .sortedByDescending { it.value }
            .map { (intent, activation) ->
                LinguisticIntentField(
                    intent = intent,
                    activation = activation,
                    contributingConcepts = candidates.values.flatten()
                        .filter { lexicon.byId(it.conceptId)?.intentBias?.containsKey(intent) == true }
                        .sortedByDescending { it.activation }
                        .map { it.conceptId }
                        .distinct()
                        .take(5),
                )
            }

        val graphemeTraces = resolutions.map { resolution ->
            graphemeFieldEngine.compare(resolution.rawToken, resolution.canonical)
        }

        return LinguisticFieldResult(
            resolutions = resolutions,
            intentField = intentField,
            interactions = interactions
                .distinctBy { Triple(it.sourceConceptId, it.targetConceptId, it.reason) }
                .sortedWith(compareBy<LinguisticFieldInteraction> { it.sourceConceptId }.thenBy { it.targetConceptId }.thenBy { it.reason }),
            converged = converged,
            iterations = iterations,
            totalEnergy = candidates.values.flatten().sumOf { it.activation },
            graphemeTraces = graphemeTraces,
            compoundBindings = compounds,
            topDownRevisions = revisions
                .distinctBy { listOf(it.iteration.toString(), it.tokenIndex.toString(), it.conceptId) }
                .sortedWith(compareBy<TopDownFieldRevision> { it.iteration }.thenBy { it.tokenIndex }.thenBy { it.conceptId }),
        )
    }

    private fun seedCandidates(tokenIndex: Int, token: String): List<FieldCandidate> {
        val normalized = normalizeFieldText(token)
        if (normalized.isBlank()) return emptyList()
        return lexicalIndex.candidateConceptIds(normalized).mapNotNull { conceptId ->
            val concept = lexicon.byId(conceptId) ?: return@mapNotNull null
            val best = concept.allForms.map { form ->
                val trace = graphemeFieldEngine.compare(normalized, form)
                SeedEvidence(
                    grapheme = trace.orthographicAffinity,
                    phonetic = trace.phoneticAffinity,
                    morphology = morphologyAffinity(normalized, form),
                    lexical = if (normalized == form) 1.0 else 0.0,
                )
            }.maxByOrNull {
                it.grapheme * 0.45 + it.phonetic * 0.20 + it.morphology * 0.25 + it.lexical * 0.10
            } ?: return@mapNotNull null
            val seedEnergy =
                weights.grapheme * best.grapheme +
                weights.phonetic * best.phonetic +
                weights.morphology * best.morphology +
                weights.lexical * best.lexical
            if (seedEnergy < MIN_SEED_SCORE) return@mapNotNull null
            FieldCandidate(
                tokenIndex = tokenIndex,
                token = normalized,
                conceptId = concept.id,
                canonical = concept.canonical,
                graphemeAffinity = best.grapheme,
                phoneticAffinity = best.phonetic,
                morphologyAffinity = best.morphology,
                lexicalAffinity = best.lexical,
                activation = (seedEnergy * concept.semanticMass).coerceIn(0.0, 1.0),
            )
        }.sortedByDescending { it.activation }.take(MAX_CANDIDATES_PER_TOKEN)
    }

    private fun sentenceAttraction(
        tokenIndex: Int,
        concept: LinguisticConcept,
        candidates: Map<Int, List<FieldCandidate>>,
        interactions: MutableList<LinguisticFieldInteraction>,
    ): Double {
        var forceSum = 0.0
        var weightSum = 0.0
        candidates.forEach { (otherIndex, otherCandidates) ->
            if (otherIndex == tokenIndex) return@forEach
            val distance = abs(otherIndex - tokenIndex)
            if (distance == 0 || distance > SENTENCE_RADIUS) return@forEach
            val distanceWeight = 1.0 / distance
            otherCandidates.take(2).forEach candidateLoop@ { other ->
                val otherConcept = lexicon.byId(other.conceptId) ?: return@candidateLoop
                val compatible = otherConcept.semanticTag in concept.attractsTags || concept.semanticTag in otherConcept.attractsTags
                val contradictory = otherConcept.semanticTag in concept.repelsTags || concept.semanticTag in otherConcept.repelsTags
                val force = when {
                    compatible -> other.activation * distanceWeight
                    contradictory -> -other.activation * distanceWeight
                    else -> 0.0
                }
                if (force != 0.0) {
                    interactions += LinguisticFieldInteraction(
                        sourceConceptId = concept.id,
                        targetConceptId = otherConcept.id,
                        force = force,
                        reason = if (force > 0.0) "sentence-attraction" else "sentence-repulsion",
                    )
                    forceSum += force
                    weightSum += distanceWeight
                }
            }
        }
        return if (weightSum == 0.0) 0.0 else (forceSum / weightSum).coerceIn(-1.0, 1.0)
    }

    private fun compositionAttraction(
        tokenIndex: Int,
        concept: LinguisticConcept,
        compounds: List<CompoundFieldBinding>,
        interactions: MutableList<LinguisticFieldInteraction>,
    ): Double {
        val binding = compounds.firstOrNull { it.tokenIndex == tokenIndex } ?: return 0.0
        val direct = binding.components.firstOrNull { it.conceptId == concept.id }
        if (direct != null) {
            interactions += LinguisticFieldInteraction(concept.id, concept.id, binding.confidence, "compound-component")
            return binding.confidence
        }
        val compatible = binding.components.filter { component ->
            component.semanticTag in concept.attractsTags
        }
        if (compatible.isEmpty()) return 0.0
        compatible.forEach { component ->
            interactions += LinguisticFieldInteraction(concept.id, component.conceptId, binding.confidence * 0.75, "compound-context")
        }
        return (binding.confidence * 0.75).coerceIn(0.0, 1.0)
    }

    private fun photonContextAttraction(concept: LinguisticConcept, context: LanguageContext): Double {
        if (context.items.isEmpty()) return 0.0
        val forms = concept.allForms + normalizeFieldText(concept.semanticTag)
        return context.items.asSequence()
            .filter { it.active || it.confidence >= 0.65 }
            .map { item ->
                val terms = item.contentTerms.map(::normalizeFieldText).filter { it.isNotBlank() }.toSet()
                val lexicalOverlap = forms.count { it in terms }
                val semanticOverlap = concept.attractsTags.count { normalizeFieldText(it) in terms }
                ((lexicalOverlap * 0.55 + semanticOverlap * 0.25).coerceAtMost(1.0) * item.confidence * if (item.active) 1.0 else 0.8)
            }
            .maxOrNull()
            ?.coerceIn(0.0, 1.0)
            ?: 0.0
    }

    private fun aggregateIntentActivation(candidates: Map<Int, List<FieldCandidate>>): Map<IntentType, Double> {
        val sums = linkedMapOf<IntentType, Double>()
        val counts = linkedMapOf<IntentType, Int>()
        candidates.values.flatten().forEach { candidate ->
            val concept = lexicon.byId(candidate.conceptId) ?: return@forEach
            concept.intentBias.forEach { (intent, bias) ->
                sums[intent] = (sums[intent] ?: 0.0) + candidate.activation * bias
                counts[intent] = (counts[intent] ?: 0) + 1
            }
        }
        return sums.mapValues { (intent, sum) -> (sum / (counts[intent] ?: 1)).coerceIn(0.0, 1.0) }
    }

    private fun morphologyAffinity(left: String, right: String): Double =
        morphologyEngine.affinity(left, right)

    private fun snapshot(candidates: Map<Int, List<FieldCandidate>>): Map<Pair<Int, String>, Double> =
        candidates.flatMap { (index, values) -> values.map { (index to it.conceptId) to it.activation } }.toMap()

    private fun maxDelta(before: Map<Pair<Int, String>, Double>, after: Map<Pair<Int, String>, Double>): Double =
        (before.keys + after.keys).maxOfOrNull { abs((before[it] ?: 0.0) - (after[it] ?: 0.0)) } ?: 0.0

    private data class SeedEvidence(
        val grapheme: Double,
        val phonetic: Double,
        val morphology: Double,
        val lexical: Double,
    )

    private data class FieldCandidate(
        val tokenIndex: Int,
        val token: String,
        val conceptId: String,
        val canonical: String,
        val graphemeAffinity: Double,
        val phoneticAffinity: Double,
        val morphologyAffinity: Double,
        val lexicalAffinity: Double,
        val activation: Double,
        val compositionAttraction: Double = 0.0,
        val sentenceAttraction: Double = 0.0,
        val photonAttraction: Double = 0.0,
        val intentAttraction: Double = 0.0,
        val repulsion: Double = 0.0,
    )

    companion object {
        private const val MIN_SEED_SCORE = 0.25
        private const val MAX_CANDIDATES_PER_TOKEN = 5
        private const val SENTENCE_RADIUS = 5
        private const val TOP_DOWN_TRACE_DELTA = 0.015
    }
}
