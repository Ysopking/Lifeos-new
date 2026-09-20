package app.lifeos.core.runtime.genesis

enum class GenesisHandoffTarget {
    PROCEDURE,
    CAPABILITY_ROUTER,
    CONNECTOR_GATEWAY,
    DEEP_SEARCH,
    TOOL_WORKSHOP,
    BUILD_STUDIO,
}

data class GenesisHandoff(
    val proposalId: String,
    val target: GenesisHandoffTarget,
    val referenceId: String,
    val payloadFingerprint: String,
    val requiresExplicitApproval: Boolean,
) {
    init {
        require(proposalId.isNotBlank())
        require(referenceId.isNotBlank())
        require(payloadFingerprint.isNotBlank())
    }

    /** H02 never grants executable activation authority. */
    val activationAllowed: Boolean = false
}
