package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThoughtNode(val photonId: PhotonId, val summary: String, val energy: Double, val confidence: Double, val tags: Set<String>)
data class MatrixState(val nodes: Map<PhotonId, ThoughtNode> = emptyMap(), val totalEnergy: Double = 0.0)

class ThoughtMatrix : ForceField {
    private val mutableState = MutableStateFlow(MatrixState())
    val state: StateFlow<MatrixState> = mutableState.asStateFlow()

    override suspend fun influence(photon: Photon): FieldInfluence {
        val node = ThoughtNode(photon.id, photon.content.take(120), photon.energy, photon.confidence, photon.tags)
        val nodes = mutableState.value.nodes + (photon.id to node)
        mutableState.value = MatrixState(nodes, nodes.values.sumOf { it.energy })
        return FieldInfluence("Gedankenmatrix", photon.id, "INDEX", photon.energy, photon.confidence, "Photon indexed in the active thought field")
    }
}
