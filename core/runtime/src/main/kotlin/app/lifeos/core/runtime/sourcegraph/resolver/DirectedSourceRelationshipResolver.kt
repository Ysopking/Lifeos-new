package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

data class DirectedSourceRelationshipResolution(
    val sourceRef: PhotonRevisionRef,
    val targetRef: PhotonRevisionRef,
    val type: SourceRelationshipType,
    val evidence: List<SourceRelationshipEvidence>,
    val blockers: List<String> = emptyList(),
) {
    init {
        require(sourceRef != targetRef)
        require(evidence.isNotEmpty() || blockers.isNotEmpty())
        require(blockers.none { it.isBlank() })
    }
}

interface DirectedSourceRelationshipResolver {
    val resolverId: String
    val resolverVersion: String

    fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution>
}
