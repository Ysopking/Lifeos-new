package app.lifeos.core.model

data class WorldStatePartition(
    val partitionId: String,
    val activityClass: GraphActivityClass,
    val revision: Long,
    val contentFingerprint: String,
) {
    init { require(partitionId.isNotBlank()); require(revision > 0); require(contentFingerprint.isNotBlank()) }
    val stableFingerprint: String get() = StableCognitiveIds.fingerprint(partitionId, activityClass.name, revision.toString(), contentFingerprint)
}

/** Reference-only world projection. Domain data remains owned by its source subsystem. */
data class WorldState(
    val revision: Long,
    val photonRevisionKeys: Set<String>,
    val moduleStateFingerprints: Set<String>,
    val fieldSnapshotFingerprint: String,
    val resourceSnapshotFingerprint: String,
    val goalProjectionFingerprint: String,
    val memoryProjectionFingerprint: String,
    val partitions: List<WorldStatePartition>,
) {
    init {
        require(revision > 0)
        require(partitions.map { it.partitionId }.distinct().size == partitions.size)
    }
    val merkleRoot: String get() = StableCognitiveIds.fingerprint(
        revision.toString(), fieldSnapshotFingerprint, resourceSnapshotFingerprint,
        goalProjectionFingerprint, memoryProjectionFingerprint,
        *photonRevisionKeys.sorted().toTypedArray(),
        *moduleStateFingerprints.sorted().toTypedArray(),
        *partitions.sortedBy { it.partitionId }.map { it.stableFingerprint }.toTypedArray(),
    )
}
