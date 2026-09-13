package app.lifeos.next.ui.tools

import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.ToolCenterCapabilityProviderSnapshot
import app.lifeos.core.runtime.capability.ToolCenterGeneratedToolSnapshot
import app.lifeos.core.runtime.capability.ToolCenterRuntimeAvailability
import app.lifeos.core.runtime.capability.ToolCenterRuntimeSnapshot
import app.lifeos.core.runtime.capability.TrustLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolCenterProjectorTest {
    @Test
    fun `ready runtime projects canonical providers tools and attention counts`() {
        val snapshot = ToolCenterRuntimeSnapshot(
            availability = ToolCenterRuntimeAvailability.READY,
            capabilityProviders = listOf(
                provider(
                    capabilityId = "memory.read",
                    providerId = "memory-provider",
                    state = ProviderState.ACTIVE,
                    reliability = 0.997,
                ),
                provider(
                    capabilityId = "search.web",
                    providerId = "generated-search",
                    state = ProviderState.DEGRADED,
                    reliability = 0.824,
                    type = ProviderType.GENERATED_TOOL,
                    trust = TrustLevel.LOW,
                ),
            ),
            generatedTools = listOf(
                tool("tool-active", GeneratedToolState.ACTIVE, 0.976),
                tool("tool-quarantined", GeneratedToolState.QUARANTINED, 0.81),
                tool("tool-trial", GeneratedToolState.TRIAL, 0.905),
            ),
        )

        val ui = ToolCenterProjector.project(snapshot)

        assertEquals("Runtime bereit", ui.summary.runtimeLabel)
        assertEquals(ToolCenterTone.POSITIVE, ui.summary.runtimeTone)
        assertEquals(2, ui.summary.capabilityCount)
        assertEquals(2, ui.summary.providerCount)
        assertEquals(3, ui.summary.generatedToolCount)
        assertEquals(1, ui.summary.activeToolCount)
        assertEquals(1, ui.summary.trialToolCount)
        assertEquals(1, ui.summary.attentionToolCount)
        assertEquals(100, ui.providers.first().reliabilityPercent)
        assertEquals("Eingeschränkt", ui.providers.last().stateLabel)
        assertEquals("Generiertes Tool", ui.providers.last().providerTypeLabel)
        assertEquals("Niedrig", ui.providers.last().trustLabel)
        assertEquals(ToolCenterTone.WARNING, ui.generatedTools.last().tone)
        assertEquals("Quarantäne", ui.generatedTools[1].stateLabel)
        assertEquals(ToolCenterTone.NEGATIVE, ui.generatedTools[1].tone)
    }

    @Test
    fun `partial runtime remains explicitly visible without inventing data`() {
        val ui = ToolCenterProjector.project(
            ToolCenterRuntimeSnapshot(
                availability = ToolCenterRuntimeAvailability.PARTIAL,
                capabilityProviders = emptyList(),
                generatedTools = emptyList(),
            )
        )

        assertEquals("Runtime teilweise bereit", ui.summary.runtimeLabel)
        assertEquals(ToolCenterTone.WARNING, ui.summary.runtimeTone)
        assertEquals(0, ui.summary.providerCount)
        assertEquals(0, ui.summary.generatedToolCount)
    }

    private fun provider(
        capabilityId: String,
        providerId: String,
        state: ProviderState,
        reliability: Double,
        type: ProviderType = ProviderType.MODULE,
        trust: TrustLevel = TrustLevel.SYSTEM,
    ): ToolCenterCapabilityProviderSnapshot = ToolCenterCapabilityProviderSnapshot(
        capabilityId = capabilityId,
        providerId = providerId,
        providerType = type,
        state = state,
        trustLevel = trust,
        reliability = reliability,
        cost = 0.0,
        requiredInputs = listOf("input"),
        outputs = listOf("output"),
    )

    private fun tool(
        toolId: String,
        state: GeneratedToolState,
        confidence: Double,
    ): ToolCenterGeneratedToolSnapshot = ToolCenterGeneratedToolSnapshot(
        toolId = toolId,
        capabilityId = "capability.$toolId",
        state = state,
        verificationConfidence = confidence,
        permissions = emptyList(),
        requiredInputs = emptyList(),
        requiredOutputs = listOf("result"),
        promotionEvidenceId = if (state == GeneratedToolState.ACTIVE) "promotion-$toolId" else null,
        lastMessage = null,
    )
}
