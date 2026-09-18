package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTargetRef

enum class CognitiveMemoryKind {
    EPISODIC,
    SEMANTIC,
    PROCEDURAL,
    OUTCOME,
}

data class CognitiveMemoryObservation(
    val memoryId: String,
    val revision: Long,
    val kind: CognitiveMemoryKind,
    val target: WorldTargetRef,
    val dimensions: Map<WorldSignalDimension, Double>,
    val confidence: Double,
    val provenanceFingerprint: String,
) {
    init {
        require(memoryId.isNotBlank())
        require(revision > 0L)
        require(dimensions.isNotEmpty())
        require(dimensions.values.all { it.isFinite() && it in 0.0..1.0 })
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(provenanceFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-memory-observation/v1",
        memoryId,
        revision.toString(),
        kind.name,
        target.fingerprint(),
        java.lang.Double.toHexString(confidence),
        provenanceFingerprint,
        *dimensions.toSortedMap(compareBy { it.name }).flatMap { (dimension, value) ->
            listOf(dimension.name, java.lang.Double.toHexString(value))
        }.toTypedArray(),
    )
}

data class CognitiveMemoryWorldProjection private constructor(
    val id: String,
    val memorySnapshotId: String,
    val observations: List<CognitiveMemoryObservation>,
) {
    init {
        require(memorySnapshotId.isNotBlank())
        require(observations.isNotEmpty())
        require(observations.map { it.memoryId to it.revision }.distinct().size == observations.size)
        require(id == expectedId())
    }

    val directWorldStateMutationAllowed: Boolean get() = false
    val truthAuthority: Boolean get() = false

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "level7-memory-world-projection/v1",
        memorySnapshotId,
        *observations.sortedBy { it.fingerprint() }.map { it.fingerprint() }.toTypedArray(),
    )

    private fun expectedId(): String = "memory-world-projection:${fingerprint()}"

    companion object {
        fun create(
            memorySnapshotId: String,
            observations: Collection<CognitiveMemoryObservation>,
        ): CognitiveMemoryWorldProjection {
            val canonical = observations.distinctBy { it.memoryId to it.revision }.sortedBy { it.fingerprint() }
            require(canonical.isNotEmpty())
            val fp = StableFieldIds.fingerprint(
                "level7-memory-world-projection/v1",
                memorySnapshotId,
                *canonical.map { it.fingerprint() }.toTypedArray(),
            )
            return CognitiveMemoryWorldProjection(
                id = "memory-world-projection:$fp",
                memorySnapshotId = memorySnapshotId,
                observations = canonical,
            )
        }
    }
}
