package app.lifeos.core.runtime.research

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutonomousWebDeviceGoldTest {
    @Test
    fun semantic_checkpoint_survives_process_epoch_change() {
        val before = checkpoint("process-a")
        val after = checkpoint("process-b")
        val recovery = AutonomousWebDeviceRecoveryGoldProof.from(before, after)

        assertTrue(before.semanticFingerprint == after.semanticFingerprint)
        assertTrue(before.processCheckpointFingerprint != after.processCheckpointFingerprint)
        assertTrue(recovery.beforeSemanticFingerprint == recovery.afterSemanticFingerprint)
    }

    @Test
    fun changed_semantic_proof_fails_recovery_gold() {
        val before = checkpoint("process-a")
        val after = AutonomousWebDeviceSemanticCheckpoint.create(
            capabilityExpansionProofFingerprint = "9".repeat(64),
            actionOutcomeProofFingerprint = "2".repeat(64),
            failureLearningProofFingerprint = "3".repeat(64),
            workflowTransferProofFingerprint = "4".repeat(64),
            processEpoch = "process-b",
        )

        assertFailsWith<IllegalArgumentException> {
            AutonomousWebDeviceRecoveryGoldProof.from(before, after)
        }
    }

    @Test
    fun final_gold_evidence_grants_no_truth_policy_permission_activation_or_execution_authority() {
        val capability = capabilityProof()
        val action = actionProof()
        val failure = failureProof()
        val workflow = workflowProof()
        val recovery = AutonomousWebDeviceRecoveryGoldProof.from(
            checkpoint("process-a"),
            checkpoint("process-b"),
        )
        val evidence = AutonomousWebDeviceGoldEvidence.create(
            capabilityExpansion = capability,
            actionOutcome = action,
            failureLearning = failure,
            workflowTransfer = workflow,
            recovery = recovery,
        )

        assertTrue(AutonomousWebDeviceGoldVerifier().verify(evidence))
        assertFalse(evidence.truthAuthority)
        assertFalse(evidence.ownerPolicyAuthority)
        assertFalse(evidence.permissionAuthority)
        assertFalse(evidence.activationAuthority)
        assertFalse(evidence.executionAuthority)
    }

    private fun checkpoint(epoch: String) = AutonomousWebDeviceSemanticCheckpoint.create(
        capabilityExpansionProofFingerprint = capabilityProof().fingerprint,
        actionOutcomeProofFingerprint = actionProof().fingerprint,
        failureLearningProofFingerprint = failureProof().fingerprint,
        workflowTransferProofFingerprint = workflowProof().fingerprint,
        processEpoch = epoch,
    )

    private fun capabilityProof(): CapabilityExpansionGoldProof {
        val values = listOf("1","2","3","4","5").map { it.repeat(64) }
        val fp = testFingerprint(
            "capability-expansion-gold-proof/v1",
            "capability.example",
            values[0], values[1], values[2], values[3], values[4],
        )
        return CapabilityExpansionGoldProof(
            capabilityId = "capability.example",
            discoveryReportFingerprint = values[0],
            discoveryCandidateFingerprint = values[1],
            toolCandidateFingerprint = values[2],
            workshopBriefFingerprint = values[3],
            buildStudioPlanFingerprint = values[4],
            fingerprint = fp,
        )
    }

    private fun actionProof(): ActionOutcomeGoldProof {
        val values = listOf("6","7","8","9").map { it.repeat(64) }
        val fp = testFingerprint(
            "action-outcome-gold-proof/v1",
            values[0], values[1], values[2], values[3], "resource-1",
        )
        return ActionOutcomeGoldProof(
            actionGraphRevisionFingerprint = values[0],
            outcomeLearningReportFingerprint = values[1],
            outcomeLearningCandidateFingerprint = values[2],
            dispatchPlanFingerprint = values[3],
            resourceIdentity = "resource-1",
            fingerprint = fp,
        )
    }

    private fun failureProof(): FailureLearningGoldProof {
        val report = "a".repeat(64)
        val candidates = listOf("b".repeat(64), "c".repeat(64))
        return FailureLearningGoldProof(
            failureReportFingerprint = report,
            failureCandidateFingerprints = candidates,
            fingerprint = testFingerprint(
                "failure-learning-gold-proof/v1",
                report,
                *candidates.toTypedArray(),
            ),
        )
    }

    private fun workflowProof(): WorkflowTransferGoldProof {
        val values = listOf("d","e","f","1","2").map { it.repeat(64) }
        return WorkflowTransferGoldProof(
            workflowInductionReportFingerprint = values[0],
            transferCandidateFingerprint = values[1],
            sourceSkillFingerprint = values[2],
            sourceSurfaceFingerprint = values[3],
            targetSurfaceFingerprint = values[4],
            fingerprint = testFingerprint(
                "workflow-transfer-gold-proof/v1",
                values[0], values[1], values[2], values[3], values[4],
            ),
        )
    }

    private fun testFingerprint(domain: String, vararg parts: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            val bytes = value.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            digest.update(
                byteArrayOf(
                    (bytes.size ushr 24).toByte(),
                    (bytes.size ushr 16).toByte(),
                    (bytes.size ushr 8).toByte(),
                    bytes.size.toByte(),
                )
            )
            digest.update(bytes)
        }
        update(domain)
        parts.forEach(::update)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
