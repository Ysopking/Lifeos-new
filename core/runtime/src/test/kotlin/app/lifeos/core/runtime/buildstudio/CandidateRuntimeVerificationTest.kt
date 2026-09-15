package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.ToolPermission
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CandidateRuntimeVerificationTest {
    @Test
    fun `real host signature and exact apk digest produce verified runtime candidate`() = runBlocking {
        val fixture = fixture()
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val sealer = CandidateRuntimeSealer("buildstudio-host:test") { signer ->
            keyPair.private.takeIf { signer == "buildstudio-host:test" }
        }
        val seal = sealer.seal(fixture.artifact)
        val verifier = RuntimeCandidateVerifier(
            sealVerifier = EcdsaCandidateSealVerifier { signer ->
                keyPair.public.takeIf { signer == "buildstudio-host:test" }
            },
            digestProvider = CandidateArtifactDigestProvider { fixture.apkSha },
        )

        val result = verifier.verify(fixture.artifact, seal, fixture.policy)
        val verified = assertIs<RuntimeCandidateVerificationResult.Verified>(result).candidate
        assertEquals(fixture.artifact.id, verified.artifact.id)
        assertEquals(fixture.apkSha, verified.debugApkSha256)
        assertFalse(verified.activationAllowed)
    }

    @Test
    fun `tampering any signed payload field invalidates real signature`() {
        val fixture = fixture()
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val seal = CandidateRuntimeSealer("buildstudio-host:test") { keyPair.private }.seal(fixture.artifact)
        val verifier = EcdsaCandidateSealVerifier { keyPair.public }

        assertTrue(verifier.verify(seal))
        assertFalse(verifier.verify(seal.copy(provenanceId = "tampered-provenance")))
        assertFalse(EcdsaCandidateSealVerifier { null }.verify(seal))
    }

    @Test
    fun `tampered apk is rejected even with valid original host seal`() = runBlocking {
        val fixture = fixture()
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val seal = CandidateRuntimeSealer("buildstudio-host:test") { keyPair.private }.seal(fixture.artifact)
        val result = RuntimeCandidateVerifier(
            sealVerifier = EcdsaCandidateSealVerifier { keyPair.public },
            digestProvider = CandidateArtifactDigestProvider { "b".repeat(64) },
        ).verify(fixture.artifact, seal, fixture.policy)

        val rejected = assertIs<RuntimeCandidateVerificationResult.Rejected>(result)
        assertTrue(RuntimeCandidateRejectionReason.APK_DIGEST_MISMATCH in rejected.reasons)
    }

    @Test
    fun `capabilities and added permissions are denied by default`() = runBlocking {
        val fixture = fixture()
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val seal = CandidateRuntimeSealer("buildstudio-host:test") { keyPair.private }.seal(fixture.artifact)
        val result = RuntimeCandidateVerifier(
            sealVerifier = EcdsaCandidateSealVerifier { keyPair.public },
            digestProvider = CandidateArtifactDigestProvider { fixture.apkSha },
        ).verify(fixture.artifact, seal, RuntimeCandidatePolicy())

        val rejected = assertIs<RuntimeCandidateVerificationResult.Rejected>(result)
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
            summary = "runtime trust fixture",
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
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = BRANCH,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = BuildGateCommand.entries.map { command ->
                    BuildCommandResult(command, true, 0, "output:${command.name}")
                },
                artifact = BuildArtifactEvidence("artifact://runtime-candidate.apk", apkSha),
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
        return Fixture(
            artifact = artifact,
            apkSha = apkSha,
            policy = RuntimeCandidatePolicy(
                allowedCapabilities = setOf(capabilityId),
                allowedAddedPermissions = setOf(ToolPermission.READ_MEMORY),
            ),
        )
    }

    private data class Fixture(
        val artifact: CandidateArtifact,
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
