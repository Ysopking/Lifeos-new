package app.lifeos.next.ui.tools

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRequirement
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeItem
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.capability.GeneratedToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCenterOwnerProjectorTest {
    @Test
    fun durableStatusOwnsActiveTrialAndAttentionClaims() {
        val runtime = runtime(
            tools = listOf(
                baseTool("active", "cap.active", "Aktiv"),
                baseTool("trial", "cap.trial", "Testlauf"),
                baseTool("bad", "cap.bad", "Quarantäne"),
                baseTool("registry-only", "cap.registry", "Aktiv"),
            )
        )
        val status = GeneratedToolRuntimeStatus(
            tools = listOf(
                durable("active", "cap.active", GeneratedToolState.ACTIVE, evidence = "promotion-active"),
                durable("bad", "cap.bad", GeneratedToolState.QUARANTINED),
                durable("trial", "cap.trial", GeneratedToolState.TRIAL),
            ).sortedBy { it.toolId }
        )

        val projected = ToolCenterOwnerProjector.project(runtime, emptyList(), status)

        assertEquals(listOf("active"), projected.activeTools.map { it.toolId })
        assertEquals(listOf("trial"), projected.trialTools.map { it.toolId })
        assertEquals(listOf("bad"), projected.attentionTools.map { it.toolId })
        assertEquals(listOf("registry-only"), projected.pendingTools.map { it.toolId })
        assertTrue(projected.trialTools.single().activationEligible)
        assertFalse(projected.activeTools.single().activationEligible)
        assertFalse(projected.attentionTools.single().activationEligible)
        assertEquals("Status nicht dauerhaft verifiziert", projected.pendingTools.single().stateLabel)
    }

    @Test
    fun blockingGapRemainsExplicitNonActivatingOwnerApproval() {
        val gap = CapabilityGap(
            requirement = CapabilityRequirement(
                capabilityId = CapabilityId("cap.missing"),
                severity = GapSeverity.CRITICAL,
                requiredInputs = setOf("goal-photon"),
                requiredOutputs = setOf("artifact"),
            ),
            type = CapabilityGapType.CAPABILITY_MISSING,
            candidateProviderIds = listOf("candidate-b", "candidate-a", "candidate-a"),
        )

        val projected = ToolCenterOwnerProjector.project(runtime(emptyList()), listOf(gap), GeneratedToolRuntimeStatus(emptyList()))
        val item = projected.gaps.single()

        assertEquals("cap.missing", item.capabilityId)
        assertEquals("Kritisch", item.severityLabel)
        assertTrue(item.approvalEligible)
        assertEquals(listOf("candidate-a", "candidate-b"), item.candidateProviderIds)
        assertTrue(projected.activeTools.isEmpty())
        assertTrue(projected.trialTools.isEmpty())
    }

    @Test
    fun rejectedToolCanNeverAppearAvailable() {
        val runtime = runtime(listOf(baseTool("rejected", "cap.reject", "Aktiv")))
        val status = GeneratedToolRuntimeStatus(
            listOf(durable("rejected", "cap.reject", GeneratedToolState.REJECTED))
        )

        val projected = ToolCenterOwnerProjector.project(runtime, emptyList(), status)

        assertTrue(projected.activeTools.isEmpty())
        assertTrue(projected.trialTools.isEmpty())
        assertEquals(listOf("rejected"), projected.attentionTools.map { it.toolId })
        assertFalse(projected.attentionTools.single().activationEligible)
    }

    private fun runtime(tools: List<ToolCenterGeneratedToolUiModel>): ToolCenterUiModel = ToolCenterUiModel(
        summary = ToolCenterSummaryUiModel(
            runtimeLabel = "Runtime bereit",
            runtimeTone = ToolCenterTone.POSITIVE,
            capabilityCount = 0,
            providerCount = 0,
            generatedToolCount = tools.size,
            activeToolCount = 0,
            trialToolCount = 0,
            attentionToolCount = 0,
        ),
        providers = emptyList(),
        generatedTools = tools,
    )

    private fun baseTool(toolId: String, capabilityId: String, stateLabel: String) = ToolCenterGeneratedToolUiModel(
        toolId = toolId,
        capabilityId = capabilityId,
        stateLabel = stateLabel,
        verificationPercent = 80,
        permissions = emptyList(),
        requiredInputs = emptyList(),
        requiredOutputs = emptyList(),
        promotionEvidenceId = null,
        lastMessage = null,
        tone = ToolCenterTone.NEUTRAL,
    )

    private fun durable(
        toolId: String,
        capabilityId: String,
        state: GeneratedToolState,
        evidence: String? = null,
    ) = GeneratedToolRuntimeItem(
        toolId = toolId,
        capabilityId = capabilityId,
        state = state,
        verificationConfidence = 0.9,
        trials = 5,
        successes = 5,
        expectedOutputs = 5,
        safetyViolations = 0,
        averageLatencyMs = 1.0,
        promotionEvidenceId = evidence,
        lastMessage = null,
    )
}
