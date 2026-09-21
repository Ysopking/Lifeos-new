package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.learning.ConceptInductionResult

enum class KnowledgeCandidateKind {
    CONCEPT,
    PROPOSITION,
    RELATION,
    CAUSAL_RULE,
}

enum class KnowledgeObservationRelation {
    SUPPORTS,
    CONTRADICTS,
}

data class KnowledgeInductionPolicy(
    val minimumIndependentSupportCycles: Int = 2,
    val minimumMeanSupportConfidence: Double = 0.55,
    val counterexamplePenalty: Double = 0.15,
) {
    init {
        require(minimumIndependentSupportCycles in 2..64)
        require(minimumMeanSupportConfidence.isFinite() && minimumMeanSupportConfidence in 0.0..1.0)
        require(counterexamplePenalty.isFinite() && counterexamplePenalty in 0.0..1.0)
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "knowledge-induction-policy/v1",
        minimumIndependentSupportCycles.toString(),
        java.lang.Double.toHexString(minimumMeanSupportConfidence),
        java.lang.Double.toHexString(counterexamplePenalty),
    )
}

data class KnowledgeInductionObservation(
    val episodeId: LearningEpisodeId,
    val sourceCycleId: String,
    val kind: KnowledgeCandidateKind,
    val semanticKey: String,
    val statement: String,
    val relation: KnowledgeObservationRelation,
    val evidenceFingerprint: String,
    val confidence: Double,
) {
    init {
        require(sourceCycleId.isNotBlank())
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }

    fun claimFingerprint(): String = StableFieldIds.fingerprint(
        "knowledge-induction-claim/v1",
        kind.name,
        semanticKey,
        statement,
    )

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "knowledge-induction-observation/v1",
        episodeId.value,
        sourceCycleId,
        claimFingerprint(),
        relation.name,
        evidenceFingerprint,
        java.lang.Double.toHexString(confidence),
    )
}

data class StructuralConceptBinding(
    val result: ConceptInductionResult,
    val semanticKey: String,
    val statement: String,
) {
    init {
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
    }
}

data class KnowledgeCandidate(
    val id: String,
    val kind: KnowledgeCandidateKind,
    val semanticKey: String,
    val statement: String,
    val supportingEpisodeIds: List<LearningEpisodeId>,
    val contradictingEpisodeIds: List<LearningEpisodeId>,
    val sourceCycleIds: List<String>,
    val evidenceFingerprints: List<String>,
    val supportCount: Int,
    val counterexampleCount: Int,
    val confidence: Double,
    val sourceLedgerFingerprint: String,
    val inductionPolicyFingerprint: String,
    val structuralInductionFingerprint: String?,
) {
    init {
        require(id.startsWith(ID_PREFIX))
        require(semanticKey.isNotBlank())
        require(statement.isNotBlank())
        require(supportingEpisodeIds.size >= 2)
        require(supportingEpisodeIds == supportingEpisodeIds.distinct().sortedBy { it.value })
        require(contradictingEpisodeIds == contradictingEpisodeIds.distinct().sortedBy { it.value })
        require(supportingEpisodeIds.intersect(contradictingEpisodeIds.toSet()).isEmpty())
        require(sourceCycleIds.size >= 2)
        require(sourceCycleIds == sourceCycleIds.distinct().sorted())
        require(evidenceFingerprints.isNotEmpty())
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        require(supportCount == supportingEpisodeIds.size)
        require(counterexampleCount == contradictingEpisodeIds.size)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(sourceLedgerFingerprint.isNotBlank())
        require(inductionPolicyFingerprint.isNotBlank())
        structuralInductionFingerprint?.let { require(it.isNotBlank()) }
        require(id == expectedId())
    }

    val truthAuthority: Boolean
        get() = false

    val promotionAllowed: Boolean
        get() = false

    val activationAllowed: Boolean
        get() = false

    fun fingerprint(): String = candidateFingerprint(
        kind = kind,
        semanticKey = semanticKey,
        statement = statement,
        supportingEpisodeIds = supportingEpisodeIds,
        contradictingEpisodeIds = contradictingEpisodeIds,
        sourceCycleIds = sourceCycleIds,
        evidenceFingerprints = evidenceFingerprints,
        supportCount = supportCount,
        counterexampleCount = counterexampleCount,
        confidence = confidence,
        sourceLedgerFingerprint = sourceLedgerFingerprint,
        inductionPolicyFingerprint = inductionPolicyFingerprint,
        structuralInductionFingerprint = structuralInductionFingerprint,
    )

    private fun expectedId(): String = ID_PREFIX + fingerprint()

    companion object {
        const val ID_PREFIX = "knowledge-candidate:"
    }
}

/**
 * B375 induces reviewable knowledge candidates from independently verified B374 episodes.
 *
 * It never promotes a candidate to world knowledge. Existing ConceptInductionEngine remains the
 * structural-pattern induction authority; bindStructuralConcepts only binds its results to exact
 * B374 episode lineage.
 */
class KnowledgeInductionEngine(
    private val policy: KnowledgeInductionPolicy = KnowledgeInductionPolicy(),
) {
    fun induce(
        state: LearningEpisodeState,
        observations: Collection<KnowledgeInductionObservation>,
    ): List<KnowledgeCandidate> {
        require(state.episodes.isNotEmpty()) { "Knowledge induction requires learning episodes" }
        require(observations.isNotEmpty()) { "Knowledge induction requires observations" }
        val episodeById = state.episodes.associateBy { it.id }
        val canonical = observations
            .distinctBy { it.fingerprint() }
            .sortedBy { it.fingerprint() }
        require(canonical.size == observations.size) {
            "Duplicate knowledge induction observations are not allowed"
        }

        canonical.forEach { observation ->
            val episode = requireNotNull(episodeById[observation.episodeId]) {
                "Knowledge observation references an unknown learning episode"
            }
            require(episode.sourceCycleId == observation.sourceCycleId) {
                "Knowledge observation source cycle does not match its episode"
            }
            require(
                episode.status == LearningEpisodeStatus.VERIFIED_OUTCOME ||
                    episode.status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT
            ) {
                "Knowledge induction requires verified learning episodes"
            }
            if (observation.kind == KnowledgeCandidateKind.CAUSAL_RULE) {
                require(episode.status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT) {
                    "Causal-rule induction requires causal-credit learning episodes"
                }
            }
        }

        return canonical
            .groupBy { it.claimFingerprint() }
            .mapNotNull { (_, group) -> induceGroup(state, group) }
            .sortedWith(
                compareByDescending<KnowledgeCandidate> { it.confidence }
                    .thenBy { it.id }
            )
    }

    fun bindStructuralConcepts(
        state: LearningEpisodeState,
        bindings: Collection<StructuralConceptBinding>,
    ): List<KnowledgeCandidate> {
        require(state.episodes.isNotEmpty()) { "Structural concept binding requires learning episodes" }
        require(bindings.isNotEmpty()) { "Structural concept binding requires induction results" }
        val latestByCycle = state.episodes
            .groupBy { it.sourceCycleId }
            .mapValues { (_, episodes) -> episodes.maxBy { it.cycleRevision } }

        return bindings
            .distinctBy {
                StableFieldIds.fingerprint(
                    it.result.fingerprint,
                    it.semanticKey,
                    it.statement,
                )
            }
            .map { binding ->
                val episodes = binding.result.sourceCycleIds
                    .map { cycleId ->
                        requireNotNull(latestByCycle[cycleId]) {
                            "Concept induction cycle has no B374 learning episode"
                        }
                    }
                    .onEach { episode ->
                        require(
                            episode.status == LearningEpisodeStatus.VERIFIED_OUTCOME ||
                                episode.status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT
                        ) {
                            "Structural concept binding requires verified learning episodes"
                        }
                    }
                    .sortedBy { it.id.value }
                require(episodes.size >= policy.minimumIndependentSupportCycles)
                require(binding.result.confidence >= policy.minimumMeanSupportConfidence)

                createCandidate(
                    state = state,
                    kind = KnowledgeCandidateKind.CONCEPT,
                    semanticKey = binding.semanticKey,
                    statement = binding.statement,
                    supportingEpisodes = episodes,
                    contradictingEpisodes = emptyList(),
                    evidenceFingerprints = listOf(binding.result.fingerprint),
                    confidence = binding.result.confidence,
                    structuralInductionFingerprint = binding.result.fingerprint,
                )
            }
            .sortedWith(
                compareByDescending<KnowledgeCandidate> { it.confidence }
                    .thenBy { it.id }
            )
    }

    private fun induceGroup(
        state: LearningEpisodeState,
        group: List<KnowledgeInductionObservation>,
    ): KnowledgeCandidate? {
        val first = group.first()
        require(group.all {
            it.kind == first.kind &&
                it.semanticKey == first.semanticKey &&
                it.statement == first.statement
        })
        val byEpisode = group.groupBy { it.episodeId }
        require(byEpisode.values.none { observations ->
            observations.map { it.relation }.distinct().size > 1
        }) {
            "One learning episode cannot both support and contradict one knowledge claim"
        }

        val supports = group
            .filter { it.relation == KnowledgeObservationRelation.SUPPORTS }
            .distinctBy { it.episodeId }
        val contradictions = group
            .filter { it.relation == KnowledgeObservationRelation.CONTRADICTS }
            .distinctBy { it.episodeId }
        val supportCycles = supports.mapTo(linkedSetOf()) { it.sourceCycleId }
        if (supportCycles.size < policy.minimumIndependentSupportCycles) return null

        val meanSupport = supports.map { it.confidence }.average()
        if (meanSupport < policy.minimumMeanSupportConfidence) return null

        val contradictionCycles = contradictions.mapTo(linkedSetOf()) { it.sourceCycleId }
        val independenceRatio = supportCycles.size.toDouble() /
            (supportCycles.size + contradictionCycles.size).coerceAtLeast(1)
        val penalty = (contradictionCycles.size * policy.counterexamplePenalty).coerceAtMost(1.0)
        val confidence = (meanSupport * independenceRatio * (1.0 - penalty)).coerceIn(0.0, 1.0)

        val episodeById = state.episodes.associateBy { it.id }
        return createCandidate(
            state = state,
            kind = first.kind,
            semanticKey = first.semanticKey,
            statement = first.statement,
            supportingEpisodes = supports.map { episodeById.getValue(it.episodeId) },
            contradictingEpisodes = contradictions.map { episodeById.getValue(it.episodeId) },
            evidenceFingerprints = group.map { it.evidenceFingerprint },
            confidence = confidence,
            structuralInductionFingerprint = null,
        )
    }

    private fun createCandidate(
        state: LearningEpisodeState,
        kind: KnowledgeCandidateKind,
        semanticKey: String,
        statement: String,
        supportingEpisodes: Collection<LearningEpisode>,
        contradictingEpisodes: Collection<LearningEpisode>,
        evidenceFingerprints: Collection<String>,
        confidence: Double,
        structuralInductionFingerprint: String?,
    ): KnowledgeCandidate {
        val supporting = supportingEpisodes.distinctBy { it.id }.sortedBy { it.id.value }
        val contradicting = contradictingEpisodes.distinctBy { it.id }.sortedBy { it.id.value }
        val cycles = supporting.map { it.sourceCycleId }.distinct().sorted()
        val evidence = evidenceFingerprints.distinct().sorted()
        val policyFingerprint = policy.fingerprint()
        val fingerprint = candidateFingerprint(
            kind = kind,
            semanticKey = semanticKey,
            statement = statement,
            supportingEpisodeIds = supporting.map { it.id },
            contradictingEpisodeIds = contradicting.map { it.id },
            sourceCycleIds = cycles,
            evidenceFingerprints = evidence,
            supportCount = supporting.size,
            counterexampleCount = contradicting.size,
            confidence = confidence,
            sourceLedgerFingerprint = state.fingerprint,
            inductionPolicyFingerprint = policyFingerprint,
            structuralInductionFingerprint = structuralInductionFingerprint,
        )
        return KnowledgeCandidate(
            id = KnowledgeCandidate.ID_PREFIX + fingerprint,
            kind = kind,
            semanticKey = semanticKey,
            statement = statement,
            supportingEpisodeIds = supporting.map { it.id },
            contradictingEpisodeIds = contradicting.map { it.id },
            sourceCycleIds = cycles,
            evidenceFingerprints = evidence,
            supportCount = supporting.size,
            counterexampleCount = contradicting.size,
            confidence = confidence,
            sourceLedgerFingerprint = state.fingerprint,
            inductionPolicyFingerprint = policyFingerprint,
            structuralInductionFingerprint = structuralInductionFingerprint,
        )
    }
}

private fun candidateFingerprint(
    kind: KnowledgeCandidateKind,
    semanticKey: String,
    statement: String,
    supportingEpisodeIds: List<LearningEpisodeId>,
    contradictingEpisodeIds: List<LearningEpisodeId>,
    sourceCycleIds: List<String>,
    evidenceFingerprints: List<String>,
    supportCount: Int,
    counterexampleCount: Int,
    confidence: Double,
    sourceLedgerFingerprint: String,
    inductionPolicyFingerprint: String,
    structuralInductionFingerprint: String?,
): String = StableFieldIds.fingerprint(
    "knowledge-candidate/v1",
    kind.name,
    semanticKey,
    statement,
    supportCount.toString(),
    counterexampleCount.toString(),
    java.lang.Double.toHexString(confidence),
    sourceLedgerFingerprint,
    inductionPolicyFingerprint,
    structuralInductionFingerprint.orEmpty(),
    *supportingEpisodeIds.map { "support:" + it.value }.sorted().toTypedArray(),
    *contradictingEpisodeIds.map { "contradict:" + it.value }.sorted().toTypedArray(),
    *sourceCycleIds.map { "cycle:" + it }.sorted().toTypedArray(),
    *evidenceFingerprints.map { "evidence:" + it }.sorted().toTypedArray(),
)
