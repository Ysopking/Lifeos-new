package app.lifeos.core.runtime.extension

import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectKind
import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectRef
import app.lifeos.core.runtime.evolution.ExtensionEvolutionAdmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class AutonomousExpansionGoldContractTest {
    @Test
    fun worldEquationGoldRequiresFullPromotionAndExactRollbackChain() {
        val fixture = fixture()
        val evidence = AutonomousExpansionGoldEvidence.create(
            gap = fixture.gap,
            graph = fixture.graph,
            candidate = fixture.candidate,
            workshop = fixture.workshop,
            validation = fixture.validation,
            evolution = fixture.evolution,
            promotionProof = fixture.promotionProof,
            hotSwapAuthorization = fixture.hotSwapAuthorization,
            hotSwapResult = fixture.applied,
            rollbackProof = fixture.rollbackProof,
            rollbackAuthorization = fixture.rollbackAuthorization,
            rollbackResult = fixture.rolledBack,
        )

        assertEquals(fixture.candidate.id, evidence.candidateId)
        assertEquals(
            fixture.applied.previousHead.activeSnapshotId,
            evidence.restoredSnapshotId,
        )
        assertFalse(evidence.activationAllowed)
        assertEquals(
            "NO_WORLD_EQUATION_ACTIVATION_WITHOUT_HOLDOUT_SHADOW_TRIAL_ROLLBACK",
            AutonomousExpansionGoldContract.WORLD_EQUATION_INVARIANT,
        )
    }

    @Test
    fun rejectsRollbackThatDoesNotRestoreExactPredecessor() {
        val fixture = fixture()
        val wrong = fixture.rolledBack.copy(
            currentHead = ExtensionRegistryHead.create(
                revision = fixture.rolledBack.currentHead.revision,
                snapshot = fixture.targetSnapshot,
                predecessorSnapshotId = fixture.rolledBack.previousHead.activeSnapshotId,
            )
        )

        assertFailsWith<IllegalArgumentException> {
            AutonomousExpansionGoldEvidence.create(
                gap = fixture.gap,
                graph = fixture.graph,
                candidate = fixture.candidate,
                workshop = fixture.workshop,
                validation = fixture.validation,
                evolution = fixture.evolution,
                promotionProof = fixture.promotionProof,
                hotSwapAuthorization = fixture.hotSwapAuthorization,
                hotSwapResult = fixture.applied,
                rollbackProof = fixture.rollbackProof,
                rollbackAuthorization = fixture.rollbackAuthorization,
                rollbackResult = wrong,
            )
        }
    }

    private fun fixture(): Fixture {
        val gap = ExtensionGap.create(
            kind = ExtensionGapKind.EQUATION_COVERAGE_GAP,
            semanticKey = "equation-coupling:test",
            reason = "required-world-coupling-not-covered-by-active-equation-contract",
            sourceFingerprint = "source-v1",
            requiredExtensionKinds = setOf(ExtensionKind.WORLD_EQUATION_PACK),
        )
        val requestId = app.lifeos.core.runtime.deepsearch.DeepSearchRequestId("request-1")
        val evidenceId = app.lifeos.core.runtime.deepsearch.DeepSearchEvidenceId("evidence-1")
        val claimId = app.lifeos.core.runtime.deepsearch.DeepSearchHypothesisId("claim-1")
        val graph = DeepSearchClaimGraph.create(
            requestId = requestId,
            sourceStatus = app.lifeos.core.runtime.deepsearch.DeepSearchStatus.UNRESOLVED,
            evidenceNodes = listOf(
                DeepSearchEvidenceNode(
                    app.lifeos.core.runtime.deepsearch.DeepSearchEvidence(
                        id = evidenceId,
                        requestId = requestId,
                        branchId = app.lifeos.core.runtime.deepsearch.DeepSearchBranchId("branch-1"),
                        sourceId = "source-1",
                        statement = "equation evidence",
                        confidence = 0.9,
                        sourcePhotonId = null,
                        fieldEvidenceId = null,
                        contradiction = false,
                    )
                )
            ),
            claimNodes = listOf(
                DeepSearchClaimNode(
                    hypothesis = app.lifeos.core.runtime.deepsearch.DeepSearchHypothesis(
                        id = claimId,
                        requestId = requestId,
                        statement = "equation coverage missing",
                        semanticTerms = setOf("equation", "coverage"),
                        confidence = 0.9,
                        evidenceIds = setOf(evidenceId),
                    ),
                    branchIds = setOf("branch-1"),
                )
            ),
            edges = listOf(
                DeepSearchClaimEdge(
                    evidenceId = evidenceId,
                    hypothesisId = claimId,
                    kind = DeepSearchClaimEdgeKind.SUPPORTS,
                )
            ),
            sourceTraceFingerprint = "trace-v1",
        )
        val candidate = ExtensionCandidateFactory().propose(
            ExtensionCandidateRequest(
                gap = gap,
                claimGraph = graph,
                selectedClaimIds = setOf(claimId),
                selectedEvidenceIds = setOf(evidenceId),
                rationale = "equation gap evidence",
            )
        )
        val spec = ExtensionWorkshopPlanner().plan(
            candidate = candidate,
            sourceCommit = "0123456789abcdef0123456789abcdef01234567",
            allowedPathPrefixes = setOf("core/runtime"),
            requiredTestPaths = setOf("core/runtime/src/test"),
        )
        val workshop = ExtensionWorkshopArtifact.create(
            spec,
            candidate,
            ExtensionWorkshopBuildEvidence(
                id = "build-evidence-1",
                sourceCommit = spec.sourceCommit,
                branchName = "extension/equation",
                branchHeadCommit = "89abcdef0123456789abcdef0123456789abcdef",
                debugArtifactRef = "app-debug.apk",
                debugArtifactSha256 = "a".repeat(64),
                activationAllowed = false,
            ),
        )
        val validation = ExtensionValidationBundle.create(
            workshop,
            ExtensionValidationEvidence(
                workshop.id,
                ExtensionValidationSuite.SANDBOX,
                "sandbox-validator",
                "buildstudio-host",
                true,
                "sandbox-pass",
            ),
            ExtensionValidationEvidence(
                workshop.id,
                ExtensionValidationSuite.GOLD,
                "gold-validator",
                "buildstudio-host",
                true,
                "gold-pass",
            ),
        )
        val subject = ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.WORLD_EQUATION_VERSION,
            candidateId = candidate.id,
            sourceArtifactId = workshop.id,
            validationBundleId = validation.id,
            candidateFingerprint = candidate.fingerprint(),
            baselineFingerprint = "baseline-equation-v1",
        )
        val evolution = ExtensionEvolutionAdmission(
            candidateId = candidate.id,
            validationBundleId = validation.id,
            subjects = listOf(subject),
        )
        val promotionProof = ExtensionPromotionProof.create(
            subjectId = subject.id,
            validationBundleId = validation.id,
            holdoutEvidenceId = "holdout-pass",
            shadowEvidenceId = "shadow-pass",
            trialEvidenceId = "trial-pass",
        )

        val initialSnapshot = snapshot("initial")
        val targetSnapshot = snapshot("target")
        val initialHead = ExtensionRegistryHead.create(1, initialSnapshot, null)
        val targetHead = ExtensionRegistryHead.create(2, targetSnapshot, initialSnapshot.id)
        val hotSwapAuthorization = ExtensionHotSwapAuthorization.create(
            subjectId = subject.id,
            promotionEvidenceId = promotionProof.id,
            expectedHeadFingerprint = initialHead.fingerprint,
            targetSnapshotId = targetSnapshot.id,
            targetSnapshotFingerprint = targetSnapshot.fingerprint(),
        )
        val applied = ExtensionHotSwapResult.Applied(
            previousHead = initialHead,
            currentHead = targetHead,
            authorizationId = hotSwapAuthorization.id,
        )
        val rollbackProof = SelfHealingExtensionRollbackProof(
            id = "extension-rollback-proof:" + app.lifeos.core.field.StableFieldIds.fingerprint(
                "extension-rollback-proof/v1",
                "incident-1",
                "3",
                "rollback",
                initialSnapshot.id,
                "rollback-evidence",
            ),
            incidentId = "incident-1",
            incidentLedgerRevision = 3,
            actionId = "rollback",
            restoreSnapshotId = initialSnapshot.id,
            evidenceFingerprint = "rollback-evidence",
        )
        val rollbackAuthorization = ExtensionRollbackAuthorization.create(
            rollbackEvidenceId = rollbackProof.id,
            expectedHeadFingerprint = targetHead.fingerprint,
            restoreSnapshotId = initialSnapshot.id,
            restoreSnapshotFingerprint = initialSnapshot.fingerprint(),
        )
        val restoredHead = ExtensionRegistryHead.create(3, initialSnapshot, targetSnapshot.id)
        val rolledBack = ExtensionHotSwapResult.RolledBack(
            previousHead = targetHead,
            currentHead = restoredHead,
            authorizationId = rollbackAuthorization.id,
        )
        return Fixture(
            gap,
            graph,
            candidate,
            workshop,
            validation,
            evolution,
            promotionProof,
            hotSwapAuthorization,
            applied,
            rollbackProof,
            rollbackAuthorization,
            rolledBack,
            targetSnapshot,
        )
    }

    private fun snapshot(name: String): ExtensionRegistrySnapshot =
        ExtensionRegistrySnapshot.create(
            listOf(
                ExtensionRegistryEntry(
                    manifest = ExtensionManifest(
                        extensionId = ExtensionId("extension.$name"),
                        version = ExtensionVersion("1.0.0"),
                        kind = ExtensionKind.WORLD_EQUATION_PACK,
                        providerId = "provider.$name",
                        entrypoints = setOf(
                            ExtensionEntrypoint("contract.$name", "implementation.$name")
                        ),
                    ),
                    worldContract = ExtensionWorldContract(
                        worldSignalSchemaVersion = WorldSignalSchemaVersion(1, 0),
                        worldNodeSchemaVersion = WorldNodeSchemaVersion(1, 0),
                        worldEquationVersion = WorldEquationVersion("equation-$name", 1, 0),
                        coefficientSchemaFingerprint = CoefficientSchemaFingerprint("coeff-$name"),
                        projectionContractFingerprint = ProjectionContractFingerprint("projection-$name"),
                    ),
                )
            )
        )

    private data class Fixture(
        val gap: ExtensionGap,
        val graph: DeepSearchClaimGraph,
        val candidate: ExtensionCandidate,
        val workshop: ExtensionWorkshopArtifact,
        val validation: ExtensionValidationBundle,
        val evolution: ExtensionEvolutionAdmission,
        val promotionProof: ExtensionPromotionProof,
        val hotSwapAuthorization: ExtensionHotSwapAuthorization,
        val applied: ExtensionHotSwapResult.Applied,
        val rollbackProof: SelfHealingExtensionRollbackProof,
        val rollbackAuthorization: ExtensionRollbackAuthorization,
        val rolledBack: ExtensionHotSwapResult.RolledBack,
        val targetSnapshot: ExtensionRegistrySnapshot,
    )
}
