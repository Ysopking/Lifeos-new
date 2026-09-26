package app.lifeos.core.runtime.reasoning

import app.lifeos.core.runtime.level7.EvidenceActionKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MetaRealizationGoldTest {
    private val t0 = Instant.parse("2026-09-26T03:30:00Z")

    @Test
    fun `full meta realization shadow cycle preserves epistemic and authority boundaries`() {
        val profile = profile()
        val source = state(
            revision = 1L,
            semantic = "personal:v1",
            asOf = t0,
        )
        val closure = ProjectionClosureEvaluator().evaluate(
            listOf(
                ProjectionTransitionSample(
                    realizationProfileFingerprint = profile.fingerprint,
                    projectionId = "projection:sein",
                    sourceRevisionId = "hidden:r1",
                    sourceProjectionFingerprint = "sein:same",
                    successorRevisionId = "hidden:r1-next",
                    successorProjectionFingerprint = "sein:future-a",
                    evidenceFingerprint = "evidence:closure-a",
                ),
                ProjectionTransitionSample(
                    realizationProfileFingerprint = profile.fingerprint,
                    projectionId = "projection:sein",
                    sourceRevisionId = "hidden:r2",
                    sourceProjectionFingerprint = "sein:same",
                    successorRevisionId = "hidden:r2-next",
                    successorProjectionFingerprint = "sein:future-b",
                    evidenceFingerprint = "evidence:closure-b",
                ),
            )
        )
        assertEquals(ProjectionClosureStatus.NOT_CLOSED, closure.status)

        val lawA = DiscreteFutureLaw.create(
            mapOf("safe" to 700_000L, "blocked" to 300_000L)
        )
        val lawB = DiscreteFutureLaw.create(
            mapOf("safe" to 300_000L, "blocked" to 700_000L)
        )
        val quotient = PredictiveStateQuotient().exact(
            listOf(
                PredictiveHistory(profile.fingerprint, "history:a", lawA),
                PredictiveHistory(profile.fingerprint, "history:b", lawB),
            )
        )
        assertEquals(2, quotient.size)

        val empirical = FinitePredictiveEquivalenceClassifier(
            PredictiveEquivalencePolicy(
                minimumSamplesPerHistory = 20L,
                mergeMaximumDistanceMicros = 50_000L,
                splitMinimumDistanceMicros = 150_000L,
            )
        ).classify(
            EmpiricalPredictiveHistory(
                profile.fingerprint,
                "history:a",
                EmpiricalFutureCounts.create(
                    mapOf("safe" to 14L, "blocked" to 6L)
                ),
            ),
            EmpiricalPredictiveHistory(
                profile.fingerprint,
                "history:b",
                EmpiricalFutureCounts.create(
                    mapOf("safe" to 6L, "blocked" to 14L)
                ),
            ),
        )
        assertEquals(PredictiveEquivalenceDecision.SPLIT, empirical.decision)

        val identifiability = PredictiveIdentifiabilityAnalyzer().assess(
            listOf(
                PredictiveCandidateSignature.create(
                    realizationProfileFingerprint = profile.fingerprint,
                    candidateId = "candidate:a",
                    observationalLawFingerprint = "observed:same",
                    interventionalLawFingerprints = mapOf(
                        "ask-owner" to "law:a"
                    ),
                ),
                PredictiveCandidateSignature.create(
                    realizationProfileFingerprint = profile.fingerprint,
                    candidateId = "candidate:b",
                    observationalLawFingerprint = "observed:same",
                    interventionalLawFingerprints = mapOf(
                        "ask-owner" to "law:b"
                    ),
                ),
            )
        )
        assertEquals(
            IdentifiabilityStatus.INTERVENTION_SEPARABLE,
            identifiability.status,
        )

        val informationPlan = InformationActionPlanner(
            InformationActionPolicy(maxActions = 1)
        ).plan(
            sourceCycleId = "meta-cycle:gold",
            budgetFingerprint = "budget:gold",
            identifiability = identifiability,
            allowedKinds = setOf(EvidenceActionKind.ASK_USER),
            candidates = listOf(
                InformationActionCandidate.create(
                    interventionId = "ask-owner",
                    kind = EvidenceActionKind.ASK_USER,
                    expectedInformationGainMicros = 900_000L,
                    privacyCostMicros = 50_000L,
                    rationale = "Resolve predictive ambiguity with owner input",
                )
            ),
        )
        assertEquals(1, informationPlan.items.size)
        assertFalse(informationPlan.executionAuthority)
        assertFalse(informationPlan.items.single().request.executionAuthority)

        val coupling = CrossDomainCouplingState.create(
            realizationProfileFingerprint = profile.fingerprint,
            localStates = listOf(
                DomainPredictiveStateRef(
                    domainId = "finance",
                    predictiveStateId = quotient[0].id,
                    futureLawFingerprint = quotient[0].futureLaw.fingerprint,
                ),
                DomainPredictiveStateRef(
                    domainId = "sein",
                    predictiveStateId = quotient[1].id,
                    futureLawFingerprint = quotient[1].futureLaw.fingerprint,
                ),
            ),
            interfaceVariables = listOf(
                CouplingInterfaceVariable.create(
                    id = "agency-resource-coupling",
                    participatingDomainIds = listOf("finance", "sein"),
                    valueFingerprint = "value:coupling",
                    evidenceFingerprint = "evidence:coupling",
                )
            ),
        )
        assertFalse(coupling.causalAuthority)
        assertFalse(coupling.executionAuthority)

        val multiscale = MultiscaleClosureEvaluator(
            MultiscaleClosurePolicy(
                aggregationRuleFingerprint = "aggregation:gold",
                maximumApproximationErrorMicros = 50_000L,
            )
        ).evaluate(
            listOf(
                MultiscaleTransitionSample(
                    realizationProfileFingerprint = profile.fingerprint,
                    microStateFingerprint = "micro:1",
                    projectedMacroStateFingerprint = "macro:1",
                    projectedMicroSuccessorLaw = lawA,
                    macroSuccessorLaw = DiscreteFutureLaw.create(
                        mapOf("safe" to 680_000L, "blocked" to 320_000L)
                    ),
                    evidenceFingerprint = "evidence:multiscale",
                )
            )
        )
        assertEquals(MultiscaleClosureStatus.APPROXIMATE, multiscale.status)

        val cycle = MetaRealizationCycle.start(source, profile)
            .withPredictiveState(
                app.lifeos.core.field.StableFieldIds.fingerprint(
                    "quotient:gold",
                    *quotient.map { it.id }.toTypedArray(),
                )
            )
            .requireInformation(identifiability.fingerprint)
            .awaitObservation(informationPlan.fingerprint)
            .observe("observation:owner-answer")

        val successor = state(
            revision = 2L,
            semantic = "personal:v2",
            asOf = t0.plusSeconds(1),
            predecessorRevisionId = source.revisionId,
            transitionFingerprint = "observation:owner-answer",
        )
        val rebased = cycle.rebase(successor.revisionId)

        assertEquals(MetaRealizationCycleState.REBASED, rebased.state)
        assertNotEquals(
            source.representationFingerprint,
            successor.representationFingerprint,
        )
        assertFalse(source.truthAuthority)
        assertFalse(source.executionAuthority)
        assertFalse(profile.truthAuthority)
        assertFalse(profile.policyAuthority)
        assertFalse(profile.executionAuthority)
        assertFalse(empirical.truthAuthority)
        assertFalse(empirical.executionAuthority)
        assertFalse(identifiability.causalAuthority)
        assertFalse(identifiability.executionAuthority)
        assertFalse(multiscale.truthAuthority)
        assertFalse(rebased.executionAuthority)
        assertFalse(rebased.directWorldMutationAllowed)
    }

    @Test
    fun `canonical replay yields identical meta realization artifacts`() {
        val profile = profile()
        val first = PredictiveStateQuotient().exact(
            listOf(
                PredictiveHistory(
                    profile.fingerprint,
                    "history:b",
                    DiscreteFutureLaw.create(
                        mapOf("x" to 500_000L, "y" to 500_000L)
                    ),
                ),
                PredictiveHistory(
                    profile.fingerprint,
                    "history:a",
                    DiscreteFutureLaw.create(
                        mapOf("x" to 500_000L, "y" to 500_000L)
                    ),
                ),
            )
        )
        val replay = PredictiveStateQuotient().exact(
            listOf(
                PredictiveHistory(
                    profile.fingerprint,
                    "history:a",
                    DiscreteFutureLaw.create(
                        linkedMapOf("y" to 500_000L, "x" to 500_000L)
                    ),
                ),
                PredictiveHistory(
                    profile.fingerprint,
                    "history:b",
                    DiscreteFutureLaw.create(
                        linkedMapOf("y" to 500_000L, "x" to 500_000L)
                    ),
                ),
            )
        )

        assertEquals(first, replay)
        assertTrue(first.single().memberHistoryFingerprints == listOf("history:a", "history:b"))
    }

    private fun state(
        revision: Long,
        semantic: String,
        asOf: Instant,
        predecessorRevisionId: String? = null,
        transitionFingerprint: String? = null,
    ): CanonicalRealizationState =
        CanonicalRealizationState.create(
            revision = revision,
            asOf = asOf,
            predecessorRevisionId = predecessorRevisionId,
            transitionFingerprint = transitionFingerprint,
            components = listOf(
                RealizationComponentRef(
                    kind = RealizationComponentKind.PERSONAL_CONTEXT,
                    representationId = "personal:r$revision",
                    semanticFingerprint = semantic,
                    provenanceFingerprints = listOf("evidence:personal"),
                )
            ),
        )

    private fun profile(): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "v2.2",
            requiredComponents = listOf(
                RealizationComponentKind.PERSONAL_CONTEXT
            ),
            projectionRegistryFingerprint = "projection-registry:v1",
            stateContractFingerprint = "state-contract:v1",
            observableIds = listOf("personal.context", "sein.state"),
            allowedAuxiliaryVariableIds = listOf("memory.activation"),
            invariantIds = listOf(
                "observation-not-world",
                "truth-not-permission",
                "permission-not-execution",
            ),
            failureCriterionIds = listOf(
                "insufficient-evidence",
                "projection-not-closed",
            ),
            frozenAt = t0,
        )
}
