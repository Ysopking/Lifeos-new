package app.lifeos.core.data.photon

import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonRevisionRef

internal enum class PhotonIndexDeltaOperation {
    CREATE,
    ADVANCE,
    TOMBSTONE,
}

internal data class PhotonIndexDelta(
    val sequence: Long,
    val operation: PhotonIndexDeltaOperation,
    val ref: PhotonRevisionRef,
    val previousHeadRef: PhotonRevisionRef?,
    val newEntry: PhotonIndexEntry,
    val snapshotGeneration: Long,
    val snapshotFingerprint: String,
) {
    init {
        require(sequence > 0L) { "Photon index delta sequence must be positive" }
        require(snapshotGeneration >= 0L) { "Photon index snapshot generation must not be negative" }
        require(snapshotFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Photon index snapshot fingerprint must be lowercase SHA-256"
        }
        require(newEntry.ref == ref) { "Photon index delta ref must match new entry ref" }
        require(newEntry.latest) { "Photon index delta must publish a latest entry" }

        when (operation) {
            PhotonIndexDeltaOperation.CREATE -> {
                require(previousHeadRef == null) { "CREATE delta cannot have a predecessor" }
                require(!newEntry.tombstoned) { "CREATE delta cannot publish a tombstone" }
            }

            PhotonIndexDeltaOperation.ADVANCE -> {
                val previous = requireNotNull(previousHeadRef) {
                    "ADVANCE delta requires a predecessor"
                }
                require(previous.photonId == ref.photonId) {
                    "ADVANCE delta predecessor belongs to another Photon"
                }
                require(previous.revision + 1L == ref.revision) {
                    "ADVANCE delta must advance exactly one revision"
                }
                require(!newEntry.tombstoned) { "ADVANCE delta cannot publish a tombstone" }
            }

            PhotonIndexDeltaOperation.TOMBSTONE -> {
                require(previousHeadRef == ref) {
                    "TOMBSTONE delta must bind to the exact current head"
                }
                require(newEntry.tombstoned) {
                    "TOMBSTONE delta must publish a tombstoned latest entry"
                }
            }
        }
    }
}
