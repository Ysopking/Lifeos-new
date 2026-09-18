package app.lifeos.core.runtime.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionIndependentValidatorTest {
    @Test
    fun requiresPassingIndependentSandboxAndGoldEvidence() {
        val artifact = workshopArtifact()
        val bundle = ExtensionIndependentValidator().validate(
            workshopArtifact = artifact,
            sandbox = evidence(
                artifact = artifact,
                suite = ExtensionValidationSuite.SANDBOX,
                validatorId = "sandbox-validator",
                producerId = "buildstudio-host",
                passed = true,
                fingerprint = "sandbox-pass",
            ),
            gold = evidence(
                artifact = artifact,
                suite = ExtensionValidationSuite.GOLD,
                validatorId = "gold-validator",
                producerId = "buildstudio-host",
                passed = true,
                fingerprint = "gold-pass",
            ),
        )

        assertEquals(artifact.id, bundle.workshopArtifactId)
        assertFalse(bundle.activationAllowed)
        assertFalse(bundle.promotionAllowed)
    }

    @Test
    fun rejectsProducerSelfValidation() {
        val artifact = workshopArtifact()

        assertFailsWith<IllegalArgumentException> {
            evidence(
                artifact = artifact,
                suite = ExtensionValidationSuite.SANDBOX,
                validatorId = "buildstudio-host",
                producerId = "buildstudio-host",
                passed = true,
                fingerprint = "self-validation",
            )
        }
    }

    @Test
    fun rejectsSharedValidatorIdentityAcrossSandboxAndGold() {
        val artifact = workshopArtifact()
        val sandbox = evidence(
            artifact = artifact,
            suite = ExtensionValidationSuite.SANDBOX,
            validatorId = "validator-a",
            producerId = "buildstudio-host",
            passed = true,
            fingerprint = "sandbox-pass",
        )
        val gold = evidence(
            artifact = artifact,
            suite = ExtensionValidationSuite.GOLD,
            validatorId = "validator-a",
            producerId = "buildstudio-host",
            passed = true,
            fingerprint = "gold-pass",
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionIndependentValidator().validate(
                workshopArtifact = artifact,
                sandbox = sandbox,
                gold = gold,
            )
        }
    }

    @Test
    fun rejectsFailedGoldValidation() {
        val artifact = workshopArtifact()
        val sandbox = evidence(
            artifact = artifact,
            suite = ExtensionValidationSuite.SANDBOX,
            validatorId = "validator-a",
            producerId = "buildstudio-host",
            passed = true,
            fingerprint = "sandbox-pass",
        )
        val gold = evidence(
            artifact = artifact,
            suite = ExtensionValidationSuite.GOLD,
            validatorId = "validator-b",
            producerId = "buildstudio-host",
            passed = false,
            fingerprint = "gold-fail",
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionIndependentValidator().validate(
                workshopArtifact = artifact,
                sandbox = sandbox,
                gold = gold,
            )
        }
    }

    private fun evidence(
        artifact: ExtensionWorkshopArtifact,
        suite: ExtensionValidationSuite,
        validatorId: String,
        producerId: String,
        passed: Boolean,
        fingerprint: String,
    ) = ExtensionValidationEvidence(
        workshopArtifactId = artifact.id,
        suite = suite,
        validatorId = validatorId,
        producerId = producerId,
        passed = passed,
        evidenceFingerprint = fingerprint,
    )

    private fun workshopArtifact(): ExtensionWorkshopArtifact {
        val candidate = minimalCandidate()
        val spec = ExtensionWorkshopPlanner().plan(
            candidate = candidate,
            sourceCommit = "0123456789abcdef0123456789abcdef01234567",
            allowedPathPrefixes = setOf("core/runtime"),
            requiredTestPaths = setOf("core/runtime/src/test"),
        )
        val evidence = ExtensionWorkshopBuildEvidence(
            id = "build-evidence-1",
            sourceCommit = spec.sourceCommit,
            branchName = "extension-candidate/test",
            branchHeadCommit = "89abcdef0123456789abcdef0123456789abcdef",
            debugArtifactRef = "app/build/outputs/apk/debug/app-debug.apk",
            debugArtifactSha256 = "a".repeat(64),
            activationAllowed = false,
        )
        return ExtensionWorkshopArtifact.create(spec, candidate, evidence)
    }

    private fun minimalCandidate(): ExtensionCandidate {
        val gap = ExtensionGap.create(
            kind = ExtensionGapKind.WORLD_SIGNAL_GAP,
            semanticKey = "world-signal-dimension:GOAL_RELEVANCE",
            reason = "required-world-signal-dimension-not-represented",
            sourceFingerprint = "source-v1",
            requiredExtensionKinds = setOf(ExtensionKind.WORLD_SIGNAL_PACK),
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
                        statement = "coverage evidence",
                        confidence = 0.8,
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
                        statement = "coverage missing",
                        semanticTerms = setOf("coverage"),
                        confidence = 0.75,
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

        return ExtensionCandidateFactory().propose(
            ExtensionCandidateRequest(
                gap = gap,
                claimGraph = graph,
                selectedClaimIds = setOf(claimId),
                selectedEvidenceIds = setOf(evidenceId),
                rationale = "missing signal coverage",
            )
        )
    }
}
