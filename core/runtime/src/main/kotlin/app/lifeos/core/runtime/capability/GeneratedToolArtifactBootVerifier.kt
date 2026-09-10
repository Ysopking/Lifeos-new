package app.lifeos.core.runtime.capability

/** Fail-closed boot integrity bridge between durable lifecycle records and bounded executable code. */
class GeneratedToolArtifactBootVerifier(
    private val states: GeneratedToolStateRepository,
    private val artifacts: GeneratedToolArtifactRepository,
) {
    suspend fun verify() {
        val durableStates = states.loadAll()
        val durableArtifacts = artifacts.loadAll()
        val statesByTool = durableStates.associateBy { it.record.manifest.toolId }
        val artifactsByTool = durableArtifacts.associateBy { it.toolId }

        require(statesByTool.size == durableStates.size) {
            "Generated-tool lifecycle store contains duplicate tool ids"
        }
        require(artifactsByTool.size == durableArtifacts.size) {
            "Generated-tool artifact store contains duplicate tool ids"
        }

        durableStates.forEach { state ->
            val record = state.record
            if (!GeneratedToolArtifact.isBoundedSourceHash(record.manifest.sourceHash)) return@forEach
            val artifact = artifactsByTool[record.manifest.toolId]
            if (record.state.requiresExecutableArtifact()) {
                requireNotNull(artifact) {
                    "Bounded generated tool ${record.manifest.toolId} is missing its executable artifact"
                }
            }
            if (artifact != null) {
                require(artifact.matches(record)) {
                    "Bounded generated tool ${record.manifest.toolId} artifact differs from lifecycle manifest"
                }
            }
        }

        durableArtifacts.forEach { artifact ->
            val state = requireNotNull(statesByTool[artifact.toolId]) {
                "Generated-tool artifact ${artifact.toolId} has no lifecycle record"
            }
            require(GeneratedToolArtifact.isBoundedSourceHash(state.record.manifest.sourceHash)) {
                "Bounded artifact ${artifact.toolId} is attached to a non-bounded lifecycle record"
            }
            require(artifact.matches(state.record)) {
                "Generated-tool artifact ${artifact.toolId} differs from lifecycle record"
            }
        }
    }

    private fun GeneratedToolState.requiresExecutableArtifact(): Boolean = when (this) {
        GeneratedToolState.BUILT,
        GeneratedToolState.TESTED,
        GeneratedToolState.VERIFIED,
        GeneratedToolState.TRIAL,
        GeneratedToolState.ACTIVE,
        GeneratedToolState.QUARANTINED -> true
        GeneratedToolState.GENERATED,
        GeneratedToolState.REJECTED,
        GeneratedToolState.RETIRED -> false
    }
}
