package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.EvidenceActionKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MetaRealizationCycleTest {
    private val coordinator = MetaRealizationCycleCoordinator()
    private val t0 = Instant.parse("2026-09-25T13:00:00Z")

    @Test
    fun `full information cycle rebases only onto successor bound to source revision`() {
        val source = realization(revision = 1L, semantic = "s1")
        val predictive = predictiveState()
        val identifiability = unresolvedIdentifiability()
        val plan = informationPlan(identifiability)

        val started = MetaRealizationCycle.start(source, "profile:v2.2")
        val predicted = coordinator.recordPredictiveState(started, predictive)
        val identified = coordinator.recordIdentifiability(predicted, identifiability)
        val informationRequired = coordinator.requireInformation(identified)
        val awaiting = coordinator.attachInformationPlan(informationRequired, plan)
        val observed = coordinator.recordObservation(awaiting, "observation:verified")
        val successor = realization(
            revision = 2L,
            semantic = "s2",
            predecessorRevisionId = source.revisionId,
            transitionFingerprint = "transition:outcome",
        )
        val rebased = coordinator.rebase(observed, successor)

        assertEquals(MetaRealizationCycleState.REBASED, rebased.state)
        assertEquals(successor.revisionId, rebased.successorRealizationRevisionId)
        assertEquals("transition:outcome", rebased.transitionFingerprint)
        assertEquals(started.cycleId, rebased.cycleId)
        assertNotEquals(started.fingerprint, rebased.fingerprint)
    }

    @Test
    fun `observation may follow identifiability directly when no information action is required`() {
        val source = realization(1L, "s1")
        val identified = coordinator.recordIdentifiability(
            coordinator.recordPredictiveState(
                MetaRealizationCycle.start(source, "profile:v2.2"),
                predictiveState(),
            ),
            unresolvedIdentifiability(),
        )

        val observed = coordinator.recordObservation(
            identified,
            "observation:direct",
        )

        assertEquals(MetaRealizationCycleState.OUTCOME_OBSERVED, observed.state)
        assertEquals("observation:direct", observed.observationFingerprint)
    }

    @Test
    fun `information plan cannot attach before explicit information-required transition`() {
        val source = realization(1L, "s1")
        val identifiability = unresolvedIdentifiability()
        val identified = coordinator.recordIdentifiability(
            coordinator.recordPredictiveState(
                MetaRealizationCycle.start(source, "profile:v2.2"),
                predictiveState(),
            ),
            identifiability,
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.attachInformationPlan(
                identified,
                informationPlan(identifiability),
            )
        }
    }

    @Test
    fun `rebase rejects successor that is not derived from frozen source revision`() {
        val source = realization(1L, "s1")
        val observed = coordinator.recordObservation(
            coordinator.recordIdentifiability(
                coordinator.recordPredictiveState(
                    MetaRealizationCycle.start(source, "profile:v2.2"),
                    predictiveState(),
                ),
                unresolvedIdentifiability(),
            ),
            "observation",
        )
        val wrongSuccessor = realization(
            revision = 2L,
            semantic = "s2",
            predecessorRevisionId = "realization-revision:other",
            transitionFingerprint = "transition",
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.rebase(observed, wrongSuccessor)
        }
    }

    @Test
    fun `cycle may terminate unresolved without inventing successor state`() {
        val source = realization(1L, "s1")
        val started = MetaRealizationCycle.start(source, "profile:v2.2")

        val unresolved = coordinator.unresolved(
            started,
            "insufficient-observability",
        )

        assertEquals(MetaRealizationCycleState.UNRESOLVED, unresolved.state)
        assertEquals("insufficient-observability", unresolved.unresolvedReason)
        assertEquals(null, unresolved.successorRealizationRevisionId)
    }

    @Test
    fun `cycle grants no truth execution or direct world mutation authority`() {
        val cycle = MetaRealizationCycle.start(
            realization(1L, "s1"),
            "profile:v2.2",
        )

        assertFalse(cycle.truthAuthority)
        assertFalse(cycle.executionAuthority)
        assertFalse(cycle.directWorldMutationAllowed)
    }

    @Test
    fun `replay of same transitions is deterministic`() {
        fun run(): MetaRealizationCycle {
            val source = realization(1L, "s1")
            val started = MetaRealizationCycle.start(source, "profile:v2.2")
            val predicted = coordinator.recordPredictiveState(started, predictiveState())
            val identified = coordinator.recordIdentifiability(
                predicted,
                unresolvedIdentifiability(),
            )
            return coordinator.recordObservation(identified, "observation:1")
        }

        assertEquals(run(), run())
    }

    private fun realization(
        revision: Long,
        semantic: String,
        predecessorRevisionId: String? = null,
        transitionFingerprint: String? = null,
    ): CanonicalRealizationState =
        CanonicalRealizationState.create(
            revision = revision,
            asOf = t0.plusSeconds(revision),
            predecessorRevisionId = predecessorRevisionId,
            transitionFingerprint = transitionFingerprint,
            components = listOf(
                RealizationComponentRef(
                    kind = RealizationComponentKind.PERSONAL_CONTEXT,
                    representationId = "personal:r$revision",
                    semanticFingerprint = semantic,
                )
            ),
        )

    private fun predictiveState(): PredictiveStateClass =
        PredictiveStateClass.create(
            realizationProfileFingerprint = "profile:v2.2",
            memberHistoryFingerprints = listOf("history:1"),
            futureLaw = DiscreteFutureLaw.create(
                mapOf("next" to PREDICTIVE_PROBABILITY_SCALE)
            ),
        )

    private fun unresolvedIdentifiability(): IdentifiabilityAssessment =
        IdentifiabilityAssessment.create(
            realizationProfileFingerprint = "profile:v2.2",
            candidateIds = listOf("candidate:a", "candidate:b"),
            status = IdentifiabilityStatus.UNRESOLVED,
            sharedInterventionIds = emptyList(),
            discriminatingInterventionIds = emptyList(),
        )

    private fun informationPlan(
        identifiability: IdentifiabilityAssessment,
    ): InformationActionPlan =
        InformationActionPlanner(
            InformationActionPolicy(maxActions = 1)
        ).plan(
            sourceCycleId = "cycle:source",
            budgetFingerprint = "budget:1",
            identifiability = identifiability,
            allowedKinds = setOf(EvidenceActionKind.ASK_USER),
            candidates = listOf(
                InformationActionCandidate.create(
                    interventionId = "ask-owner",
                    kind = EvidenceActionKind.ASK_USER,
                    expectedInformationGainMicros = 500_000L,
                    rationale = "clarify",
                )
            ),
        )
}
