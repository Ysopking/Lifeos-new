package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldEquationPackStructuralPromotionReviewTest {
    @Test
    fun canarySupportedEvidenceProducesOnlyExplicitReviewBundle() {
        val validation = validationBundle()
        val plan = WorldEquationPackStructuralCanaryPlan.create(
            validation = validation,
            maximumCases = 20,
            maximumConsecutiveFailures = 2,
        )
        val record = supportedRecord(validation, plan)

        val review = WorldEquationPackStructuralPromotionReviewGate().build(
            validation = validation,
            plan = plan,
            record = record,
        )

        assertEquals(validation.fingerprint, review.validationBundleFingerprint)
        assertEquals(plan.fingerprint, review.canaryPlanFingerprint)
        assertEquals(record.fingerprint, review.canaryEvidenceRecordFingerprint)
        assertEquals(record.latestAssessmentId, review.canaryAssessmentId)
        assertTrue(review.ownerApprovalRequired)
        assertFalse(review.automaticPromotionAllowed)
        assertFalse(review.productiveActivationAllowed)
        assertFalse(review.productiveWorldMutationAllowed)
        assertFalse(review.promotionAdmissionAllowed)
    }

    @Test
    fun reviewBoundaryRejectsCanaryStateWithoutSupportedLifecycle() {
        val validation = validationBundle()
        val plan = WorldEquationPackStructuralCanaryPlan.create(
            validation = validation,
            maximumCases = 20,
            maximumConsecutiveFailures = 2,
        )
        val supported = supportedRecord(validation, plan)
        val running = WorldEquationPackStructuralCanaryEvidenceRecord.create(
            revision = supported.revision,
            state = WorldEquationPackStructuralCanaryLifecycleState.CANARY,
            evidence = supported.evidence,
            latestAssessmentId = supported.latestAssessmentId,
        )

        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackStructuralPromotionReviewGate().build(
                validation = validation,
                plan = plan,
                record = running,
            )
        }
    }

    @Test
    fun promotionReviewCodecAndRepositoryPreserveImmutableIdentity() = runTest {
        val validation = validationBundle()
        val plan = WorldEquationPackStructuralCanaryPlan.create(
            validation = validation,
            maximumCases = 20,
            maximumConsecutiveFailures = 2,
        )
        val record = supportedRecord(validation, plan)
        val review = WorldEquationPackStructuralPromotionReviewGate().build(
            validation = validation,
            plan = plan,
            record = record,
        )

        val encoded = WorldEquationPackStructuralPromotionReviewCodec.encode(review)
        assertEquals(
            review,
            WorldEquationPackStructuralPromotionReviewCodec.decode(encoded),
        )
        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackStructuralPromotionReviewCodec.decode(
                encoded + byteArrayOf(1),
            )
        }

        val repository = InMemoryWorldEquationPackStructuralPromotionReviewRepository()
        val coordinator = WorldEquationPackStructuralPromotionReviewCoordinator(repository)
        val durable = coordinator.buildAndPersist(validation, plan, record)
        val repeated = coordinator.buildAndPersist(validation, plan, record)
        assertEquals(review, durable)
        assertEquals(durable, repeated)
        assertEquals(durable, coordinator.recover(durable.fingerprint))
        assertEquals(listOf(durable), repository.loadReport().bundles)
        assertTrue(durable.ownerApprovalRequired)
        assertFalse(durable.productiveActivationAllowed)
    }

    private fun supportedRecord(
        validation: WorldEquationPackStructuralValidationBundle,
        plan: WorldEquationPackStructuralCanaryPlan,
    ): WorldEquationPackStructuralCanaryEvidenceRecord {
        val protocol = WorldEquationPackStructuralCanaryProtocol(
            version = "promotion-review-test-v1",
            minimumIndependentCases = 2,
            minimumShadowReferences = 1,
            minimumHoldoutReferences = 1,
        )
        val evidence = WorldEquationPackStructuralCanaryEvidenceSet(
            planFingerprint = plan.fingerprint,
            candidatePackFingerprint = validation.candidatePackFingerprint,
            protocol = protocol,
            replays = listOf(
                replay(
                    validation,
                    plan,
                    "shadow",
                    WorldEquationPackEvidencePartition.SHADOW,
                ),
                replay(
                    validation,
                    plan,
                    "holdout",
                    WorldEquationPackEvidencePartition.HOLDOUT,
                ),
            ),
        )
        val assessment = WorldEquationPackStructuralCanaryEvidenceEvaluator()
            .evaluate(evidence)
        assertEquals(
            WorldEquationPackStructuralCanaryDecision.CANARY_SUPPORTED,
            assessment.decision,
        )
        return WorldEquationPackStructuralCanaryEvidenceRecord.create(
            revision = 5L,
            state = WorldEquationPackStructuralCanaryLifecycleState.CANARY_SUPPORTED,
            evidence = evidence,
            latestAssessmentId = assessment.id,
        )
    }

    private fun replay(
        validation: WorldEquationPackStructuralValidationBundle,
        plan: WorldEquationPackStructuralCanaryPlan,
        suffix: String,
        partition: WorldEquationPackEvidencePartition,
    ): WorldEquationPackStructuralCanaryReplay {
        val candidateMetrics = metrics("candidate-" + suffix)
        val baselineMetrics = metrics("baseline-" + suffix)
        val caseFingerprint = "case-" + suffix
        val reference = WorldEquationPackShadowObservation(
            caseFingerprint = caseFingerprint,
            runId = "run-" + suffix,
            workloadId = "workload-" + suffix,
            partition = partition,
            baselinePackFingerprint = validation.baselinePackFingerprint,
            candidatePackFingerprint = validation.candidatePackFingerprint,
            baseline = baselineMetrics,
            candidate = candidateMetrics,
        )
        val admissionFingerprint = "admission-" + suffix
        val canaryFingerprint = StableFieldIds.fingerprint(
            "world-equation-pack-structural-canary-observation/v1",
            caseFingerprint,
            plan.fingerprint,
            admissionFingerprint,
            validation.candidatePackFingerprint,
            candidateMetrics.fingerprint(),
        )
        val canary = WorldEquationPackStructuralCanaryObservation(
            caseFingerprint = caseFingerprint,
            planFingerprint = plan.fingerprint,
            admissionFingerprint = admissionFingerprint,
            candidatePackFingerprint = validation.candidatePackFingerprint,
            metrics = candidateMetrics,
            fingerprint = canaryFingerprint,
        )
        return WorldEquationPackStructuralCanaryReplay(reference, canary)
    }

    private fun metrics(
        suffix: String,
    ): WorldEquationPackRunMetrics =
        WorldEquationPackRunMetrics(
            status = WorldFormulaStatus.CONVERGED,
            iterationCount = 2,
            conflictCount = 0,
            anomalyCount = 0,
            terminalDelta = 0.0,
            activeCoefficientIds = emptySet(),
            materializationFingerprint = "materialization-" + suffix,
            graphFingerprint = "graph-" + suffix,
            finalStateFingerprint = "state-" + suffix,
        )

    private fun validationBundle(): WorldEquationPackStructuralValidationBundle {
        val baselinePackVersion = "review-baseline-v1"
        val baselinePackFingerprint = "review-baseline-pack"
        val baselineStructuralFingerprint = "review-baseline-structure"
        val candidatePackVersion = "review-candidate-v2"
        val candidatePackFingerprint = "review-candidate-pack"
        val candidateStructuralFingerprint = "review-candidate-structure"
        val structuralPreflightId = "review-preflight"
        val evidenceRecordFingerprint = "review-shadow-record"
        val evidenceSetFingerprint = "review-shadow-evidence"
        val shadowAssessmentId = "review-shadow-assessment"
        val registrySnapshotId = "review-registry"
        val registryFingerprint = "review-registry-fingerprint"
        val protocolFingerprint = "review-shadow-protocol"
        val fingerprint = StableFieldIds.fingerprint(
            "world-equation-pack-structural-validation/v1",
            baselinePackVersion,
            baselinePackFingerprint,
            baselineStructuralFingerprint,
            candidatePackVersion,
            candidatePackFingerprint,
            candidateStructuralFingerprint,
            structuralPreflightId,
            evidenceRecordFingerprint,
            evidenceSetFingerprint,
            shadowAssessmentId,
            registrySnapshotId,
            registryFingerprint,
            protocolFingerprint,
        )
        return WorldEquationPackStructuralValidationBundle.restore(
            baselinePackVersion = baselinePackVersion,
            baselinePackFingerprint = baselinePackFingerprint,
            baselineStructuralFingerprint = baselineStructuralFingerprint,
            candidatePackVersion = candidatePackVersion,
            candidatePackFingerprint = candidatePackFingerprint,
            candidateStructuralFingerprint = candidateStructuralFingerprint,
            structuralPreflightId = structuralPreflightId,
            evidenceRecordFingerprint = evidenceRecordFingerprint,
            evidenceSetFingerprint = evidenceSetFingerprint,
            shadowAssessmentId = shadowAssessmentId,
            registrySnapshotId = registrySnapshotId,
            registryFingerprint = registryFingerprint,
            protocolFingerprint = protocolFingerprint,
            fingerprint = fingerprint,
        )
    }
}
