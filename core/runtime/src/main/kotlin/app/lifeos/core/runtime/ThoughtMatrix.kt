package app.lifeos.core.runtime

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.thought.ThoughtMatrixSnapshot
import app.lifeos.core.runtime.thought.ThoughtMatrixV2
import app.lifeos.core.runtime.thought.ThoughtProjectionInput
import app.lifeos.core.runtime.thought.ThoughtProjectionResult
import app.lifeos.core.runtime.thought.ThoughtVerificationStatus
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ThoughtNode(
    val photonId: PhotonId,
    val summary: String,
    val energy: Double,
    val confidence: Double,
    val tags: Set<String>,
    val revision: Long = 1,
)

data class MatrixState(
    val nodes: Map<PhotonId, ThoughtNode> = emptyMap(),
    val totalEnergy: Double = 0.0,
)

/**
 * Backward-compatible force-field facade over ThoughtMatrixV2.
 *
 * Existing runtime callers keep the original MatrixState semantics. Equal-revision disagreements
 * never overwrite that legacy read model, while v2 records the disagreement as unresolved truth.
 */
class ThoughtMatrix(
    private val v2: ThoughtMatrixV2 = ThoughtMatrixV2(),
) : ForceField {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(MatrixState())
    val state: StateFlow<MatrixState> = mutableState.asStateFlow()

    override suspend fun influence(photon: Photon): FieldInfluence? = mutex.withLock {
        when (
            val result = v2.project(
                ThoughtProjectionInput(
                    photon = photon,
                    fieldDomainId = LEGACY_DOMAIN,
                    semanticKey = semanticKey(photon),
                    verification = ThoughtVerificationStatus.OBSERVED,
                )
            )
        ) {
            is ThoughtProjectionResult.Applied -> {
                val projected = result.snapshot.nodes.single { it.photonId == photon.id }
                val previous = mutableState.value
                val existing = previous.nodes[photon.id]
                val node = ThoughtNode(
                    photonId = projected.photonId,
                    summary = projected.summary.take(120),
                    energy = projected.energy,
                    confidence = projected.confidence,
                    tags = projected.tags,
                    revision = projected.sourceRevision,
                )
                val nodes = previous.nodes + (photon.id to node)
                mutableState.value = MatrixState(
                    nodes = nodes,
                    totalEnergy = previous.totalEnergy - (existing?.energy ?: 0.0) + projected.energy,
                )
                FieldInfluence(
                    module = "Gedankenmatrix",
                    photonId = photon.id,
                    type = "INDEX",
                    deltaEnergy = photon.energy,
                    confidence = photon.confidence,
                    explanation = "Photon indexed in the active thought field",
                )
            }

            is ThoughtProjectionResult.Conflict -> FieldInfluence(
                module = "Gedankenmatrix",
                photonId = photon.id,
                type = "INDEX_CONFLICT",
                deltaEnergy = 0.0,
                confidence = 0.0,
                explanation = "Equal Photon revision produced conflicting v2 thought projections; legacy value retained for compatibility and not treated as v2 authority",
            )

            is ThoughtProjectionResult.Stale,
            is ThoughtProjectionResult.Unchanged -> null
        }
    }

    suspend fun v2Snapshot(capturedAt: Instant = Instant.now()): ThoughtMatrixSnapshot =
        v2.snapshot(capturedAt)

    private fun semanticKey(photon: Photon): String = photon.tags
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .sorted()
        .firstOrNull()
        ?: photon.mimeType.trim().lowercase()

    private companion object {
        val LEGACY_DOMAIN = StableFieldIds.domain("lifeos.runtime.thought-matrix")
    }
}
