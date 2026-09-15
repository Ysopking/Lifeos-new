package app.lifeos.core.runtime.codeaudit

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.buildstudio.BuildDesignSpec
import app.lifeos.core.runtime.buildstudio.BuildSpec
import app.lifeos.core.runtime.buildstudio.SourcePatchOperation
import app.lifeos.core.runtime.buildstudio.SourcePatchOperationType
import app.lifeos.core.runtime.buildstudio.SourcePatchPlan
import app.lifeos.core.runtime.buildstudio.SourcePatchPlanner
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.informationasset.InformationAssetKind
import app.lifeos.core.runtime.informationasset.code.CodeChangeOperation
import app.lifeos.core.runtime.informationasset.code.CodeChangeRisk
import app.lifeos.core.runtime.informationasset.code.CodeChangeRiskLevel
import app.lifeos.core.runtime.informationasset.code.CodeChangeStep
import app.lifeos.core.runtime.informationasset.code.CodeChangeTarget
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodingAssistantCoordinatorTest {
    private val now = Instant.parse("2026-09-15T19:00:00Z")
    private val sourceCommit = "a".repeat(40)
    private val sourcePath = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/example/NewCapability.kt"
    private val testPath = "core/runtime/src/test/kotlin/app/lifeos/core/runtime/example/NewCapabilityTest.kt"

    @Test
    fun `proposal is revision-bound to valid audit and never authorizes activation`() {
        val audit = audit()
        val proposal = CodingAssistantCoordinator().propose(audit, proposalRequest(), now.plusSeconds(1))

        assertEquals(audit.revision.manifest.id, proposal.sourceAuditRevisionId)
        assertEquals(InformationAssetKind.CODE_CHANGE_PROPOSAL, proposal.proposal.request.kind)
        assertTrue(proposal.validation.isValid)
        assertFalse(proposal.activationAllowed)
        assertTrue(proposal.proposal.manifest.sourcePhotons.isNotEmpty())
    }

    @Test
    fun `implementation preparation returns policy checked SourcePatchPlan only`() = runBlocking {
        val audit = audit()
        val coordinator = CodingAssistantCoordinator()
        val proposal = coordinator.propose(audit, proposalRequest(), now.plusSeconds(1))
        val spec = spec()
        val design = design(spec)
        val planner = SourcePatchPlanner { requested ->
            SourcePatchPlan(
                designSpecId = requested.id,
                operations = listOf(
                    SourcePatchOperation(SourcePatchOperationType.CREATE, sourcePath, "class NewCapability"),
                    SourcePatchOperation(SourcePatchOperationType.CREATE, testPath, "class NewCapabilityTest"),
                ),
            )
        }

        val result = coordinator.prepareImplementation(audit, proposal, spec, design, planner)
        val prepared = assertIs<CodingImplementationPreparation.Prepared>(result)
        assertFalse(prepared.activationAllowed)
        assertEquals(setOf(sourcePath, testPath), prepared.patchPlan.operations.map { it.path }.toSet())
    }

    @Test
    fun `implementation preparation rejects paths not approved by semantic proposal`() = runBlocking {
        val audit = audit()
        val coordinator = CodingAssistantCoordinator()
        val proposal = coordinator.propose(
            audit,
            proposalRequest().copy(steps = proposalRequest().steps.filter { it.target.path == sourcePath }),
            now.plusSeconds(1),
        )
        val spec = spec()
        val design = design(spec)
        val result = coordinator.prepareImplementation(
            audit,
            proposal,
            spec,
            design,
            SourcePatchPlanner { error("planner must not run after proposal preflight failure") },
        )

        val rejected = assertIs<CodingImplementationPreparation.Rejected>(result)
        assertTrue(rejected.failures.any { it == "proposal-path-not-approved:$testPath" })
    }

    private fun audit(): CodeAuditRun = CodeAuditCoordinator().audit(
        namespace = "coding-assistant-test",
        stableKey = "audit",
        title = "Audit",
        sourceCommit = sourceCommit,
        sourceFiles = listOf(
            CodeAuditSourceFile(
                path = "core/runtime/src/main/kotlin/app/lifeos/core/runtime/example/Existing.kt",
                photon = Photon(
                    id = PhotonId("coding-assistant-source"),
                    revision = 1,
                    content = "class Existing",
                    mimeType = "text/x-kotlin",
                    provenance = Provenance(source = "test", actor = "test", createdAt = now),
                ),
            )
        ),
        evaluatedAt = now.plusSeconds(1),
    )

    private fun proposalRequest() = CodingProposalRequest(
        namespace = "coding-assistant-test",
        stableKey = "proposal",
        title = "Proposal",
        intent = "Add a bounded example capability",
        steps = listOf(
            CodeChangeStep(CodeChangeOperation.ADD, CodeChangeTarget(sourcePath), "Add implementation"),
            CodeChangeStep(CodeChangeOperation.ADD, CodeChangeTarget(testPath), "Add regression test"),
        ),
        risks = listOf(
            CodeChangeRisk(CodeChangeRiskLevel.LOW, "New code may fail compilation", "Require BuildStudio gates"),
        ),
        validationPlan = "unit test, lint and debug assemble",
        rollbackPlan = "discard isolated candidate branch",
    )

    private fun spec(): BuildSpec {
        val requirement = CapabilityRequirement(
            capabilityId = CapabilityId("example.capability"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("photon"),
            requiredOutputs = setOf("photon"),
        )
        return BuildSpec(
            sourceCommit = sourceCommit,
            gap = CapabilityGap(requirement, CapabilityGapType.CAPABILITY_MISSING),
            allowedPathPrefixes = setOf(
                "core/runtime/src/main/kotlin/app/lifeos/core/runtime/example",
                "core/runtime/src/test/kotlin/app/lifeos/core/runtime/example",
            ),
            requiredTestPaths = setOf(testPath),
        )
    }

    private fun design(spec: BuildSpec) = BuildDesignSpec(
        buildSpecId = spec.id,
        capability = spec.gap.requirement,
        summary = "Implement bounded example capability",
        implementationNotes = listOf("No activation"),
        plannedSourcePaths = setOf(sourcePath),
        plannedTestPaths = setOf(testPath),
    )
}
