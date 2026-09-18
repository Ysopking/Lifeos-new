package app.lifeos.core.runtime.level7

import app.lifeos.core.field.StableFieldIds

data class WorldTransitionRule(
    val sourceFingerprint: String,
    val actionFingerprint: String,
    val targetFingerprint: String,
    val confidence: Double,
) {
    init {
        require(sourceFingerprint.isNotBlank())
        require(actionFingerprint.isNotBlank())
        require(targetFingerprint.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class WorldModelSnapshot private constructor(
    val id: String,
    val revision: Long,
    val equationVersion: String,
    val graphCandidateIds: List<String>,
    val transitionRules: List<WorldTransitionRule>,
    val predecessorSnapshotId: String?,
) {
    init {
        require(revision > 0)
        require(equationVersion.isNotBlank())
        require(graphCandidateIds.isNotEmpty())
        require(predecessorSnapshotId == null || predecessorSnapshotId.isNotBlank())
        require(id == expectedId())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "world-model-snapshot/v1",
        revision.toString(),
        equationVersion,
        predecessorSnapshotId.orEmpty(),
        *graphCandidateIds.sorted().toTypedArray(),
        *transitionRules.sortedBy { it.sourceFingerprint }.flatMap {
            listOf(
                it.sourceFingerprint,
                it.actionFingerprint,
                it.targetFingerprint,
                java.lang.Double.toHexString(it.confidence),
            )
        }.toTypedArray(),
    )

    private fun expectedId(): String = "world-model:${fingerprint()}"

    companion object {
        fun create(
            revision: Long,
            equationVersion: String,
            graphCandidateIds: List<String>,
            transitionRules: List<WorldTransitionRule>,
            predecessorSnapshotId: String?,
        ): WorldModelSnapshot {
            val canonicalIds = graphCandidateIds.distinct().sorted()
            val fingerprint = StableFieldIds.fingerprint(
                "world-model-snapshot/v1",
                revision.toString(),
                equationVersion,
                predecessorSnapshotId.orEmpty(),
                *canonicalIds.toTypedArray(),
                *transitionRules.sortedBy { it.sourceFingerprint }.flatMap {
                    listOf(
                        it.sourceFingerprint,
                        it.actionFingerprint,
                        it.targetFingerprint,
                        java.lang.Double.toHexString(it.confidence),
                    )
                }.toTypedArray(),
            )
            return WorldModelSnapshot(
                id = "world-model:$fingerprint",
                revision = revision,
                equationVersion = equationVersion,
                graphCandidateIds = canonicalIds,
                transitionRules = transitionRules,
                predecessorSnapshotId = predecessorSnapshotId,
            )
        }
    }
}

data class WorldModelHead(
    val revision: Long,
    val activeSnapshotId: String,
    val predecessorSnapshotId: String?,
    val fingerprint: String,
) {
    init {
        require(revision > 0)
        require(activeSnapshotId.isNotBlank())
        require(predecessorSnapshotId == null || predecessorSnapshotId.isNotBlank())
        require(fingerprint.isNotBlank())
    }
}

interface WorldModelRepository {
    suspend fun saveSnapshot(snapshot: WorldModelSnapshot)
    suspend fun loadSnapshot(id: String): WorldModelSnapshot?
    suspend fun loadHead(): WorldModelHead?
    suspend fun compareAndSetHead(expectedRevision: Long?, next: WorldModelHead): Boolean
}
