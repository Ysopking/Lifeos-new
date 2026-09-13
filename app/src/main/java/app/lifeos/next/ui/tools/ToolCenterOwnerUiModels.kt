package app.lifeos.next.ui.tools

import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.CapabilityGapType
import app.lifeos.core.runtime.capability.GapSeverity
import app.lifeos.core.runtime.capability.GeneratedToolGenesisResult
import app.lifeos.core.runtime.capability.GeneratedToolRequestExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.capability.GeneratedToolUserActionResult
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationResult
import kotlin.math.roundToInt

enum class ToolCenterOwnerToolGroup {
    PENDING,
    TRIAL,
    ACTIVE,
    ATTENTION,
    RETIRED,
}

data class ToolCenterGapUiModel(
    val capabilityId: String,
    val gapTypeLabel: String,
    val severityLabel: String,
    val requiredInputs: List<String>,
    val requiredOutputs: List<String>,
    val candidateProviderIds: List<String>,
    val approvalEligible: Boolean,
)

data class ToolCenterOwnerToolUiModel(
    val toolId: String,
    val capabilityId: String,
    val stateLabel: String,
    val tone: ToolCenterTone,
    val group: ToolCenterOwnerToolGroup,
    val activationEligible: Boolean,
    val verificationPercent: Int,
    val trials: Int,
    val successes: Int,
    val safetyViolations: Int,
    val permissions: List<String>,
    val requiredInputs: List<String>,
    val requiredOutputs: List<String>,
    val promotionEvidenceId: String?,
    val boundedAdmissionEvidenceId: String?,
    val boundedReadinessEvidenceId: String?,
    val boundedPromotionSealId: String?,
    val lastMessage: String?,
)

data class ToolCenterOwnerWorkspaceUiModel(
    val runtime: ToolCenterUiModel,
    val gaps: List<ToolCenterGapUiModel>,
    val pendingTools: List<ToolCenterOwnerToolUiModel>,
    val trialTools: List<ToolCenterOwnerToolUiModel>,
    val activeTools: List<ToolCenterOwnerToolUiModel>,
    val attentionTools: List<ToolCenterOwnerToolUiModel>,
    val retiredTools: List<ToolCenterOwnerToolUiModel>,
) {
    val durableToolCount: Int
        get() = pendingTools.size + trialTools.size + activeTools.size + attentionTools.size + retiredTools.size
}

/**
 * Pure projection that treats the encrypted generated-tool status as authority for lifecycle claims.
 * A process-registry-only entry is never presented as ACTIVE or activation-eligible.
 */
object ToolCenterOwnerProjector {
    fun project(
        runtime: ToolCenterUiModel,
        gaps: List<CapabilityGap>,
        status: GeneratedToolRuntimeStatus?,
    ): ToolCenterOwnerWorkspaceUiModel {
        val projectedGaps = gaps
            .map(::projectGap)
            .sortedBy { it.capabilityId }

        val baseById = runtime.generatedTools.associateBy { it.toolId }
        val statusById = status?.tools.orEmpty().associateBy { it.toolId }
        val toolIds = (baseById.keys + statusById.keys).toSortedSet()
        val tools = toolIds.map { toolId ->
            val base = baseById[toolId]
            val durable = statusById[toolId]
            if (durable == null) {
                ToolCenterOwnerToolUiModel(
                    toolId = toolId,
                    capabilityId = base?.capabilityId ?: "unknown",
                    stateLabel = "Status nicht dauerhaft verifiziert",
                    tone = ToolCenterTone.MUTED,
                    group = ToolCenterOwnerToolGroup.PENDING,
                    activationEligible = false,
                    verificationPercent = base?.verificationPercent ?: 0,
                    trials = 0,
                    successes = 0,
                    safetyViolations = 0,
                    permissions = base?.permissions.orEmpty(),
                    requiredInputs = base?.requiredInputs.orEmpty(),
                    requiredOutputs = base?.requiredOutputs.orEmpty(),
                    promotionEvidenceId = null,
                    boundedAdmissionEvidenceId = null,
                    boundedReadinessEvidenceId = null,
                    boundedPromotionSealId = null,
                    lastMessage = base?.lastMessage,
                )
            } else {
                val group = group(durable.state)
                ToolCenterOwnerToolUiModel(
                    toolId = durable.toolId,
                    capabilityId = durable.capabilityId,
                    stateLabel = stateLabel(durable.state),
                    tone = tone(durable.state),
                    group = group,
                    activationEligible = durable.state == GeneratedToolState.TRIAL,
                    verificationPercent = percent(durable.verificationConfidence),
                    trials = durable.trials,
                    successes = durable.successes,
                    safetyViolations = durable.safetyViolations,
                    permissions = base?.permissions.orEmpty(),
                    requiredInputs = base?.requiredInputs.orEmpty(),
                    requiredOutputs = base?.requiredOutputs.orEmpty(),
                    promotionEvidenceId = durable.promotionEvidenceId,
                    boundedAdmissionEvidenceId = durable.boundedAdmissionEvidenceId,
                    boundedReadinessEvidenceId = durable.boundedReadinessEvidenceId,
                    boundedPromotionSealId = durable.boundedPromotionSealId,
                    lastMessage = durable.lastMessage ?: base?.lastMessage,
                )
            }
        }

        return ToolCenterOwnerWorkspaceUiModel(
            runtime = runtime,
            gaps = projectedGaps,
            pendingTools = tools.filter { it.group == ToolCenterOwnerToolGroup.PENDING },
            trialTools = tools.filter { it.group == ToolCenterOwnerToolGroup.TRIAL },
            activeTools = tools.filter { it.group == ToolCenterOwnerToolGroup.ACTIVE },
            attentionTools = tools.filter { it.group == ToolCenterOwnerToolGroup.ATTENTION },
            retiredTools = tools.filter { it.group == ToolCenterOwnerToolGroup.RETIRED },
        )
    }

    private fun projectGap(gap: CapabilityGap): ToolCenterGapUiModel = ToolCenterGapUiModel(
        capabilityId = gap.requirement.capabilityId.value,
        gapTypeLabel = when (gap.type) {
            CapabilityGapType.CAPABILITY_MISSING -> "Fähigkeit fehlt"
            CapabilityGapType.PROVIDER_UNHEALTHY -> "Provider nicht gesund"
            CapabilityGapType.CONTRACT_MISMATCH -> "Vertrag passt nicht"
        },
        severityLabel = when (gap.requirement.severity) {
            GapSeverity.NON_BLOCKING -> "Nicht blockierend"
            GapSeverity.DEGRADED -> "Eingeschränkt"
            GapSeverity.BLOCKING -> "Blockierend"
            GapSeverity.CRITICAL -> "Kritisch"
        },
        requiredInputs = gap.requirement.requiredInputs.sorted(),
        requiredOutputs = gap.requirement.requiredOutputs.sorted(),
        candidateProviderIds = gap.candidateProviderIds.distinct().sorted(),
        approvalEligible = gap.requirement.severity == GapSeverity.BLOCKING ||
            gap.requirement.severity == GapSeverity.CRITICAL,
    )

    private fun group(state: GeneratedToolState): ToolCenterOwnerToolGroup = when (state) {
        GeneratedToolState.TRIAL -> ToolCenterOwnerToolGroup.TRIAL
        GeneratedToolState.ACTIVE -> ToolCenterOwnerToolGroup.ACTIVE
        GeneratedToolState.QUARANTINED,
        GeneratedToolState.REJECTED -> ToolCenterOwnerToolGroup.ATTENTION
        GeneratedToolState.RETIRED -> ToolCenterOwnerToolGroup.RETIRED
        GeneratedToolState.GENERATED,
        GeneratedToolState.BUILT,
        GeneratedToolState.TESTED,
        GeneratedToolState.VERIFIED -> ToolCenterOwnerToolGroup.PENDING
    }

    private fun stateLabel(state: GeneratedToolState): String = when (state) {
        GeneratedToolState.GENERATED -> "Generiert"
        GeneratedToolState.BUILT -> "Gebaut"
        GeneratedToolState.TESTED -> "Getestet"
        GeneratedToolState.VERIFIED -> "Verifiziert"
        GeneratedToolState.TRIAL -> "TRIAL"
        GeneratedToolState.ACTIVE -> "ACTIVE"
        GeneratedToolState.QUARANTINED -> "Quarantäne"
        GeneratedToolState.REJECTED -> "Abgelehnt"
        GeneratedToolState.RETIRED -> "Stillgelegt"
    }

    private fun tone(state: GeneratedToolState): ToolCenterTone = when (state) {
        GeneratedToolState.ACTIVE -> ToolCenterTone.POSITIVE
        GeneratedToolState.TRIAL -> ToolCenterTone.WARNING
        GeneratedToolState.QUARANTINED,
        GeneratedToolState.REJECTED -> ToolCenterTone.NEGATIVE
        GeneratedToolState.RETIRED -> ToolCenterTone.MUTED
        else -> ToolCenterTone.NEUTRAL
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt().coerceIn(0, 100)
}

/** Pure owner-action result wording, kept separate from Android/ViewModel state. */
object ToolCenterActionMessages {
    fun generation(result: GeneratedToolUserActionResult): String = when (val execution = result.execution) {
        is GeneratedToolRequestExecutionResult.Blocked ->
            "Tool-Erzeugung wurde vor Genesis blockiert: ${execution.reason}"
        is GeneratedToolRequestExecutionResult.Completed -> when (val genesis = execution.genesis) {
            is GeneratedToolGenesisResult.OwnerReviewRequired ->
                "${genesis.record.manifest.toolId} wurde erzeugt, gebaut, getestet und verifiziert. Die exakte Revision wartet in Assets auf Owner-Freigabe; Aktivierung ist damit nicht erlaubt."
            is GeneratedToolGenesisResult.TrialReady ->
                "${genesis.record.manifest.toolId} ist nach der getrennten Freigabe isoliert in TRIAL. Das Tool ist noch nicht ACTIVE."
            is GeneratedToolGenesisResult.Rejected ->
                "${genesis.record.manifest.toolId} wurde sicher abgelehnt: ${genesis.reasons.joinToString("; ")}"
        }
    }

    fun activation(result: PrivateNovelCapabilityActivationResult): String = when (result) {
        is PrivateNovelCapabilityActivationResult.Activated ->
            "${result.promotion.activeRecord.manifest.toolId} hat Canary-, Readiness-, Promotion-Seal- und Owner-Gates bestanden und ist jetzt ACTIVE mit LOW Trust."
        is PrivateNovelCapabilityActivationResult.AlreadyActive ->
            "${result.record.manifest.toolId} ist bereits ACTIVE; es wurden keine weiteren Canary-Trials ausgeführt."
        is PrivateNovelCapabilityActivationResult.Blocked ->
            "Aktivierung von ${result.toolId} wurde sicher blockiert: ${result.reasons.joinToString("; ")}"
    }
}
