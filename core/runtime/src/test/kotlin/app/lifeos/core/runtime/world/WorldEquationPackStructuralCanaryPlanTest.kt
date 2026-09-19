package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest

class WorldEquationPackStructuralCanaryPlanTest {
    @Test
    fun plannerRequiresExactHealthyRecoveredValidationBundle() {
        val validation = validationBundle("candidate-a")
        val recovery = WorldEquationPackStructuralValidationRecoveryReport(
            state = WorldEquationPackStructuralValidationRecoveryState.HEALTHY,
            bundles = listOf(validation),
            unreadableEntries = emptyList(),
        )

        val plan = WorldEquationPackStructuralCanaryPlanner().create(
            validation = validation,
            recovery = recovery,
            maximumCases = 100,
            maximumConsecutiveFailures = 3,
        )

        assertEquals(validation.fingerprint, plan.validationBundleFingerprint)
        assertEquals(validation.candidatePackFingerprint, plan.candidatePackFingerprint)
        assertFalse(plan.executionAllowed)
        assertFalse(plan.productiveActivationAllowed)
        assertFalse(plan.productiveWorldMutationAllowed)
        assertFalse(plan.promotionAdmissionAllowed)
    }

    @Test
    fun corruptedRecoveryBlocksCanaryDesign() {
        val validation = validationBundle("candidate-b")
        val recovery = WorldEquationPackStructuralValidationRecoveryReport(
            state = WorldEquationPackStructuralValidationRecoveryState.CORRUPTED,
            bundles = listOf(validation),
            unreadableEntries = listOf("broken.wepkv"),
        )

        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackStructuralCanaryPlanner().create(
                validation = validation,
                recovery = recovery,
                maximumCases = 10,
                maximumConsecutiveFailures = 1,
            )
        }
    }

    @Test
    fun recoveryForAnotherCandidateCannotAuthorizeCanaryDesign() {
        val requested = validationBundle("candidate-requested")
        val recovered = validationBundle("candidate-recovered")
        val recovery = WorldEquationPackStructuralValidationRecoveryReport(
            state = WorldEquationPackStructuralValidationRecoveryState.HEALTHY,
            bundles = listOf(recovered),
            unreadableEntries = emptyList(),
        )

        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackStructuralCanaryPlanner().create(
                validation = requested,
                recovery = recovery,
                maximumCases = 10,
                maximumConsecutiveFailures = 1,
            )
        }
    }

    @Test
    fun codecRejectsTrailingBytesAndPreservesCanonicalPlan() {
        val validation = validationBundle("candidate-codec")
        val recovery = WorldEquationPackStructuralValidationRecoveryReport(
            state = WorldEquationPackStructuralValidationRecoveryState.HEALTHY,
            bundles = listOf(validation),
            unreadableEntries = emptyList(),
        )
        val plan = WorldEquationPackStructuralCanaryPlanner().create(
            validation = validation,
            recovery = recovery,
            maximumCases = 25,
            maximumConsecutiveFailures = 2,
        )

        val encoded = WorldEquationPackStructuralCanaryPlanCodec.encode(plan)
        assertEquals(plan, WorldEquationPackStructuralCanaryPlanCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackStructuralCanaryPlanCodec.decode(encoded + byteArrayOf(1))
        }
    }

    @Test
    fun coordinatorPersistsAndRecoversPlanIdempotently() = runTest {
        val validation = validationBundle("candidate-coordinator")
        val recovery = WorldEquationPackStructuralValidationRecoveryReport(
            state = WorldEquationPackStructuralValidationRecoveryState.HEALTHY,
            bundles = listOf(validation),
            unreadableEntries = emptyList(),
        )
        val repository = InMemoryWorldEquationPackStructuralCanaryPlanRepository()
        val coordinator = WorldEquationPackStructuralCanaryPlanCoordinator(repository)

        val first = coordinator.createAndPersist(
            validation = validation,
            recovery = recovery,
            maximumCases = 50,
            maximumConsecutiveFailures = 2,
        )
        val second = coordinator.createAndPersist(
            validation = validation,
            recovery = recovery,
            maximumCases = 50,
            maximumConsecutiveFailures = 2,
        )

        assertEquals(first, second)
        assertEquals(first, coordinator.recover(first.fingerprint))
        assertEquals(listOf(first), repository.loadReport().plans)
        assertFalse(first.executionAllowed)
        assertFalse(first.productiveActivationAllowed)
    }

    private fun validationBundle(
        suffix: String,
    ): WorldEquationPackStructuralValidationBundle {
        val baselinePackVersion = "baseline-v1"
        val baselinePackFingerprint = "baseline-pack-fingerprint"
        val baselineStructuralFingerprint = "baseline-structural-fingerprint"
        val candidatePackVersion = "candidate-v1-" + suffix
        val candidatePackFingerprint = "candidate-pack-fingerprint-" + suffix
        val candidateStructuralFingerprint = "candidate-structural-fingerprint-" + suffix
        val structuralPreflightId = "preflight-" + suffix
        val evidenceRecordFingerprint = "record-" + suffix
        val evidenceSetFingerprint = "evidence-" + suffix
        val shadowAssessmentId = "assessment-" + suffix
        val registrySnapshotId = "registry-snapshot"
        val registryFingerprint = "registry-fingerprint"
        val protocolFingerprint = "protocol-fingerprint"
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
