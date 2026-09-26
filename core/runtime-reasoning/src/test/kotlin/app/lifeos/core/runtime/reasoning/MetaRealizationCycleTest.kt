package app.lifeos.core.runtime.reasoning

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MetaRealizationCycleTest {
    private val t0 = Instant.parse("2026-09-26T03:00:00Z")

    @Test
    fun `cycle advances through explicit non-authoritative stages and rebases`() {
        val source = state(revision = 1L, semantic = "personal:v1")
        val profile = profile()
        val frozen = MetaRealizationCycle.start(source, profile)
        val predictive = frozen.withPredictiveState("quotient:1")
        val info = predictive.requireInformation("identifiability:1")
        val awaiting = info.awaitObservation("plan:1")
        val observed = awaiting.observe("observation:1")
        val successor = state(
            revision = 2L,
            semantic = "personal:v2",
            predecessorRevisionId = source.revisionId,
            transitionFingerprint = "observation:1",
        )
        val rebased = observed.rebase(successor.revisionId)

        assertEquals(MetaRealizationCycleState.REBASED, rebased.state)
        assertEquals(successor.revisionId, rebased.successorRealizationRevisionId)
        assertNotEquals(source.revisionId, rebased.successorRealizationRevisionId)
        assertFalse(rebased.executionAuthority)
        assertFalse(rebased.directWorldMutationAllowed)
    }

    @Test
    fun `cycle start rejects state missing frozen profile component`() {
        val source = CanonicalRealizationState.create(
            revision = 1L,
            asOf = t0,
            components = listOf(
                RealizationComponentRef(
                    kind = RealizationComponentKind.COGNITIVE_STATE,
                    representationId = "cognition:r1",
                    semanticFingerprint = "cognition:v1",
                )
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            MetaRealizationCycle.start(source, profile())
        }
    }

    @Test
    fun `unresolved state cannot fabricate successor revision`() {
        val cycle = MetaRealizationCycle.start(state(1L, "personal:v1"), profile())
            .withPredictiveState("quotient:1")
            .requireInformation("identifiability:1")
            .unresolved()

        assertEquals(MetaRealizationCycleState.UNRESOLVED, cycle.state)
        assertEquals(null, cycle.successorRealizationRevisionId)
    }

    private fun state(
        revision: Long,
        semantic: String,
        predecessorRevisionId: String? = null,
        transitionFingerprint: String? = null,
    ) = CanonicalRealizationState.create(
        revision = revision,
        asOf = t0.plusSeconds(revision - 1),
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

    private fun profile() = RealizationTransferProfile.create(
        version = "v2.2",
        requiredComponents = listOf(RealizationComponentKind.PERSONAL_CONTEXT),
        projectionRegistryFingerprint = "projection-registry:v1",
        stateContractFingerprint = "state-contract:v1",
        observableIds = listOf("personal.context"),
        invariantIds = listOf("truth-not-permission"),
        failureCriterionIds = listOf("insufficient-evidence"),
        frozenAt = t0,
    )
}
