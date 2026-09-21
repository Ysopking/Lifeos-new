package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.reasoning.ProblemStateGraphId
import app.lifeos.core.runtime.learning.CandidateCluster
import app.lifeos.core.runtime.learning.ConceptInductionResult
import app.lifeos.core.runtime.learning.PatternOccurrence
import app.lifeos.core.runtime.learning.PatternSignature
import app.lifeos.core.runtime.thought.ThoughtGraphEdgeKind
import app.lifeos.core.runtime.thought.ThoughtGraphNodeKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeInductionEngineTest {
    private val at = Instant.parse("2026-09-21T21:00:00Z")

    @Test
    fun `two independent verified cycles induce only a reviewable knowledge candidate`() {
        val state = state(
            episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME),
            episode("cycle-b", LearningEpisodeStatus.VERIFIED_OUTCOME),
        )
        val observations = state.episodes.map { item ->
            observation(
                item,
                kind = KnowledgeCandidateKind.PROPOSITION,
                relation = KnowledgeObservationRelation.SUPPORTS,
                confidence = 0.8,
            )
        }

        val candidate = KnowledgeInductionEngine().induce(state, observations).single()

        assertEquals(KnowledgeCandidateKind.PROPOSITION, candidate.kind)
        assertEquals(2, candidate.supportCount)
        assertEquals(listOf("cycle-a", "cycle-b"), candidate.sourceCycleIds)
        assertFalse(candidate.truthAuthority)
        assertFalse(candidate.promotionAllowed)
        assertFalse(candidate.activationAllowed)
    }

    @Test
    fun `multiple revisions of one cycle do not fake independent support`() {
        val first = episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val second = episode(
            sourceCycleId = "cycle-a",
            status = LearningEpisodeStatus.VERIFIED_OUTCOME,
            cycleRevision = 2L,
            predecessorId = first.id,
            createdAt = at.plusSeconds(1),
        )
        val state = state(first, second)
        val observations = state.episodes.map {
            observation(
                it,
                kind = KnowledgeCandidateKind.PROPOSITION,
                relation = KnowledgeObservationRelation.SUPPORTS,
                confidence = 0.9,
            )
        }

        assertTrue(KnowledgeInductionEngine().induce(state, observations).isEmpty())
    }

    @Test
    fun `unverified episode observations fail closed`() {
        val verified = episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val unverified = episode("cycle-b", LearningEpisodeStatus.UNVERIFIED_OUTCOME)
        val state = state(verified, unverified)

        assertFailsWith<IllegalArgumentException> {
            KnowledgeInductionEngine().induce(
                state,
                listOf(
                    observation(
                        verified,
                        KnowledgeCandidateKind.PROPOSITION,
                        KnowledgeObservationRelation.SUPPORTS,
                        0.8,
                    ),
                    observation(
                        unverified,
                        KnowledgeCandidateKind.PROPOSITION,
                        KnowledgeObservationRelation.SUPPORTS,
                        0.8,
                    ),
                ),
            )
        }
    }

    @Test
    fun `causal rule induction requires causal credit on every contributing episode`() {
        val causalA = episode(
            "cycle-a",
            LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT,
        )
        val nonCausalB = episode(
            "cycle-b",
            LearningEpisodeStatus.VERIFIED_OUTCOME,
        )
        val state = state(causalA, nonCausalB)

        assertFailsWith<IllegalArgumentException> {
            KnowledgeInductionEngine().induce(
                state,
                listOf(
                    observation(
                        causalA,
                        KnowledgeCandidateKind.CAUSAL_RULE,
                        KnowledgeObservationRelation.SUPPORTS,
                        0.85,
                    ),
                    observation(
                        nonCausalB,
                        KnowledgeCandidateKind.CAUSAL_RULE,
                        KnowledgeObservationRelation.SUPPORTS,
                        0.85,
                    ),
                ),
            )
        }
    }

    @Test
    fun `one episode cannot both support and contradict the same claim`() {
        val first = episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val second = episode("cycle-b", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val state = state(first, second)

        assertFailsWith<IllegalArgumentException> {
            KnowledgeInductionEngine().induce(
                state,
                listOf(
                    observation(
                        first,
                        KnowledgeCandidateKind.PROPOSITION,
                        KnowledgeObservationRelation.SUPPORTS,
                        0.8,
                    ),
                    observation(
                        first,
                        KnowledgeCandidateKind.PROPOSITION,
                        KnowledgeObservationRelation.CONTRADICTS,
                        0.8,
                    ),
                    observation(
                        second,
                        KnowledgeCandidateKind.PROPOSITION,
                        KnowledgeObservationRelation.SUPPORTS,
                        0.8,
                    ),
                ),
            )
        }
    }

    @Test
    fun `counterexamples reduce confidence without becoming truth authority`() {
        val supportA = episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val supportB = episode("cycle-b", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val counter = episode("cycle-c", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val state = state(supportA, supportB, counter)
        val engine = KnowledgeInductionEngine()

        val withoutCounter = engine.induce(
            state,
            listOf(
                observation(supportA, KnowledgeCandidateKind.RELATION, KnowledgeObservationRelation.SUPPORTS, 0.9),
                observation(supportB, KnowledgeCandidateKind.RELATION, KnowledgeObservationRelation.SUPPORTS, 0.9),
            ),
        ).single()
        val withCounter = engine.induce(
            state,
            listOf(
                observation(supportA, KnowledgeCandidateKind.RELATION, KnowledgeObservationRelation.SUPPORTS, 0.9),
                observation(supportB, KnowledgeCandidateKind.RELATION, KnowledgeObservationRelation.SUPPORTS, 0.9),
                observation(counter, KnowledgeCandidateKind.RELATION, KnowledgeObservationRelation.CONTRADICTS, 0.9),
            ),
        ).single()

        assertTrue(withCounter.confidence < withoutCounter.confidence)
        assertFalse(withCounter.truthAuthority)
    }

    @Test
    fun `observation ordering cannot change candidate identity`() {
        val state = state(
            episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME),
            episode("cycle-b", LearningEpisodeStatus.VERIFIED_OUTCOME),
        )
        val observations = state.episodes.map {
            observation(
                it,
                KnowledgeCandidateKind.PROPOSITION,
                KnowledgeObservationRelation.SUPPORTS,
                0.8,
            )
        }
        val engine = KnowledgeInductionEngine()

        assertEquals(
            engine.induce(state, observations),
            engine.induce(state, observations.reversed()),
        )
    }

    @Test
    fun `existing ConceptInductionResult binds to exact verified episode cycles`() {
        val first = episode("cycle-a", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val second = episode("cycle-b", LearningEpisodeStatus.VERIFIED_OUTCOME)
        val state = state(first, second)
        val result = conceptResult(setOf("cycle-a", "cycle-b"))

        val candidate = KnowledgeInductionEngine().bindStructuralConcepts(
            state,
            listOf(
                StructuralConceptBinding(
                    result = result,
                    semanticKey = "concept:evidence-support",
                    statement = "Evidence support patterns recur across verified cycles.",
                )
            ),
        ).single()

        assertEquals(KnowledgeCandidateKind.CONCEPT, candidate.kind)
        assertEquals(result.fingerprint, candidate.structuralInductionFingerprint)
        assertEquals(2, candidate.supportCount)
        assertFalse(candidate.promotionAllowed)
    }

    private fun observation(
        episode: LearningEpisode,
        kind: KnowledgeCandidateKind,
        relation: KnowledgeObservationRelation,
        confidence: Double,
    ): KnowledgeInductionObservation = KnowledgeInductionObservation(
        episodeId = episode.id,
        sourceCycleId = episode.sourceCycleId,
        kind = kind,
        semanticKey = "knowledge:fixture",
        statement = "The fixture relationship is stable.",
        relation = relation,
        evidenceFingerprint = StableFieldIds.fingerprint(
            "knowledge-observation-evidence",
            episode.id.value,
            relation.name,
        ),
        confidence = confidence,
    )

    private fun state(vararg episodes: LearningEpisode): LearningEpisodeState =
        LearningEpisodeState(
            revision = episodes.size.toLong(),
            episodes = episodes.sortedBy { it.id.value },
        )

    private fun episode(
        sourceCycleId: String,
        status: LearningEpisodeStatus,
        cycleRevision: Long = 1L,
        predecessorId: LearningEpisodeId? = null,
        createdAt: Instant = at,
    ): LearningEpisode {
        val summary = when (status) {
            LearningEpisodeStatus.INCOMPLETE_OUTCOME -> LearningEpisodeSummary(
                expectedActions = 1,
                missingObservations = 1,
                incompleteObservations = 0,
                unverifiedObservations = 0,
                withinExpectedBand = 0,
                outsideExpectedBand = 0,
                causalAssignments = 0,
            )
            LearningEpisodeStatus.UNVERIFIED_OUTCOME -> LearningEpisodeSummary(
                expectedActions = 1,
                missingObservations = 0,
                incompleteObservations = 0,
                unverifiedObservations = 1,
                withinExpectedBand = 0,
                outsideExpectedBand = 0,
                causalAssignments = 0,
            )
            LearningEpisodeStatus.VERIFIED_OUTCOME -> LearningEpisodeSummary(
                expectedActions = 1,
                missingObservations = 0,
                incompleteObservations = 0,
                unverifiedObservations = 0,
                withinExpectedBand = 1,
                outsideExpectedBand = 0,
                causalAssignments = 0,
            )
            LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT -> LearningEpisodeSummary(
                expectedActions = 1,
                missingObservations = 0,
                incompleteObservations = 0,
                unverifiedObservations = 0,
                withinExpectedBand = 1,
                outsideExpectedBand = 0,
                causalAssignments = 1,
            )
        }
        val problemId = ProblemStateGraphId(
            ProblemStateGraphId.PREFIX + StableFieldIds.fingerprint("problem", sourceCycleId)
        )
        val hypothesis = StableFieldIds.fingerprint("hypothesis", sourceCycleId)
        val search = StableFieldIds.fingerprint("search", sourceCycleId)
        val counterfactual = StableFieldIds.fingerprint("counterfactual", sourceCycleId)
        val plan = StableFieldIds.fingerprint("plan", sourceCycleId)
        val expectation = StableFieldIds.fingerprint("expectation", sourceCycleId)
        val error = StableFieldIds.fingerprint("error", sourceCycleId)
        val causal = if (status == LearningEpisodeStatus.VERIFIED_WITH_CAUSAL_CREDIT) {
            StableFieldIds.fingerprint("causal", sourceCycleId)
        } else {
            null
        }
        val fingerprint = StableFieldIds.fingerprint(
            "learning-episode/v1",
            sourceCycleId,
            cycleRevision.toString(),
            predecessorId?.value.orEmpty(),
            problemId.value,
            hypothesis,
            search,
            counterfactual,
            plan,
            expectation,
            error,
            causal.orEmpty(),
            status.name,
            summary.fingerprint(),
            createdAt.toString(),
        )
        return LearningEpisode(
            id = LearningEpisodeId(LearningEpisodeId.PREFIX + fingerprint),
            sourceCycleId = sourceCycleId,
            cycleRevision = cycleRevision,
            predecessorId = predecessorId,
            problemGraphId = problemId,
            hypothesisSeedFingerprint = hypothesis,
            reasoningSearchFingerprint = search,
            counterfactualBatchFingerprint = counterfactual,
            experimentPlanFingerprint = plan,
            expectationModelFingerprint = expectation,
            predictionErrorReportFingerprint = error,
            causalCreditReportFingerprint = causal,
            status = status,
            summary = summary,
            createdAt = createdAt,
        )
    }

    private fun conceptResult(sourceCycles: Set<String>): ConceptInductionResult {
        val signature = PatternSignature(
            sourceKind = ThoughtGraphNodeKind.EVIDENCE,
            relationKind = ThoughtGraphEdgeKind.SUPPORTS,
            targetKind = ThoughtGraphNodeKind.HYPOTHESIS,
        )
        val occurrences = sourceCycles.sorted().map { cycle ->
            PatternOccurrence(
                cycleId = cycle,
                workingSetFingerprint = StableFieldIds.fingerprint("working-set", cycle),
                nodeIds = setOf("evidence-" + cycle, "hypothesis-" + cycle),
                signature = signature,
            )
        }
        return ConceptInductionResult(
            cluster = CandidateCluster(signature, occurrences),
            supportCount = occurrences.size,
            counterexampleCount = 0,
            informationGain = 0.5,
            confidence = 0.8,
            sourceCycleIds = sourceCycles,
            inductionAlgorithmVersion = "structural-pattern-lite-v1",
        )
    }
}
