package app.lifeos.core.runtime.boot

import app.lifeos.core.model.IncrementalPhotonIndexReader
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexChangeOperation
import app.lifeos.core.model.PhotonIndexChanges
import app.lifeos.core.model.PhotonIndexHead
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds

data class IncrementalBootPhotonManifest(
    val indexHead: PhotonIndexHead,
    val latestRefs: List<PhotonRevisionRef>,
) {
    init {
        require(
            latestRefs == latestRefs.sortedWith(
                compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
            )
        ) { "Incremental boot Photon refs must be canonically sorted" }
        require(latestRefs.map { it.photonId }.distinct().size == latestRefs.size) {
            "Incremental boot manifest must contain one current ref per Photon id"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "incremental-boot-photon-manifest/v1",
        indexHead.snapshotGeneration.toString(),
        indexHead.snapshotFingerprint,
        indexHead.lastJournalSequence.toString(),
        *latestRefs.flatMap { ref ->
            listOf(ref.photonId.value, ref.revision.toString())
        }.toTypedArray(),
    )
}

data class IncrementalBootPhotonManifestLoadReport(
    val manifest: IncrementalBootPhotonManifest?,
    val corrupted: Boolean = false,
    val message: String? = null,
) {
    init {
        if (corrupted) require(!message.isNullOrBlank())
        if (!corrupted) require(message == null)
    }
}

/**
 * Rebuildable cache only. Photon payloads and the encrypted Photon index remain authoritative.
 * CAS prevents two concurrent boots from moving the manifest over each other.
 */
interface IncrementalBootPhotonManifestRepository {
    suspend fun loadReport(): IncrementalBootPhotonManifestLoadReport

    suspend fun compareAndSet(
        expectedFingerprint: String?,
        next: IncrementalBootPhotonManifest,
    ): Boolean
}

/**
 * M210 boot source. It discovers current Photon heads from the durable index journal instead of
 * rescanning the Photon vault. Compaction/generation changes explicitly fall back to the current
 * index snapshot. Exact Photon revisions are then loaded and the head is rechecked before the
 * manifest advances, closing the race between discovery and hydration.
 */
class IncrementalPhotonRepositoryBootSource(
    private val repository: RevisionedPhotonRepository,
    private val incrementalIndex: IncrementalPhotonIndexReader,
    private val manifests: IncrementalBootPhotonManifestRepository,
    private val maxHeadRetries: Int = DEFAULT_HEAD_RETRIES,
) : BootPhotonSource {
    init {
        require(maxHeadRetries in 1..MAX_HEAD_RETRIES)
    }

    override suspend fun load(): PhotonLoadReport {
        repeat(maxHeadRetries) {
            val loadedManifest = manifests.loadReport()
            val previous = loadedManifest.manifest.takeUnless { loadedManifest.corrupted }
            val target = resolveTarget(previous)
            val hydrated = hydrate(target.refs)
            val stableHead = incrementalIndex.indexHead()
            if (stableHead != target.head) return@repeat

            if (hydrated.unreadableFiles.isNotEmpty()) {
                return hydrated
            }

            val next = IncrementalBootPhotonManifest(
                indexHead = stableHead,
                latestRefs = target.refs,
            )
            if (previous?.fingerprint == next.fingerprint) {
                return hydrated
            }
            if (manifests.compareAndSet(previous?.fingerprint, next)) {
                return hydrated
            }
        }
        throw IllegalStateException(
            "Photon index changed repeatedly during bounded incremental boot hydration"
        )
    }

    private suspend fun resolveTarget(
        previous: IncrementalBootPhotonManifest?,
    ): TargetHeads {
        if (previous == null) return snapshotTarget()

        return when (val changes = incrementalIndex.changesSince(previous.indexHead)) {
            is PhotonIndexChanges.SnapshotRequired -> snapshotTarget()
            is PhotonIndexChanges.Incremental -> {
                val refs = previous.latestRefs.associateByTo(linkedMapOf()) { it.photonId }
                var consistent = true
                changes.changes.forEach { change ->
                    when (change.operation) {
                        PhotonIndexChangeOperation.CREATE -> {
                            if (refs.containsKey(change.ref.photonId)) consistent = false
                            if (!change.newEntry.tombstoned) {
                                refs[change.ref.photonId] = change.ref
                            }
                        }
                        PhotonIndexChangeOperation.ADVANCE -> {
                            val previousRef = change.previousHeadRef
                            if (refs[change.ref.photonId] != previousRef) consistent = false
                            if (!change.newEntry.tombstoned) {
                                refs[change.ref.photonId] = change.ref
                            }
                        }
                        PhotonIndexChangeOperation.TOMBSTONE -> {
                            if (refs[change.ref.photonId] != change.ref) consistent = false
                            refs.remove(change.ref.photonId)
                        }
                    }
                }
                if (!consistent) {
                    snapshotTarget()
                } else {
                    TargetHeads(
                        head = changes.currentHead,
                        refs = refs.values.sortedWith(REF_ORDER),
                    )
                }
            }
        }
    }

    private suspend fun snapshotTarget(): TargetHeads {
        repeat(SNAPSHOT_STABILITY_RETRIES) {
            val before = incrementalIndex.indexHead()
            val report = repository.indexReport()
            val after = incrementalIndex.indexHead()
            if (before != after) return@repeat
            if (report.unreadableRevisionFiles.isNotEmpty()) {
                return TargetHeads(
                    head = after,
                    refs = report.latestRefs.values.sortedWith(REF_ORDER),
                    indexFailures = report.unreadableRevisionFiles,
                )
            }
            return TargetHeads(
                head = after,
                refs = report.latestRefs.values.sortedWith(REF_ORDER),
            )
        }
        throw IllegalStateException("Photon index did not stabilize during snapshot fallback")
    }

    private suspend fun hydrate(targetRefs: List<PhotonRevisionRef>): PhotonLoadReport {
        val photons = mutableListOf<Photon>()
        val failures = mutableListOf<String>()
        targetRefs.forEach { ref ->
            try {
                val photon = repository.load(ref)
                if (photon == null) {
                    failures += "missing-exact:" + ref.photonId.value + "@" + ref.revision
                } else {
                    require(photon.id == ref.photonId && photon.revision == ref.revision)
                    photons += photon
                }
            } catch (error: Exception) {
                failures += "unreadable-exact:" + ref.photonId.value + "@" + ref.revision
            }
        }
        return PhotonLoadReport(
            photons = photons.sortedBy { it.provenance.createdAt },
            unreadableFiles = failures.distinct().sorted(),
        )
    }

    private data class TargetHeads(
        val head: PhotonIndexHead,
        val refs: List<PhotonRevisionRef>,
        val indexFailures: List<String> = emptyList(),
    ) {
        init {
            require(refs == refs.sortedWith(REF_ORDER))
            require(refs.map { it.photonId }.distinct().size == refs.size)
        }
    }

    private companion object {
        const val DEFAULT_HEAD_RETRIES = 3
        const val MAX_HEAD_RETRIES = 8
        const val SNAPSHOT_STABILITY_RETRIES = 3
        val REF_ORDER =
            compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
    }
}
