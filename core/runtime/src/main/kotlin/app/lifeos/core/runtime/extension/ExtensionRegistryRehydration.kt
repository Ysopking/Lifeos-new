package app.lifeos.core.runtime.extension

import app.lifeos.core.field.StableFieldIds

data class ExtensionRegistryHead private constructor(
    val revision: Long,
    val activeSnapshotId: String,
    val predecessorSnapshotId: String?,
    val snapshotFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(revision > 0) { "Extension registry head revision must be positive" }
        require(activeSnapshotId.isNotBlank()) { "Extension registry head snapshot id must not be blank" }
        require(predecessorSnapshotId?.isNotBlank() != false) {
            "Extension registry predecessor snapshot id must not be blank"
        }
        require(snapshotFingerprint.isNotBlank()) { "Extension registry snapshot fingerprint must not be blank" }
        require(fingerprint == expectedFingerprint()) {
            "Extension registry head fingerprint does not match content"
        }
    }

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "extension-registry-head/v1",
        revision.toString(),
        activeSnapshotId,
        predecessorSnapshotId.orEmpty(),
        snapshotFingerprint,
    )

    companion object {
        fun create(
            revision: Long,
            snapshot: ExtensionRegistrySnapshot,
            predecessorSnapshotId: String?,
        ): ExtensionRegistryHead {
            val fingerprint = StableFieldIds.fingerprint(
                "extension-registry-head/v1",
                revision.toString(),
                snapshot.id,
                predecessorSnapshotId.orEmpty(),
                snapshot.fingerprint(),
            )
            return ExtensionRegistryHead(
                revision = revision,
                activeSnapshotId = snapshot.id,
                predecessorSnapshotId = predecessorSnapshotId,
                snapshotFingerprint = snapshot.fingerprint(),
                fingerprint = fingerprint,
            )
        }

        fun restore(
            revision: Long,
            activeSnapshotId: String,
            predecessorSnapshotId: String?,
            snapshotFingerprint: String,
            fingerprint: String,
        ): ExtensionRegistryHead = ExtensionRegistryHead(
            revision = revision,
            activeSnapshotId = activeSnapshotId,
            predecessorSnapshotId = predecessorSnapshotId,
            snapshotFingerprint = snapshotFingerprint,
            fingerprint = fingerprint,
        )
    }
}

interface ExtensionRegistrySnapshotRepository {
    suspend fun save(snapshot: ExtensionRegistrySnapshot)
    suspend fun load(id: String): ExtensionRegistrySnapshot?
}

interface ExtensionRegistryHeadRepository {
    suspend fun load(): ExtensionRegistryHead?

    /**
     * Compare-and-set is the only head mutation primitive. A null expected revision means the head
     * must not exist yet. Implementations must persist atomically.
     */
    suspend fun compareAndSet(
        expectedRevision: Long?,
        next: ExtensionRegistryHead,
    ): Boolean
}

data class ExtensionRegistryRehydrationReport(
    val head: ExtensionRegistryHead?,
    val snapshot: ExtensionRegistrySnapshot?,
    val restoredEntries: Int,
) {
    init {
        require(restoredEntries >= 0)
        require((head == null) == (snapshot == null))
        require(restoredEntries == (snapshot?.entries?.size ?: 0))
    }
}

/**
 * B149 boot rehydration is bounded: read one authoritative head, then load that exact immutable
 * snapshot. It never scans all historical extension state and never treats persistence as
 * activation authority.
 */
class ExtensionRegistryRehydrator(
    private val heads: ExtensionRegistryHeadRepository,
    private val snapshots: ExtensionRegistrySnapshotRepository,
) {
    suspend fun rehydrate(): ExtensionRegistryRehydrationReport {
        val head = heads.load()
            ?: return ExtensionRegistryRehydrationReport(
                head = null,
                snapshot = null,
                restoredEntries = 0,
            )

        val snapshot = requireNotNull(snapshots.load(head.activeSnapshotId)) {
            "Extension registry head points to missing snapshot"
        }
        require(snapshot.id == head.activeSnapshotId) {
            "Extension registry head resolved a different snapshot identity"
        }
        require(snapshot.fingerprint() == head.snapshotFingerprint) {
            "Extension registry head snapshot fingerprint mismatch"
        }

        val canonical = ExtensionRegistrySnapshot.create(snapshot.entries)
        require(canonical.id == snapshot.id) {
            "Extension registry snapshot content no longer matches its id"
        }
        require(canonical.topologicalOrder == snapshot.topologicalOrder) {
            "Extension registry snapshot dependency order is not canonical"
        }
        require(!snapshot.directActivationAllowed) {
            "Extension registry snapshot must remain non-authoritative for activation"
        }

        return ExtensionRegistryRehydrationReport(
            head = head,
            snapshot = snapshot,
            restoredEntries = snapshot.entries.size,
        )
    }
}
