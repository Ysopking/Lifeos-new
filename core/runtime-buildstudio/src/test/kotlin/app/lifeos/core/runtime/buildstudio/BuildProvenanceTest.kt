package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.ToolPermission
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BuildProvenanceTest {
    private val reviewedAt = Instant.parse("2026-09-10T12:30:00Z")

    @Test
    fun `verified candidate produces exact non activating provenance artifact`() {
        val fixture = fixture()
        val actor = BuildActorEvidence(
            actorId = "reviewer:local-user",
            role = BuildActorRole.REVIEWER,
            action = BuildActorAction.APPROVED,
            occurredAt = reviewedAt,
            evidenceRef = "review:101",
        )
        val permissions = BuildPermissionDelta(
            added = setOf(ToolPermission.READ_MEMORY),
        )

        val provenance = BuildProvenance.fromVerifiedCandidate(
            spec = fixture.spec,
            design = fixture.design,
            patch = fixture.patch,
            candidate = fixture.candidate,
            verification = fixture.verification,
            capabilityChanges = fixture.capabilityChanges,
            permissionDelta = permissions,
            actors = listOf(actor),
        )
        val artifact = CandidateArtifact(fixture.candidate, fixture.verification, provenance)

        assertFalse(provenance.activationAllowed)
        assertFalse(artifact.activationAllowed)
        assertEquals(fixture.spec.sourceCommit, artifact.sourceCommit)
        assertEquals(fixture.branchName, artifact.branchName)
        assertEquals(fixture.apk.debugApkSha256, artifact.debugApkSha256)
        assertEquals(fixture.patch.operations.map { it.path }.sorted(), provenance.files.map { it.path }.sorted())
        assertEquals(BuildGateCommand.entries.toSet(), provenance.commandResults.map { it.command }.toSet())
        assertEquals(permissions, provenance.permissionDelta)
        assertEquals(listOf(actor), provenance.actors)
        assertEquals(fixture.requirement, provenance.sourceRequirement)
        assertTrue(BuildPathPolicy().isProtected(J02_PROVENANCE_PATH))
        assertTrue(BuildPathPolicy().isProtected(J02_ARTIFACT_PATH))
    }

    @Test
    fun `provenance and candidate artifact ids are order stable`() {
        val first = fixture(reversePatch = false, reverseCommands = false)
        val second = fixture(reversePatch = true, reverseCommands = true)
        val actors = listOf(
            BuildActorEvidence(
                actorId = "reviewer:b",
                role = BuildActorRole.REVIEWER,
                action = BuildActorAction.REVIEWED,
                occurredAt = reviewedAt.plusSeconds(1),
                evidenceRef = "review:b",
            ),
            BuildActorEvidence(
                actorId = "reviewer:a",
                role = BuildActorRole.REVIEWER,
                action = BuildActorAction.APPROVED,
                occurredAt = reviewedAt,
                evidenceRef = "review:a",
            ),
        )
        val firstProvenance = BuildProvenance.fromVerifiedCandidate(
            first.spec,
            first.design,
            first.patch,
            first.candidate,
            first.verification,
            first.capabilityChanges,
            actors = actors,
        )
        val secondProvenance = BuildProvenance.fromVerifiedCandidate(
            second.spec,
            second.design,
            second.patch,
            second.candidate,
            second.verification,
            second.capabilityChanges.reversed(),
            actors = actors.reversed(),
        )

        assertEquals(first.patch.id, second.patch.id)
        assertEquals(first.verification.id, second.verification.id)
        assertEquals(first.candidate.id, second.candidate.id)
        assertEquals(firstProvenance.id, secondProvenance.id)
        assertEquals(
            CandidateArtifact(first.candidate, first.verification, firstProvenance).id,
            CandidateArtifact(second.candidate, second.verification, secondProvenance).id,
        )
    }

    @Test
    fun `apk permission capability and actor evidence change provenance identity`() {
        val fixture = fixture()
        val baseline = provenance(fixture)
        val changedApk = baseline.copy(
            artifact = BuildArtifactEvidence("artifact://debug.apk", "c".repeat(64)),
        )
        val changedPermissions = baseline.copy(
            permissionDelta = BuildPermissionDelta(added = setOf(ToolPermission.NETWORK_ACCESS)),
        )
        val changedCapabilities = baseline.copy(
            capabilityChanges = baseline.capabilityChanges + BuildCapabilityChange(
                CapabilityId("module.secondary"),
                BuildCapabilityChangeType.ADDED,
                outputs = setOf("secondary-output"),
            ),
        )
        val changedActor = baseline.copy(
            actors = listOf(
                BuildActorEvidence(
                    actorId = "reviewer:local-user",
                    role = BuildActorRole.REVIEWER,
                    action = BuildActorAction.REVIEWED,
                    occurredAt = reviewedAt,
                    evidenceRef = "review:changed",
                )
            ),
        )

        assertNotEquals(baseline.id, changedApk.id)
        assertNotEquals(baseline.id, changedPermissions.id)
        assertNotEquals(baseline.id, changedCapabilities.id)
        assertNotEquals(baseline.id, changedActor.id)
    }

    @Test
    fun `capability delta must address exact source requirement contract`() {
        val fixture = fixture()

        assertFailsWith<IllegalArgumentException> {
            BuildProvenance.fromVerifiedCandidate(
                fixture.spec,
                fixture.design,
                fixture.patch,
                fixture.candidate,
                fixture.verification,
                capabilityChanges = listOf(
                    BuildCapabilityChange(
                        capabilityId = fixture.requirement.capabilityId,
                        type = BuildCapabilityChangeType.ADDED,
                        requiredInputs = emptySet(),
                        outputs = emptySet(),
                    )
                ),
            )
        }
    }

    @Test
    fun `candidate artifact rejects provenance with different apk evidence`() {
        val fixture = fixture()
        val provenance = provenance(fixture).copy(
            artifact = BuildArtifactEvidence("artifact://other.apk", "d".repeat(64)),
        )

        assertFailsWith<IllegalArgumentException> {
            CandidateArtifact(fixture.candidate, fixture.verification, provenance)
        }
    }

    @Test
    fun `reviewer and promotion actor roles cannot impersonate each other`() {
        assertFailsWith<IllegalArgumentException> {
            BuildActorEvidence(
                actorId = "reviewer:a",
                role = BuildActorRole.REVIEWER,
                action = BuildActorAction.PROMOTED,
                occurredAt = reviewedAt,
                evidenceRef = "invalid",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BuildActorEvidence(
                actorId = "promotion:a",
                role = BuildActorRole.PROMOTION_ACTOR,
                action = BuildActorAction.APPROVED,
                occurredAt = reviewedAt,
                evidenceRef = "invalid",
            )
        }
    }

    private fun provenance(fixture: Fixture): BuildProvenance = BuildProvenance.fromVerifiedCandidate(
        fixture.spec,
        fixture.design,
        fixture.patch,
        fixture.candidate,
        fixture.verification,
        fixture.capabilityChanges,
    )

    private fun fixture(
        reversePatch: Boolean = false,
        reverseCommands: Boolean = false,
    ): Fixture {
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("module.example.generate"),
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
            summary = "example build",
            implementationNotes = listOf("bounded"),
            plannedSourcePaths = setOf(SOURCE_PATH),
            plannedTestPaths = setOf(TEST_PATH),
        )
        val patchOperations = listOf(
            SourcePatchOperation(SourcePatchOperationType.CREATE, SOURCE_PATH, "class Example"),
            SourcePatchOperation(SourcePatchOperationType.CREATE, TEST_PATH, "class ExampleTest"),
        ).let { if (reversePatch) it.reversed() else it }
        val patch = SourcePatchPlan(design.id, patchOperations)
        val commands = BuildGateCommand.entries.map { command ->
            BuildCommandResult(
                command = command,
                success = true,
                exitCode = 0,
                outputFingerprint = "output:${command.name}",
            )
        }.let { if (reverseCommands) it.reversed() else it }
        val apk = BuildArtifactEvidence(
            debugApkRef = "artifact://debug.apk",
            debugApkSha256 = "a".repeat(64),
        )
        val branchName = "buildstudio/candidate-provenance"
        val verification = BuildVerificationPolicy().verify(
            BuildVerificationEvidence(
                branchName = branchName,
                branchHeadCommit = APPLIED_HEAD,
                patchPlanId = patch.id,
                commandResults = commands,
                artifact = apk,
            )
        )
        val candidate = BuildStudioCandidate(
            buildSpecId = spec.id,
            designSpecId = design.id,
            patchPlanId = patch.id,
            branchName = branchName,
            branchHeadCommit = APPLIED_HEAD,
            verificationId = verification.id,
        )
        val capabilityChanges = listOf(
            BuildCapabilityChange(
                capabilityId = requirement.capabilityId,
                type = BuildCapabilityChangeType.ADDED,
                requiredInputs = requirement.requiredInputs,
                outputs = requirement.requiredOutputs,
            )
        )
        return Fixture(
            requirement,
            spec,
            design,
            patch,
            verification,
            candidate,
            capabilityChanges,
            apk,
            branchName,
        )
    }

    private data class Fixture(
        val requirement: CapabilityRequirement,
        val spec: BuildSpec,
        val design: BuildDesignSpec,
        val patch: SourcePatchPlan,
        val verification: BuildVerification,
        val candidate: BuildStudioCandidate,
        val capabilityChanges: List<BuildCapabilityChange>,
        val apk: BuildArtifactEvidence,
        val branchName: String,
    )

    companion object {
        private const val SOURCE_COMMIT = "4ee3579226d95db83d3c5c6086e99f746b108980"
        private const val APPLIED_HEAD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val SOURCE_PREFIX = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val TEST_PREFIX = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/buildstudio/generated"
        private const val SOURCE_PATH = "$SOURCE_PREFIX/Example.kt"
        private const val TEST_PATH = "$TEST_PREFIX/ExampleTest.kt"
        private const val J02_PROVENANCE_PATH = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/BuildProvenance.kt"
        private const val J02_ARTIFACT_PATH = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/buildstudio/CandidateArtifact.kt"
    }
}
