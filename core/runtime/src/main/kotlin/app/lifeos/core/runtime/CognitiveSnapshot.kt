package app.lifeos.core.runtime

import java.security.MessageDigest

data class CognitiveSnapshot(
    val schemaVersion: Int,
    val worldRevision: Long,
    val eventSequence: Long,
    val worldRoot: String,
    val payload: ByteArray,
) {
    init {
        require(schemaVersion > 0)
        require(worldRevision >= 0)
        require(eventSequence >= 0)
        require(worldRoot.isNotBlank())
    }
}

data class SnapshotManifest(
    val schemaVersion: Int,
    val worldRevision: Long,
    val eventSequence: Long,
    val worldRoot: String,
    val payloadSha256: String,
)

class SnapshotVerifier {
    fun manifest(snapshot: CognitiveSnapshot): SnapshotManifest = SnapshotManifest(
        schemaVersion = snapshot.schemaVersion,
        worldRevision = snapshot.worldRevision,
        eventSequence = snapshot.eventSequence,
        worldRoot = snapshot.worldRoot,
        payloadSha256 = sha256(snapshot.payload),
    )

    fun verify(snapshot: CognitiveSnapshot, manifest: SnapshotManifest): Boolean =
        snapshot.schemaVersion == manifest.schemaVersion &&
            snapshot.worldRevision == manifest.worldRevision &&
            snapshot.eventSequence == manifest.eventSequence &&
            snapshot.worldRoot == manifest.worldRoot &&
            sha256(snapshot.payload) == manifest.payloadSha256

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Rehydration starts from the latest verified snapshot and replays only its event tail. */
data class SnapshotReplayPlan(val snapshot: CognitiveSnapshot?, val eventsAfterSequence: Long)

class SnapshotCompactor(private val verifier: SnapshotVerifier = SnapshotVerifier()) {
    fun selectLatestVerified(
        candidates: Collection<Pair<CognitiveSnapshot, SnapshotManifest>>,
    ): SnapshotReplayPlan {
        val latest = candidates.asSequence()
            .filter { (snapshot, manifest) -> verifier.verify(snapshot, manifest) }
            .maxWithOrNull(compareBy<Pair<CognitiveSnapshot, SnapshotManifest>>({ it.first.eventSequence }, { it.first.worldRevision }))
            ?.first
        return SnapshotReplayPlan(latest, latest?.eventSequence ?: 0L)
    }
}
