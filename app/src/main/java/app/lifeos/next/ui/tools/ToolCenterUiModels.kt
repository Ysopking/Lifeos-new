package app.lifeos.next.ui.tools

enum class ToolCenterTone {
    POSITIVE,
    NEUTRAL,
    WARNING,
    NEGATIVE,
    MUTED,
}

data class ToolCenterSummaryUiModel(
    val runtimeLabel: String,
    val runtimeTone: ToolCenterTone,
    val capabilityCount: Int,
    val providerCount: Int,
    val generatedToolCount: Int,
    val activeToolCount: Int,
    val trialToolCount: Int,
    val attentionToolCount: Int,
) {
    init {
        require(capabilityCount >= 0)
        require(providerCount >= 0)
        require(generatedToolCount >= 0)
        require(activeToolCount in 0..generatedToolCount)
        require(trialToolCount in 0..generatedToolCount)
        require(attentionToolCount in 0..generatedToolCount)
    }
}

data class ToolCenterProviderUiModel(
    val capabilityId: String,
    val providerId: String,
    val providerTypeLabel: String,
    val stateLabel: String,
    val trustLabel: String,
    val reliabilityPercent: Int,
    val requiredInputs: List<String>,
    val outputs: List<String>,
    val tone: ToolCenterTone,
) {
    init {
        require(capabilityId.isNotBlank())
        require(providerId.isNotBlank())
        require(reliabilityPercent in 0..100)
    }
}

data class ToolCenterGeneratedToolUiModel(
    val toolId: String,
    val capabilityId: String,
    val stateLabel: String,
    val verificationPercent: Int,
    val permissions: List<String>,
    val requiredInputs: List<String>,
    val requiredOutputs: List<String>,
    val promotionEvidenceId: String?,
    val lastMessage: String?,
    val tone: ToolCenterTone,
) {
    init {
        require(toolId.isNotBlank())
        require(capabilityId.isNotBlank())
        require(verificationPercent in 0..100)
    }
}

data class ToolCenterUiModel(
    val summary: ToolCenterSummaryUiModel,
    val providers: List<ToolCenterProviderUiModel>,
    val generatedTools: List<ToolCenterGeneratedToolUiModel>,
)
