package app.lifeos.core.language

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LinguisticFieldEngine(
    private val lexicon: DeterministicLinguisticFieldLexicon = DeterministicLinguisticFieldLexicon(),
    private val weights: LinguisticFieldWeights = LinguisticFieldWeights(),
    private val maxIterations: Int = 6,
    private val convergenceDelta: Double = 1e-4,
    private val resolutionThreshold: Double = 0.58,
    private val resolutionMargin: Double = 0.05,
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
        val lexicalTokens = utterance.tokens
            .withIndex()
            .filter { it.value.kind != TokenKind.PUNCTUATION }
        if (lexicalTokens.isEmpty()) {
            return LinguisticFieldResult(emptyList(), emptyList(), emptyList(), converged = true, iterations = 0, totalEnergy = 0.0)
        }

        val candidates = lexicalTokens.associate { indexed ->
            indexed.index to seedCandidates(indexed.index, indexed.value.normalized)
        }.toMutableMap()

        val interactions = mutableListOf<LinguisticFieldInteraction>()
        var previous = snapshot(candidates)
        var converged = false
        var iterations = 0

        repeat(maxIterations) { iteration ->
            iterations = iteration + 1
            val intentField = aggregateIntentActivation(candidates)
            val updated = mutableMapOf<Int, List<MutableCandidate>>()
            lexicalTokens.forEach { indexed ->
                val tokenCandidates = candidates[indexed.index].orEmpty()
                val next = tokenCandidates.map { candidate ->
                    val concept = lexicon.byId(candidate.conceptId) ?: return@map candidate.copy()
                    val sentenceAttraction = sentenceAttraction(indexed.index, concept, candidates, interactions)
                    val photonAttraction = photonContextAttraction(concept, context)
                    val intentAttraction = concept.intentBias.entries.maxOfOrNull { (intent, bias) ->
                        bias * (intentField[intent] ?: 0.0)
                    } ?: 0.0
                    val repulsion = competitionRepulsion(candidate, tokenCandidates)
                    val raw =
                        weights.grapheme * candidate.graphemeAffinity +
                        weights.morphology * candidate.morphologyAffinity +
                        weights.lexical * candidate.lexicalAffinity +
                        weights.sentenceContext * sentenceAttraction +
                        weights.photonContext * photonAttraction +
                        weights.intentCoherence * intentAttraction -
                        weights.competitionRepulsion * repulsion
                    candidate.copy(
                        activation = squash(raw * concept.semanticMass),
                        sentenceAttraction = sentenceAttraction,
                        photonAttraction = photonAttraction,
                        intentAttraction = intentAttraction,
                        repulsion = repulsion,
                    )
                }
                updated[indexed.index] = next.sortedByDescending { it.activation }.take(MAX_CANDIDATES_PER_TOKEN)
            }
            candidates.clear()
            candidates.putAll(updated)
            val current = snapshot(candidates)
            if (maxDelta(previous, current) <= convergenceDelta) {
                converged = true
                return@repeat
            }
            previous = current
        }

        val resolutions = candidates.entries
            .sortedBy { it.key }
            .mapNotNull { (tokenIndex, tokenCandidates) ->
                val sorted = tokenCandidates.sortedByDescending { it.activation }
                val winner = sorted.firstOrNull() ?: return@mapNotNull null
                val runnerUp = sorted.getOrNull(1)
                val margin = winner.activation - (runnerUp?.activation ?: 0.0)
                if (winner.activation < resolutionThreshold || margin < resolutionMargin) return@mapNotNull null
                val rawToken = utterance.tokens[tokenIndex].original
                val concept = lexicon.byId(winner.conceptId) ?: return@mapNotNull null
                LinguisticFieldResolution(
                    tokenIndex = tokenIndex,
                    rawToken = rawToken,
                    canonical = concept.canonical,
                    semanticTag = concept.semanticTag,
                    entityType = concept.entityType,
                    confidence = winner.activation.coerceIn(0.0, 1.0),
                    alternatives = sorted.drop(1).take(3).map { it.canonical to it.activation },
                )
            }

        val intentField = aggregateIntentActivation(candidates)
            .entries
            .sortedByDescending { it.value }
            .map { (intent, activation) ->
                LinguisticIntentField(
                    intent = intent,
                    activation = activation.coerceIn(0.0, 1.0),
                    contributingConcepts = candidates.values.flatten()
                        .filter { candidate -> lexicon.byId(candidate.conceptId)?.intentBias?.containsKey(intent) == true }
                        .sortedByDescending { it.activation }
                        .map { it.conceptId }
                        .distinct()
                        .take(5),
                )
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
        )
    }

    private fun seedCandidates(tokenIndex: Int, token: String): List<MutableCandidate> {
        val normalized = normalizeFieldText(token)
        if (normalized.isBlank()) return emptyList()
        return lexicon.concepts.mapNotNull { concept ->
            val formScores = concept.allForms.map { form ->
                val grapheme = graphemeAffinity(normalized, form)
                val morphology = morphologyAffinity(normalized, form)
                val lexical = if (normalized == form) 1.0 else if (normalized in concept.allForms) 0.96 else 0.0
                Triple(grapheme, morphology, lexical)
            }
            val best = formScores.maxByOrNull { it.first * 0.55 + it.second * 0.30 + it.third * 0.15 } ?: return@mapNotNull null
            val seed = weights.grapheme * best.first + weights.morphology * best.second + weights.lexical * best.third
            if (seed < MIN_SEED_SCORE) return@mapNotNull null
            MutableCandidate(
                tokenIndex = tokenIndex,
                token = normalized,
                conceptId = concept.id,
                canonical = concept.canonical,
                graphemeAffinity = best.first,
                morphologyAffinity = best.second,
                lexicalAffinity = best.third,
                activation = squash(seed * concept.semanticMass),
            )
        }.sortedByDescending { it.activation }.take(MAX_CANDIDATES_PER_TOKEN)
    }

    private fun sentenceAttraction(
        tokenIndex: Int,
        concept: LinguisticConcept,
        candidates: Map<Int, List<MutableCandidate>>,
        interactions: MutableList<LinguisticFieldInteraction>,
    ): Double {
        var attraction = 0.0
        var normalizer = 0.0
        candidates.forEach { (otherIndex, otherCandidates) ->
            if (otherIndex == tokenIndex) return@forEach
            val distance = abs(otherIndex - tokenIndex)
            if (distance > SENTENCE_RADIUS) return@forEach
            val distanceWeight = 1.0 / distance.toDouble()
            otherCandidates.take(2).forEach { other ->
                val otherConcept = lexicon.byId(other.conceptId) ?: return@forEach
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
                }
                attraction += force
                normalizer += distanceWeight
            }
        }
        if (normalizer == 0.0) return 0.0
        return (attraction / normalizer).coerceIn(-1.0, 1.0)
    }

    private fun photonContextAttraction(concept: LinguisticConcept, context: LanguageContext): Double {
        if (context.items.isEmpty()) return 0.0
        val terms = concept.allForms + normalizeFieldText(concept.canonical) + concept.semanticTag.lowercase()
        val scored = context.items.asSequence()
            .filter { it.active || it.confidence >= 0.65 }
            .map { item ->
                val normalizedTerms = item.contentTerms.map(::normalizeFieldText).filter { it.isNotBlank() }.toSet()
                val overlap = terms.count { it in normalizedTerms }
                val semanticOverlap = concept.attractsTags.count { tag -> normalizeFieldText(tag) in normalizedTerms }
                val base = (overlap * 0.55 + semanticOverlap * 0.25).coerceAtMost(1.0)
                base * item.confidence * if (item.active) 1.0 else 0.8
            }
            .maxOrNull() ?: 0.0
        return scored.coerceIn(0.0, 1.0)
    }

    private fun aggregateIntentActivation(candidates: Map<Int, List<MutableCandidate>>): Map<IntentType, Double> {
        val sums = linkedMapOf<IntentType, Double>()
        val contributors = linkedMapOf<IntentType, Int>()
        candidates.values.flatten().forEach { candidate ->
            val concept = lexicon.byId(candidate.conceptId) ?: return@forEach
            concept.intentBias.forEach { (intent, bias) ->
                sums[intent] = (sums[intent] ?: 0.0) + candidate.activation * bias
                contributors[intent] = (contributors[intent] ?: 0) + 1
            }
        }
        return sums.mapValues { (intent, sum) ->
            val count = contributors[intent]?.coerceAtLeast(1) ?: 1
            (sum / count.toDouble()).coerceIn(0.0, 1.0)
        }
    }

    private fun competitionRepulsion(candidate: MutableCandidate, peers: List<MutableCandidate>): Double {
        val strongestOther = peers.asSequence()
            .filter { it.conceptId != candidate.conceptId }
            .maxOfOrNull { it.activation }
            ?: 0.0
        return strongestOther.coerceIn(0.0, 1.0)
    }

    private fun graphemeAffinity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val edit = levenshtein(left, right)
        val editSimilarity = 1.0 - edit.toDouble() / max(left.length, right.length).toDouble()
        val leftNgrams = ngrams(left)
        val rightNgrams = ngrams(right)
        val union = (leftNgrams + rightNgrams).size
        val jaccard = if (union == 0) 0.0 else leftNgrams.intersect(rightNgrams).size.toDouble() / union.toDouble()
        return (editSimilarity * 0.72 + jaccard * 0.28).coerceIn(0.0, 1.0)
    }

    private fun morphologyAffinity(left: String, right: String): Double {
        if (left == right) return 1.0
        val leftStem = stem(left)
        val rightStem = stem(right)
        if (leftStem == rightStem && leftStem.length >= 3) return 0.96
        val prefix = commonPrefixLength(leftStem, rightStem)
        val denominator = max(leftStem.length, rightStem.length).coerceAtLeast(1)
        val prefixScore = prefix.toDouble() / denominator.toDouble()
        val contains = when {
            leftStem.length >= 4 && rightStem.contains(leftStem) -> 0.82
            rightStem.length >= 4 && leftStem.contains(rightStem) -> 0.82
            else -> 0.0
        }
        return max(prefixScore, contains).coerceIn(0.0, 1.0)
    }

    private fun stem(value: String): String {
        var result = value
        val suffixes = listOf("ern", "en", "er", "es", "e", "n", "s", "ing")
        for (suffix in suffixes) {
            if (result.length - suffix.length >= 3 && result.endsWith(suffix)) {
                result = result.dropLast(suffix.length)
                break
            }
        }
        return result
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = min(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    private fun ngrams(value: String): Set<String> {
        if (value.length < 2) return setOf(value)
        return (0 until value.length - 1).mapTo(linkedSetOf()) { value.substring(it, it + 2) }
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val temp = previous
            previous = current
            current = temp
        }
        return previous[b.length]
    }

    private fun squash(value: Double): Double = (value / (1.0 + abs(value))).coerceIn(0.0, 1.0)

    private fun snapshot(candidates: Map<Int, List<MutableCandidate>>): Map<Pair<Int, String>, Double> =
        candidates.flatMap { (tokenIndex, tokenCandidates) ->
            tokenCandidates.map { (tokenIndex to it.conceptId) to it.activation }
        }.toMap()

    private fun maxDelta(
        before: Map<Pair<Int, String>, Double>,
        after: Map<Pair<Int, String>, Double>,
    ): Double {
        val keys = before.keys + after.keys
        return keys.maxOfOrNull { key -> abs((before[key] ?: 0.0) - (after[key] ?: 0.0)) } ?: 0.0
    }

    private data class MutableCandidate(
        val tokenIndex: Int,
        val token: String,
        val conceptId: String,
        val canonical: String,
        val graphemeAffinity: Double,
        val morphologyAffinity: Double,
        val lexicalAffinity: Double,
        val activation: Double,
        val sentenceAttraction: Double = 0.0,
        val photonAttraction: Double = 0.0,
        val intentAttraction: Double = 0.0,
        val repulsion: Double = 0.0,
    )

    companion object {
        private const val MIN_SEED_SCORE = 0.30
        private const val MAX_CANDIDATES_PER_TOKEN = 5
        private const val SENTENCE_RADIUS = 5
    }
}
