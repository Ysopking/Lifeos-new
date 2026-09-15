package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.ToolPermission
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CandidateRuntimeVerificationTest {
    @Test
    fun `valid sealed candidate with exact apk digest becomes verified runtime candidate`() = runBlocking {
        val fixture = fixture()
        val verifier = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { it.signature == "trusted-signature" },
            digestProvider = CandidateArtifactDigestProvider { fixture.apkSha },
        )

        val result = verifier.verify(fixture.artifact, fixture.seal, fixture.policy)

        val verified = assertIs<RuntimeCandidateVerificationResult.Verified>(result).candidate
        assertEquals(fixture.artifact.id, verified.artifact.id)
        assertEquals(fixture.apkSha, verified.debugApkSha256)
    }

    @Test
    fun `untrusted seal is rejected before activation`() = runBlocking {
        val fixture = fixture()
        val verifier = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { false },
            digestProvider = CandidateArtifactDigestProvider { fixture.apkSha },
        )

        val rejected = assertIs<RuntimeCandidateVerificationResult.Rejected>(
            verifier.verify(fixture.artifact, fixture.seal, fixture.policy)
        )
        assertTrue(RuntimeCandidateRejectionReason.INVALID_OR_UNTRUSTED_SEAL in rejected.reasons)
    }

    @Test
    fun `tampered apk digest is rejected even with otherwise trusted seal`() = runBlocking {
        val fixture = fixture()
        val verifier = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { true },
            digestProvider = CandidateArtifactDigestProvider { "b".repeat(64) },
        )

        val rejected = assertIs<RuntimeCandidateVerificationResult.Rejected>(
            verifier.verify(fixture.artifact, fixture.seal, fixture.policy)
        )
        assertTrue(RuntimeCandidateRejectionReason.APK_DIGEST_MISMATCH in rejected.reasons)
    }

    @Test
    fun `candidate seal cannot rebind source or provenance identity`() = runBlocking {
        val fixture = fixture()
        val tampered = fixture.seal.copy(
            sourceCommit = "f".repeat(40),
            provenanceId = "tampered-provenance",
        )
        val verifier = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { true },
            digestProvider = CandidateArtifactDigestProvider { fixture.apkSha },
        )

        val rejected = assertIs<RuntimeCandidateVerificationResult.Rejected>(
            verifier.verify(fixture.artifact, tampered, fixture.policy)
        )
        assertTrue(RuntimeCandidateRejectionReason.SOURCE_COMMIT_MISMATCH in rejected.reasons)
        assertTrue(RuntimeCandidateRejectionReason.PROVENANCE_ID_MISMATCH in rejected.reasons)
    }

    @Test
    fun `capabilities and added permissions are deny by default`() = runBlocking {
        val fixture = fixture()
        val verifier = RuntimeCandidateVerifier(
            sealVerifier = CandidateSealVerifier { true },
            digestProvider = CandidateArtifactDigestProvider { fixture.apkSha },
        )

        val rejected = assertIs<RuntimeCandidateVerificationResult.Rejected>(
            verifier.verify(fixture.artifact, fixture.seal, RuntimeCandidatePolicy())
        )
        assertTrue(RuntimeCandidateRejectionReason.CAPABILITY_NOT_ALLOWED in rejected.reasons)
        assertTrue(RuntimeCandidateRejectionReason.ADDED_PERMISSION_NOT_ALLOWED in rejected.reasons)
    }

    private fun fixture(): Fixture {
        val capabilityId = CapabilityId("module.hotswap.example")
        val requirement = CapabilityRequirement(
            capabilityId = capabilityId,
            requiredInputs = setOf("goal-photon"),
            requiredOutputs = setOf("example-output"),
        )
        val spec = BuildSpec(
            sourceCommit = SOURCE_COMMIT,
            gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING),
            allowedPathPrefixes = setOf(SOURCE_PREFIX, TEST_PREFIX),
            requiredTestPaths = setOf(TEST_PATH),
        )
        val design = BuildDesignSpec(
            buildSpecId = spec.id,
            capability = requirement,
            summary = "hot swap fixture",
            implementationNotes = listOf("bounded"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patch = SourcePatchPlan(
            design.id,
            listOf(
                SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class RuntimeCandidateFixture"),
                SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class RuntimeCandidateFixtureTest"),
            ),
        )
        val apkSha = "a".repeat(64)
        val apk = BuildArtifactEvidence("artifact://runtime-candidate.apk", apkSha)
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = BuildGateCommand.entries.map { command ->
                    BuildCommandResult(command, true, 0, "output:${command.name}")
                },
                artifact = apk,
            )
        )
        val candidate = BuildStudioCandidate(
            buildSpecId = spec.id,
            designSpecId = design.id,
            patchPlanId = patch.id,
            branchName = BRANCH,
            branchHeadCommit = APPLIED_HEAD,
            verificationId = verification.id,
        )
        val provenance = BuildProvenance.fromVerifiedCandidate(
            spec = spec,
            design = design,
            patch = patch,
            candidate = candidate,
            verification = verification,
            capabilityChanges = listOf(
                BuildCapabilityChange(
                    capabilityId = capabilityId,
                    type = BuildCapabilityChangeType.ADDED,
                    requiredInputs = requirement.requiredInputs,
                    outputs = requirement.requiredOutputs,
                )
            ),
            permissionDelta = BuildPermissionDelta(added = setOf(ToolPermission.READ_MEMORY)),
        )
        val artifact = CandidateArtifact(candidate, verification, provenance)
        val seal = CandidateRuntimeSeal(
            candidateArtifactId = artifact.id,
            candidateId = candidate.id,
            sourceCommit = artifact.sourceCommit,
            branchHeadCommit = artifact.branchHeadCommit,
            verificationId = verification.id,
            provenanceId = provenance.id,
            debugApkSha256 = apkSha,
            signerId = "buildstudio-host:test",
            signature = "trusted-signature",
        )
        return Fixture(
            artifact = artifact,
            seal = seal,
            apkSha = apkSha,
            policy = RuntimeCandidatePolicy(
                allowedCapabilities = setOf(capabilityId),
                allowedAddedPermissions = setOf(ToolPermission.READ_MEMORY),
            ),
        )
    }

    private data class Fixture(
        val artifact: CandidateArtifact,
        val seal: CandidateRuntimeSeal,
        val apkSha: String,
        val policy: RuntimeCandidatePolicy,
    )

    companion object {
        private const val SOURCE_COMMIT = "4ee3579226d95db83d3c5c6086e99f746b108980"
        private const val APPLIED_HEAD = "cccccccccccccccccccccccccccccccccccccccc"
        private const val BRANCH = "buildstudio/candidate-runtime-verification"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/RuntimeCandidateFixture.kt"
        private const val TEST_PATH = "$TEST_PREFIX/RuntimeCandidateFixtureTest.kt"
    }
}
