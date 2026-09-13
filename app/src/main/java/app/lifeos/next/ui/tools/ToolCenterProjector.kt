package app.lifeos.next.ui.tools

import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.ToolCenterRuntimeAvailability
import app.lifeos.core.runtime.capability.ToolCenterRuntimeSnapshot
import app.lifeos.core.runtime.capability.TrustLevel
import kotlin.math.roundToInt

/** Pure Android-free projection from the productive runtime snapshot into Tool Center UI state. */
object ToolCenterProjector {
    fun project(snapshot: ToolCenterRuntimeSnapshot): ToolCenterUiModel {
        val generatedTools = snapshot.generatedTools.map { tool ->
            ToolCenterGeneratedToolUiModel(
                toolId = tool.toolId,
                capabilityId = tool.capabilityId,
                stateLabel = toolStateLabel(tool.state),
                verificationPercent = percent(tool.verificationConfidence),
                permissions = tool.permissions,
                requiredInputs = tool.requiredInputs,
                requiredOutputs = tool.requiredOutputs,
                promotionEvidenceId = tool.promotionEvidenceId,
                lastMessage = tool.lastMessage,
                tone = toolTone(tool.state),
            )
        }
        val providers = snapshot.capabilityProviders.map { provider ->
            ToolCenterProviderUiModel(
                capabilityId = provider.capabilityId,
                providerId = provider.providerId,
                providerTypeLabel = providerTypeLabel(provider.providerType),
                stateLabel = providerStateLabel(provider.state),
                trustLabel = trustLabel(provider.trustLevel),
                reliabilityPercent = percent(provider.reliability),
                requiredInputs = provider.requiredInputs,
                outputs = provider.outputs,
                tone = providerTone(provider.state),
            )
        }

        return ToolCenterUiModel(
            summary = ToolCenterSummaryUiModel(
                runtimeLabel = runtimeLabel(snapshot.availability),
                runtimeTone = runtimeTone(snapshot.availability),
                capabilityCount = snapshot.capabilityCount,
                providerCount = snapshot.providerCount,
                generatedToolCount = snapshot.generatedToolCount,
                activeToolCount = snapshot.generatedTools.count { it.state == GeneratedToolState.ACTIVE },
                trialToolCount = snapshot.generatedTools.count { it.state == GeneratedToolState.TRIAL },
                attentionToolCount = snapshot.generatedTools.count {
                    it.state == GeneratedToolState.QUARANTINED || it.state == GeneratedToolState.REJECTED
                },
            ),
            providers = providers,
            generatedTools = generatedTools,
        )
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt().coerceIn(0, 100)

    private fun runtimeLabel(availability: ToolCenterRuntimeAvailability): String = when (availability) {
        ToolCenterRuntimeAvailability.READY -> "Runtime bereit"
        ToolCenterRuntimeAvailability.PARTIAL -> "Runtime teilweise bereit"
        ToolCenterRuntimeAvailability.UNAVAILABLE -> "Runtime nicht verfügbar"
    }

    private fun runtimeTone(availability: ToolCenterRuntimeAvailability): ToolCenterTone = when (availability) {
        ToolCenterRuntimeAvailability.READY -> ToolCenterTone.POSITIVE
        ToolCenterRuntimeAvailability.PARTIAL -> ToolCenterTone.WARNING
        ToolCenterRuntimeAvailability.UNAVAILABLE -> ToolCenterTone.NEGATIVE
    }

    private fun providerStateLabel(state: ProviderState): String = when (state) {
        ProviderState.ACTIVE -> "Aktiv"
        ProviderState.DEGRADED -> "Eingeschränkt"
        ProviderState.QUARANTINED -> "Quarantäne"
        ProviderState.DISABLED -> "Deaktiviert"
    }

    private fun providerTone(state: ProviderState): ToolCenterTone = when (state) {
        ProviderState.ACTIVE -> ToolCenterTone.POSITIVE
        ProviderState.DEGRADED -> ToolCenterTone.WARNING
        ProviderState.QUARANTINED -> ToolCenterTone.NEGATIVE
        ProviderState.DISABLED -> ToolCenterTone.MUTED
    }

    private fun providerTypeLabel(type: ProviderType): String = when (type) {
        ProviderType.MODULE -> "Modul"
        ProviderType.WORKER -> "Worker"
        ProviderType.INTERNAL_TOOL -> "Internes Tool"
        ProviderType.EXTERNAL_TOOL -> "Externes Tool"
        ProviderType.CONNECTOR -> "Connector"
        ProviderType.COMPOSITE_CAPABILITY -> "Kombinierte Fähigkeit"
        ProviderType.GENERATED_TOOL -> "Generiertes Tool"
    }

    private fun trustLabel(level: TrustLevel): String = when (level) {
        TrustLevel.LOW -> "Niedrig"
        TrustLevel.MEDIUM -> "Mittel"
        TrustLevel.HIGH -> "Hoch"
        TrustLevel.SYSTEM -> "System"
    }

    private fun toolStateLabel(state: GeneratedToolState): String = when (state) {
        GeneratedToolState.GENERATED -> "Generiert"
        GeneratedToolState.BUILT -> "Gebaut"
        GeneratedToolState.TESTED -> "Getestet"
        GeneratedToolState.VERIFIED -> "Verifiziert"
        GeneratedToolState.TRIAL -> "Testlauf"
        GeneratedToolState.ACTIVE -> "Aktiv"
        GeneratedToolState.QUARANTINED -> "Quarantäne"
        GeneratedToolState.REJECTED -> "Abgelehnt"
        GeneratedToolState.RETIRED -> "Stillgelegt"
    }

    private fun toolTone(state: GeneratedToolState): ToolCenterTone = when (state) {
        GeneratedToolState.ACTIVE -> ToolCenterTone.POSITIVE
        GeneratedToolState.TRIAL -> ToolCenterTone.WARNING
        GeneratedToolState.QUARANTINED,
        GeneratedToolState.REJECTED -> ToolCenterTone.NEGATIVE
        GeneratedToolState.RETIRED -> ToolCenterTone.MUTED
        GeneratedToolState.GENERATED,
        GeneratedToolState.BUILT,
        GeneratedToolState.TESTED,
        GeneratedToolState.VERIFIED -> ToolCenterTone.NEUTRAL
    }
}
