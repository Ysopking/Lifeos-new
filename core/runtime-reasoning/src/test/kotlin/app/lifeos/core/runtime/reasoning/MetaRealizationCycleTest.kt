package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.EvidenceActionKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MetaRealizationCycleTest {
    private val t0 = Instant.parse("2026-09-25T13:00:00Z")

    @Test
    fun `cycle advances through non authoritative information loop and rebases`() {
        val source = sourceState()
        val profile = profile()
        val identifiability = IdentifiabilityAssessment.create(
            realizationProfileFingerprint = profile.fingerprint,
            candidateIds = listOf("a", "b"),
            status = IdentifiabilityStatus.UNRESOLVED,
            sharedInterventionIds = emptyList(),
            discriminatingInterventionIds = emptyList(),
        )
        val plan = InformationActionPlanner().plan(
            sourceCycleId = "external-cycle",
            budgetFingerprint = "budget",
            identifiability = identifiability,
            allowedKinds = setOf(EvidenceActionKind.ASK_USER),
            candidates = listOf(
                InformationActionCandidate.create(
                    interventionId = "ask",
                    kind = EvidenceActionKind.ASK_USER,
                    expectedInformationGainMicros = 500_000L,
                    rationale = "resolve ambiguity",
                )
            ),
        )

        val frozen = MetaRealizationCycle.start(source, profile)
        val predictive = frozen.predictiveReady("predictive-quotient")
        val required = predictive.assessIdentifiability(identifiability)
        val awaiting = required.awaitObservation(plan)
        val observed = awaiting.observe("observation:1")
        val successor = CanonicalRealizationState.create(
            revision = 2L,
            asOf = t0.plusSeconds(1),
            predecessorRevisionId = source.revisionId,
            transitionFingerprint = "transition:1",
            components = source.components.map {
                it.copy(
                    representationId = it.representationId + ":next",
                    semanticFingerprint = it.semanticFingerprint + ":next",
                )
            },
        )
        val rebased = observed.rebase(successor, "transition:1")

        assertEquals(MetaRealizationCycleState.REBASED, rebased.state)
        assertEquals(successor.revisionId, rebased.successorRevisionId)
        assertFalse(rebased.executionAuthority)
        assertFalse(rebased.directWorldMutationAllowed)
    }

    @Test
    fun `rebase rejects successor not linked to source revision`() {
        val source = sourceState()
        val profile = profile()
        val identifiability = IdentifiabilityAssessment.create(
            profile.fingerprint,
            listOf("a", "b"),
            IdentifiabilityStatus.UNRESOLVED,
            emptyList(),
            emptyList(),
        )
        val plan = InformationActionPlanner().plan(
            "external-cycle",
            "budget",
            identifiability,
            setOf(EvidenceActionKind.ASK_USER),
            listOf(
                InformationActionCandidate.create(
                    "ask",
                    EvidenceActionKind.ASK_USER,
                    500_000L,
                    rationale = "resolve",
                )
            ),
        )
        val observed = MetaRealizationCycle.start(source, profile)
            .predictiveReady("predictive")
            .assessIdentifiability(identifiability)
            .awaitObservation(plan)
            .observe("observation")

        val invalidSuccessor = CanonicalRealizationState.create(
            revision = 2L,
            asOf = t0.plusSeconds(1),
            predecessorRevisionId = "other-revision",
            transitionFingerprint = "transition",
            components = source.components,
        )

        assertFailsWith<IllegalArgumentException> {
            observed.rebase(invalidSuccessor, "transition")
        }
    }

    private fun sourceState(): CanonicalRealizationState =
        CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(
                RealizationComponentRef(
                    kind = RealizationComponentKind.PERSONAL_CONTEXT,
                    representationId = "personal:r1",
                    semanticFingerprint = "personal:s1",
                )
            ),
        )

    private fun profile(): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "v2.2",
            requiredComponents = listOf(RealizationComponentKind.PERSONAL_CONTEXT),
            projectionRegistryFingerprint = "projection-registry",
            stateContractFingerprint = "state-contract",
            observableIds = listOf("personal.context"),
            invariantIds = listOf("truth-not-permission"),
            failureCriterionIds = listOf("unresolved"),
            frozenAt = t0,
        )
}
