package app.lifeos.core.runtime.capability

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.buildstudio.BuildStudioExpansionRequest
import app.lifeos.core.runtime.buildstudio.BuildStudioHostAdapter
import app.lifeos.core.runtime.buildstudio.BuildStudioHostProcessRegistry
import app.lifeos.core.runtime.buildstudio.BuildStudioHostState
import app.lifeos.core.runtime.buildstudio.BuildStudioHostStatus
import app.lifeos.core.runtime.buildstudio.BuildStudioResult
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuardedCapabilityExpansionEvidenceTest {
    private val instant = Instant.parse("2026-09-12T15:00:00Z")

    @Test
    fun `capability gap photon is deterministic non activating and keeps source goal lineage`() {
        val source = sourcePhoton()
        val gap = gap()
        val goalId = PhotonId("goal-n-1")

        val first = CapabilityGapPhoton.create(gap, source, goalId, 3)
        val replay = CapabilityGapPhoton.create(gap.copy(candidateProviderIds = gap.candidateProviderIds.reversed()), source, goalId, 3)

        assertEquals(first, replay)
        assertTrue(source.id in first.provenance.parentIds)
        assertTrue(goalId in first.provenance.parentIds)
        assertTrue("activation-allowed:false" in first.tags)
        assertTrue(first.content.contains("source_state_hash="))
        assertTrue(first.content.contains("goal_revision=3"))
    }

    @Test
    fun `tool workshop candidate projection binds version implementation contract and remains non activating`() {
        val gap = gap()
        val source = sourcePhoton()
        val jobId = ToolWorkshopJobId.create(
            sourcePhotonId = source.id.value,
            sourceRevision = source.revision,
            capabilityId = gap.requirement.capabilityId,
            severity = gap.requirement.severity,
            gapType = gap.type,
            requiredInputs = gap.requirement.requiredInputs,
            requiredOutputs = gap.requirement.requiredOutputs,
            candidateProviderIds = gap.candidateProviderIds,
            policyVersion = "policy-v1",
            workshopVersion = "workshop-v9",
        )
        val definition = ToolWorkshopJobDefinition(
            id = jobId,
            sourceRequestId = "request-n-1",
            sourcePhotonId = source.id.value,
            sourceRevision = source.revision,
            capabilityId = gap.requirement.capabilityId,
            severity = gap.requirement.severity,
            gapType = gap.type,
            requiredInputs = gap.requirement.requiredInputs,
            requiredOutputs = gap.requirement.requiredOutputs,
            candidateProviderIds = gap.candidateProviderIds,
            policyVersion = "policy-v1",
            workshopVersion = "workshop-v9",
            createdAt = instant,
        )
        val snapshot = ToolWorkshopJobSnapshot(
            definition = definition,
            state = ToolWorkshopJobState.TRIAL_READY,
            stageFingerprint = "verified-stage-fingerprint",
            ledgerRevision = 8,
        )
        val gapPhotonId = PhotonId("gap-photon-n-1")

        val first = GeneratedCapabilityCandidatePhoton.fromToolWorkshop(snapshot, source, gapPhotonId)
        val replay = GeneratedCapabilityCandidatePhoton.fromToolWorkshop(snapshot, source, gapPhotonId)

        assertEquals(first, replay)
        assertTrue(first.content.contains("module_version=workshop-v9"))
        assertTrue(first.content.contains("implementation_hash=verified-stage-fingerprint"))
        assertTrue(first.content.contains("capability_id=${gap.requirement.capabilityId.value}"))
        assertTrue("activation-allowed:false" in first.tags)
        assertTrue(gapPhotonId in first.provenance.parentIds)
    }

    @Test
    fun `BuildStudio expansion request carries no repository credential or source commit authority`() {
        val request = expansionRequest()
        val photon = request.toPhoton(sourcePhoton())

        assertFalse(request.activationAllowed)
        assertTrue("authorized-host-required" in photon.tags)
        assertTrue("activation-allowed:false" in photon.tags)
        assertFalse(photon.content.contains("token", ignoreCase = true))
        assertFalse(photon.content.contains("credential", ignoreCase = true))
        assertFalse(photon.content.contains("source_commit", ignoreCase = true))
    }

    @Test
    fun `missing BuildStudio host fails closed without candidate`() = runTest {
        BuildStudioHostProcessRegistry.uninstall()
        val result = BuildStudioHostProcessRegistry.expand(expansionRequest())

        val failed = assertIs<BuildStudioResult.Failed>(result)
        assertEquals("host", failed.stage)
        assertEquals("buildstudio-host-not-installed", failed.reason)
    }

    @Test
    fun `quarantined BuildStudio host cannot execute expansion`() = runTest {
        BuildStudioHostProcessRegistry.uninstall()
        var expanded = false
        val capabilities = CapabilityRegistry()
        val host = object : BuildStudioHostAdapter {
            override val id: String = "quarantined-host"
            override suspend fun status() = BuildStudioHostStatus(BuildStudioHostState.QUARANTINED, "test")
            override suspend fun run(spec: app.lifeos.core.runtime.buildstudio.BuildSpec): BuildStudioResult =
                error("run must not be called")
            override suspend fun expand(request: BuildStudioExpansionRequest): BuildStudioResult {
                expanded = true
                return BuildStudioResult.Failed("unexpected", "executed")
            }
        }
        try {
            BuildStudioHostProcessRegistry.install(host, capabilities)
            val result = BuildStudioHostProcessRegistry.expand(expansionRequest())
            val failed = assertIs<BuildStudioResult.Failed>(result)
            assertTrue(failed.reason.startsWith("buildstudio-host-quarantined:"))
            assertFalse(expanded)
        } finally {
            BuildStudioHostProcessRegistry.uninstall()
        }
    }

    private fun sourcePhoton() = Photon(
        id = PhotonId("source-n-1"),
        revision = 2,
        content = "need a missing capability",
        provenance = Provenance("test", "owner", instant),
        tags = setOf("chat"),
    )

    private fun gap() = CapabilityGap(
        requirement = CapabilityRequirement(
            capabilityId = CapabilityId("novel.report.render"),
            severity = GapSeverity.BLOCKING,
            requiredInputs = setOf("report-model"),
            requiredOutputs = setOf("rendered-report"),
        ),
        type = CapabilityGapType.CAPABILITY_MISSING,
        candidateProviderIds = listOf("candidate-b", "candidate-a"),
    )

    private fun expansionRequest(): BuildStudioExpansionRequest {
        val gap = gap()
        val handoff = GenesisHandoff(
            proposalId = "proposal-n-1",
            target = GenesisHandoffTarget.BUILD_STUDIO,
            referenceId = "module-proposal-n-1",
            payloadFingerprint = "handoff-fingerprint-n-1",
            requiresExplicitApproval = true,
        )
        return BuildStudioExpansionRequest(
            gap = gap,
            genesisHandoff = handoff,
            sourcePhotonId = PhotonId("source-n-1"),
            sourcePhotonRevision = 2,
            goalPhotonId = PhotonId("goal-n-1"),
            goalPhotonRevision = 3,
            gapPhotonId = PhotonId("gap-photon-n-1"),
        )
    }
}
