package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceCodec
import app.lifeos.core.runtime.evolution.WorldEquationEvidencePartition
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceSet
import app.lifeos.core.runtime.evolution.WorldEquationEvaluationProtocol
import app.lifeos.core.runtime.evolution.WorldEquationLifecycleState
import app.lifeos.core.runtime.evolution.WorldEquationPrimaryMetric
import app.lifeos.core.runtime.evolution.WorldEquationPromotionDecision
import app.lifeos.core.runtime.evolution.WorldEquationPromotionEvaluator
import app.lifeos.core.runtime.evolution.WorldEquationPromotionPolicy
import app.lifeos.core.runtime.evolution.WorldEquationRunMetrics
import app.lifeos.core.runtime.evolution.WorldEquationShadowObservation
import app.lifeos.core.runtime.evolution.WorldEquationEvidenceRecord
import app.lifeos.core.runtime.level7.EvidenceActionKind
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MetaRealizationGoldTest {
    private val t0 = Instant.parse("2026-09-25T14:00:00Z")

    @Test
    fun `full shadow cycle preserves epistemic authority and replay invariants`() {
        val source = sourceState()
        val profile = profile()
        val projection = projectionNonClosure(profile)
        assertEquals(ProjectionClosureStatus.NOT_CLOSED, projection.status)
        assertFalse(projection.autonomousProjectionEstablished)

        val law = DiscreteFutureLaw.create(
            mapOf("continue" to 700_000L, "pause" to 300_000L)
        )
        val predictiveClasses = PredictiveStateQuotient().exact(
            listOf(
                PredictiveHistory(profile.fingerprint, "history:a", law),
                PredictiveHistory(profile.fingerprint, "history:b", law),
            )
        )
        assertEquals(1, predictiveClasses.size)
        assertFalse(predictiveClasses.single().truthAuthority)
        assertFalse(predictiveClasses.single().executionAuthority)

        val finite = FinitePredictiveEquivalenceClassifier().classify(
            EmpiricalPredictiveHistory(
                profile.fingerprint,
                "history:a",
                EmpiricalFutureCounts.create(mapOf("continue" to 5L, "pause" to 5L)),
            ),
            EmpiricalPredictiveHistory(
                profile.fingerprint,
                "history:b",
                EmpiricalFutureCounts.create(mapOf("continue" to 5L, "pause" to 5L)),
            ),
        )
        assertEquals(PredictiveEquivalenceDecision.UNRESOLVED, finite.decision)
        assertFalse(finite.truthAuthority)

        val identifiability = PredictiveIdentifiabilityAnalyzer().assess(
            listOf(
                PredictiveCandidateSignature.create(
                    realizationProfileFingerprint = profile.fingerprint,
                    candidateId = "candidate:a",
                    observationalLawFingerprint = "observational:same",
                    interventionalLawFingerprints = mapOf(
                        "ask-owner" to "interventional:a"
                    ),
                ),
                PredictiveCandidateSignature.create(
                    realizationProfileFingerprint = profile.fingerprint,
                    candidateId = "candidate:b",
                    observationalLawFingerprint = "observational:same",
                    interventionalLawFingerprints = mapOf(
                        "ask-owner" to "interventional:b"
                    ),
                ),
            )
        )
        assertEquals(IdentifiabilityStatus.INTERVENTION_SEPARABLE, identifiability.status)
        assertFalse(identifiability.causalAuthority)

        val plan = InformationActionPlanner().plan(
            sourceCycleId = "meta-cycle:gold",
            budgetFingerprint = "budget:gold",
            identifiability = identifiability,
            allowedKinds = setOf(EvidenceActionKind.ASK_USER),
            candidates = listOf(
                InformationActionCandidate.create(
                    interventionId = "ask-owner",
                    kind = EvidenceActionKind.ASK_USER,
                    expectedInformationGainMicros = 800_000L,
                    privacyCostMicros = 100_000L,
                    reversibilityMicros = INFORMATION_SCORE_SCALE,
                    rationale = "Resolve observational equivalence with owner input",
                )
            ),
        )
        assertEquals(1, plan.items.size)
        assertFalse(plan.executionAuthority)
        assertFalse(plan.items.single().request.executionAuthority)

        val coupling = CrossDomainCouplingState.create(
            realizationProfileFingerprint = profile.fingerprint,
            localStates = listOf(
                DomainPredictiveStateRef(
                    domainId = StableFieldIds.domain("finance"),
                    predictiveStateId = "predictive:finance",
                    futureLawFingerprint = law.fingerprint,
                ),
                DomainPredictiveStateRef(
                    domainId = StableFieldIds.domain("time"),
                    predictiveStateId = "predictive:time",
                    futureLawFingerprint = law.fingerprint,
                ),
            ),
            interfaceVariables = listOf(
                CouplingInterfaceVariable.create(
                    id = "cashflow-time-pressure",
                    participatingDomains = listOf(
                        StableFieldIds.domain("finance"),
                        StableFieldIds.domain("time"),
                    ),
                    valueFingerprint = "coupling:value",
                    evidenceFingerprint = "coupling:evidence",
                )
            ),
        )
        assertFalse(coupling.causalAuthority)
        assertFalse(coupling.executionAuthority)

        val multiscale = MultiscaleClosureEvaluator(
            MultiscaleClosurePolicy(
                distanceMetricId = MultiscaleClosurePolicy.TOTAL_VARIATION_METRIC_ID,
                aggregationRuleFingerprint = "aggregation:gold",
                maximumApproximationErrorMicros = 50_000L,
            )
        ).evaluate(
            realizationProfileFingerprint = profile.fingerprint,
            samples = listOf(
                MultiscaleTransitionSample.create(
                    realizationProfileFingerprint = profile.fingerprint,
                    microStateFingerprint = "micro:1",
                    projectedMacroStateFingerprint = "macro:1",
                    projectedMicroSuccessorLaw = law,
                    macroSuccessorLaw = law,
                    evidenceFingerprint = "scale:evidence:1",
                ),
                MultiscaleTransitionSample.create(
                    realizationProfileFingerprint = profile.fingerprint,
                    microStateFingerprint = "micro:2",
                    projectedMacroStateFingerprint = "macro:2",
                    projectedMicroSuccessorLaw = law,
                    macroSuccessorLaw = law,
                    evidenceFingerprint = "scale:evidence:2",
                ),
            ),
        )
        assertEquals(MultiscaleClosureStatus.EXACT, multiscale.status)
        assertFalse(multiscale.truthAuthority)

        val quotientFingerprint = StableFieldIds.fingerprint(
            "meta-realization-gold-quotient/v1",
            *predictiveClasses.map { it.id }.sorted().toTypedArray(),
        )
        val successor = successorState(source)
        val rebased = MetaRealizationCycle.start(source, profile)
            .predictiveReady(quotientFingerprint)
            .assessIdentifiability(identifiability)
            .awaitObservation(plan)
            .observe("observation:owner-answer")
            .rebase(successor, "prediction-error:gold")

        assertEquals(MetaRealizationCycleState.REBASED, rebased.state)
        assertEquals(successor.revisionId, rebased.successorRevisionId)
        assertFalse(rebased.executionAuthority)
        assertFalse(rebased.directWorldMutationAllowed)
        assertNotEquals(source.revisionId, successor.revisionId)

        val replay = MetaRealizationCycle.start(source, profile)
            .predictiveReady(quotientFingerprint)
            .assessIdentifiability(identifiability)
            .awaitObservation(plan)
            .observe("observation:owner-answer")
            .rebase(successor, "prediction-error:gold")

        assertEquals(rebased.fingerprint, replay.fingerprint)
        assertEquals(rebased.cycleId, replay.cycleId)
    }

    @Test
    fun `world equation anti vacuity requires frozen attestation and exclusion evidence`() {
        val baseline = CognitiveWorldEquationProfile().spec
        val first = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = baseline.version + "-meta-gold",
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        }
                    )
                } else {
                    it
                }
            },
        )
        val protocol = WorldEquationEvaluationProtocol(
            version = "meta-realization-v2.2",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
            realizationProfileFingerprint = "realization-profile:gold",
            stateSpaceFingerprint = "state-space:gold",
            observableContractFingerprint = "observables:gold",
            failureCriteriaFingerprint = "failures:gold",
            minimumExclusionRuns = 1,
        )
        val observations = listOf(
            observation(baseline.fingerprint(), candidate.fingerprint(), first.id, "run-1", WorldEquationEvidencePartition.SHADOW),
            observation(baseline.fingerprint(), candidate.fingerprint(), first.id, "run-2", WorldEquationEvidencePartition.HOLDOUT),
            observation(baseline.fingerprint(), candidate.fingerprint(), first.id, "run-3", WorldEquationEvidencePartition.EXCLUSION),
        )
        val evidence = WorldEquationEvidenceSet.empty(
            candidate = candidate,
            baseline = baseline,
            protocol = protocol,
            policyFingerprint = WorldEquationPromotionPolicy.V1.fingerprint(),
        ).copy(observations = observations)

        val verdict = WorldEquationPromotionEvaluator().evaluate(
            candidate,
            baseline,
            evidence,
        )
        assertEquals(WorldEquationPromotionDecision.PROMOTABLE, verdict.decision)
        assertEquals(
            app.lifeos.core.runtime.evolution.WorldEquationGateStatus.PASS,
            verdict.gateResults.single {
                it.gate == app.lifeos.core.runtime.evolution.WorldEquationEvidenceGate.ANTI_VACUITY
            }.status,
        )

        val record = WorldEquationEvidenceRecord.create(
            revision = 1L,
            state = WorldEquationLifecycleState.PROMOTABLE,
            evidence = evidence,
            latestVerdictId = verdict.id,
        )
        assertEquals(
            record,
            WorldEquationEvidenceCodec.decode(WorldEquationEvidenceCodec.encode(record)),
        )

        val legacyProtocol = WorldEquationEvaluationProtocol(
            version = "legacy-unattested",
            primaryMetric = WorldEquationPrimaryMetric.STABILIZATION_ITERATIONS,
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumActiveObservationsPerChangedCoefficient = 1,
        )
        val legacyEvidence = WorldEquationEvidenceSet.empty(
            candidate = candidate,
            baseline = baseline,
            protocol = legacyProtocol,
            policyFingerprint = WorldEquationPromotionPolicy.V1.fingerprint(),
        ).copy(observations = observations)

        val legacyVerdict = WorldEquationPromotionEvaluator().evaluate(
            candidate,
            baseline,
            legacyEvidence,
        )
        assertTrue(legacyVerdict.decision != WorldEquationPromotionDecision.PROMOTABLE)
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
                    provenanceFingerprints = listOf("evidence:personal"),
                ),
                RealizationComponentRef(
                    kind = RealizationComponentKind.PRODUCTIVE_WORLD,
                    representationId = "world:r1",
                    semanticFingerprint = "world:s1",
                    provenanceFingerprints = listOf("evidence:world"),
                ),
            ),
        )

    private fun successorState(
        source: CanonicalRealizationState,
    ): CanonicalRealizationState =
        CanonicalRealizationState.create(
            revision = 2L,
            asOf = t0.plusSeconds(1),
            predecessorRevisionId = source.revisionId,
            transitionFingerprint = "prediction-error:gold",
            components = listOf(
                RealizationComponentRef(
                    kind = RealizationComponentKind.PERSONAL_CONTEXT,
                    representationId = "personal:r2",
                    semanticFingerprint = "personal:s2",
                    provenanceFingerprints = listOf("evidence:personal", "observation:owner-answer"),
                ),
                RealizationComponentRef(
                    kind = RealizationComponentKind.PRODUCTIVE_WORLD,
                    representationId = "world:r2",
                    semanticFingerprint = "world:s2",
                    provenanceFingerprints = listOf("evidence:world", "observation:owner-answer"),
                ),
            ),
        )

    private fun profile(): RealizationTransferProfile =
        RealizationTransferProfile.create(
            version = "v2.2",
            requiredComponents = listOf(
                RealizationComponentKind.PERSONAL_CONTEXT,
                RealizationComponentKind.PRODUCTIVE_WORLD,
            ),
            projectionRegistryFingerprint = "projection-registry:gold",
            stateContractFingerprint = "state-contract:gold",
            observableIds = listOf("owner.context", "world.head"),
            allowedAuxiliaryVariableIds = listOf("memory.activation"),
            invariantIds = listOf(
                "prediction-not-outcome",
                "truth-not-permission",
            ),
            failureCriterionIds = listOf(
                "projection-not-closed",
                "unresolved-identifiability",
            ),
            frozenAt = t0,
        )

    private fun projectionNonClosure(
        profile: RealizationTransferProfile,
    ): ProjectionClosureResult =
        ProjectionClosureEvaluator().evaluate(
            listOf(
                ProjectionTransitionSample(
                    realizationProfileFingerprint = profile.fingerprint,
                    projectionId = "projection:sein",
                    sourceRevisionId = "hidden:r1",
                    sourceProjectionFingerprint = "sein:same",
                    successorRevisionId = "hidden:r2",
                    successorProjectionFingerprint = "sein:future-a",
                    evidenceFingerprint = "evidence:a",
                ),
                ProjectionTransitionSample(
                    realizationProfileFingerprint = profile.fingerprint,
                    projectionId = "projection:sein",
                    sourceRevisionId = "hidden:r3",
                    sourceProjectionFingerprint = "sein:same",
                    successorRevisionId = "hidden:r4",
                    successorProjectionFingerprint = "sein:future-b",
                    evidenceFingerprint = "evidence:b",
                ),
            )
        )

    private fun observation(
        baseline: String,
        candidate: String,
        coefficientId: app.lifeos.core.field.world.WorldCoefficientId,
        runId: String,
        partition: WorldEquationEvidencePartition,
    ): WorldEquationShadowObservation =
        WorldEquationShadowObservation(
            caseFingerprint = StableFieldIds.fingerprint(
                "meta-realization-gold-case/v1",
                runId,
            ),
            runId = runId,
            workloadId = "meta-gold-workload",
            baselineEquationFingerprint = baseline,
            candidateEquationFingerprint = candidate,
            partition = partition,
            baseline = WorldEquationRunMetrics(
                status = WorldFormulaStatus.CONVERGED,
                iterationCount = 5,
                conflictCount = 0,
                anomalyCount = 0,
                terminalDelta = 0.0,
                activeCoefficientIds = setOf(coefficientId),
            ),
            candidate = WorldEquationRunMetrics(
                status = WorldFormulaStatus.CONVERGED,
                iterationCount = 3,
                conflictCount = 0,
                anomalyCount = 0,
                terminalDelta = 0.0,
                activeCoefficientIds = setOf(coefficientId),
            ),
        )
}
