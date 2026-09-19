package app.lifeos.next.ui

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.matches
import java.security.MessageDigest

internal class PagedPhotonRepositoryFake(
    photons: List<Photon>,
) : RevisionedPhotonRepository {
    private val byRef = photons.associateBy { PhotonRevisionRef(it.id, it.revision) }

    override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> =
        queryEntries(query).map { it.ref }

    override suspend fun queryEntries(query: PhotonIndexQuery): List<PhotonIndexEntry> {
        val ordered = byRef.values
            .groupBy { it.id }
            .map { (_, revisions) -> revisions.maxBy { it.revision } }
            .map(::entry)
            .asSequence()
            .filter { it.matches(query) }
            .sortedWith(
                when (query.order) {
                    PhotonIndexOrder.IDENTITY ->
                        compareBy<PhotonIndexEntry> { it.ref.photonId.value }
                            .thenBy { it.ref.revision }
                    PhotonIndexOrder.NEWEST_FIRST ->
                        compareByDescending<PhotonIndexEntry> { it.createdAt }
                            .thenBy { it.ref.photonId.value }
                            .thenByDescending { it.ref.revision }
                    PhotonIndexOrder.OLDEST_FIRST ->
                        compareBy<PhotonIndexEntry> { it.createdAt }
                            .thenBy { it.ref.photonId.value }
                            .thenBy { it.ref.revision }
                    PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                        compareByDescending<PhotonIndexEntry> { it.semanticMass }
                            .thenByDescending { it.createdAt }
                            .thenBy { it.ref.photonId.value }
                    PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                        compareByDescending<PhotonIndexEntry> { it.confidence }
                            .thenByDescending { it.createdAt }
                            .thenBy { it.ref.photonId.value }
                }
            )
            .toList()
        val start = query.after?.let { cursor ->
            val index = ordered.indexOfFirst { it.ref == cursor.lastRef }
            require(index >= 0) { "cursor missing" }
            index + 1
        } ?: 0
        return ordered.drop(start).take(query.limit)
    }

    override suspend fun indexReport(): PhotonIndexReport {
        val heads = byRef.values.groupBy { it.id }.mapValues { (_, values) -> values.maxBy { it.revision } }
        return PhotonIndexReport(
            formatVersion = 2,
            entryCount = byRef.size,
            livePhotonCount = heads.size,
            tombstonedPhotonCount = 0,
            latestRefs = heads.mapValues { (_, photon) -> PhotonRevisionRef(photon.id, photon.revision) },
        )
    }

    override suspend fun load(ref: PhotonRevisionRef): Photon? = byRef[ref]

    override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
        byRef.keys.filter { it.photonId == id }.maxByOrNull { it.revision }

    override suspend fun load(id: PhotonId): Photon? =
        latestRef(id)?.let(byRef::get)

    override suspend fun loadReport(): PhotonLoadReport =
        PhotonLoadReport(
            photons = byRef.values.groupBy { it.id }.map { (_, revisions) -> revisions.maxBy { it.revision } },
            unreadableFiles = emptyList(),
        )

    override suspend fun loadAll(): List<Photon> = loadReport().photons

    override suspend fun save(photon: Photon) {
        error("Test fake is read-only")
    }

    override suspend fun saveRevision(
        photon: Photon,
        expectedPreviousRevision: Long?,
    ): PhotonRevisionWriteResult = error("Test fake is read-only")

    override suspend fun delete(id: PhotonId) {
        error("Test fake is read-only")
    }

    private fun entry(photon: Photon) = PhotonIndexEntry(
        ref = PhotonRevisionRef(photon.id, photon.revision),
        createdAt = photon.provenance.createdAt,
        phase = photon.phase,
        mimeType = photon.mimeType,
        tags = photon.tags,
        semanticMass = photon.semanticMass,
        confidence = photon.confidence,
        contentFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(photon.content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) },
        latest = true,
    )
}
