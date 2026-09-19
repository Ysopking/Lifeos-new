package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

data class SourceResolutionInput(
    val sourceRef: PhotonRevisionRef,
    val metadata: CanonicalSourceMetadata,
)

data class SourceRelationshipResolution(
    val type: SourceRelationshipType,
    val evidence: List<SourceRelationshipEvidence>,
    val blockers: List<String> = emptyList(),
) {
    init {
        require(evidence.isNotEmpty() || blockers.isNotEmpty()) {
            "Source relationship resolution must carry evidence or blockers"
        }
        require(blockers.none { it.isBlank() })
    }
}

interface SourceRelationshipResolver {
    val resolverId: String
    val resolverVersion: String

    fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<SourceRelationshipResolution>
}
