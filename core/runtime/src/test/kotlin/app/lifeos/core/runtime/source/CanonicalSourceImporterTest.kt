package app.lifeos.core.runtime.source

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceAccountRef
import app.lifeos.core.model.source.SourceActorMetadata
import app.lifeos.core.model.source.SourceDocumentMetadata
import app.lifeos.core.model.source.SourceExternalObjectRef
import app.lifeos.core.model.source.SourceFileMetadata
import app.lifeos.core.model.source.SourceMetadataOrigin
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.model.source.SourcePrivacyZone
import app.lifeos.core.model.source.SourceProviderRef
import app.lifeos.core.model.source.SourceTechnicalMetadata
import app.lifeos.core.model.source.SourceTimestamps
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipWriteResult
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanonicalSourceImporterTest {
    private val at = Instant.parse("2026-09-19T17:00:00Z")

    @Test
    fun historicalMetadataIsBackfilledBeforeIdentityResolution() = runTest {
        val repository = MemoryRevisionedPhotonRepository()
        val historical = source("historical")
        repository.saveRevision(historical, expectedPreviousRevision = null)
        SourceMetadataRepository(repository).commit(
            historical,
            personMetadata(
                externalId = "message-a",
                actorId = "actor-7",
                address = "person@example.test",
            ),
        )

        val fresh = source("fresh")
        repository.saveRevision(fresh, expectedPreviousRevision = null)
        val imported = CanonicalSourceImporter(repository).import(
            fresh,
            personMetadata(
                externalId = "message-b",
                actorId = "actor-7",
                address = "person@example.test",
            ),
        )

        assertTrue(PhotonRevisionRef(historical.id, historical.revision) in imported.candidateRefs)
        val person = imported.relationshipWrites.single {
            it.edge.type == SourceRelationshipType.SAME_PERSON
        }
        assertEquals(SourceRelationshipState.CONFIRMED, person.edge.state)
        assertTrue(
            repository.load(SourceImportIndexPhotonFactory.photonId(
                PhotonRevisionRef(historical.id, historical.revision)
            )) != null
        )
        assertTrue(CanonicalSourceImporter.RECEIPT_ROOT_TAG in imported.receipt.tags)
    }

    @Test
    fun overlappingDocumentResolversProduceOneStableLedgerEdgeAndRetryIsIdempotent() = runTest {
        val repository = MemoryRevisionedPhotonRepository()
        val importer = CanonicalSourceImporter(repository)
        val older = source("doc-old")
        val newer = source("doc-new", at.plusSeconds(1))
        repository.saveRevision(older, expectedPreviousRevision = null)
        repository.saveRevision(newer, expectedPreviousRevision = null)

        importer.import(
            older,
            documentMetadata(
                externalId = "external-old",
                logicalId = "logical-42",
                filename = "report.pdf",
                sequence = "1",
            ),
        )
        val first = importer.import(
            newer,
            documentMetadata(
                externalId = "external-new",
                logicalId = "logical-42",
                filename = "report-v2.pdf",
                sequence = "2",
            ),
        )

        val sameDocument = first.relationshipWrites.single {
            it.edge.type == SourceRelationshipType.SAME_LOGICAL_DOCUMENT
        }
        val revision = first.relationshipWrites.single {
            it.edge.type == SourceRelationshipType.REVISION_OF
        }
        assertEquals(SourceRelationshipState.CONFIRMED, sameDocument.edge.state)
        assertEquals(SourceRelationshipState.CONFIRMED, revision.edge.state)
        assertEquals("canonical-source-resolution", sameDocument.edge.resolverId)

        val retry = importer.import(
            newer,
            documentMetadata(
                externalId = "external-new",
                logicalId = "logical-42",
                filename = "report-v2.pdf",
                sequence = "2",
            ),
        )
        assertEquals(first.receipt.id, retry.receipt.id)
        assertTrue(retry.relationshipWrites.all { it is SourceRelationshipWriteResult.Idempotent })
        assertEquals(
            first.relationshipWrites.map { it.photon.revision },
            retry.relationshipWrites.map { it.photon.revision },
        )
    }

    @Test
    fun sameFilenameOnlyIsDiscoveredButCannotBecomeConfirmedMerge() = runTest {
        val repository = MemoryRevisionedPhotonRepository()
        val importer = CanonicalSourceImporter(repository)
        val first = source("file-a")
        val second = source("file-b")
        repository.saveRevision(first, expectedPreviousRevision = null)
        repository.saveRevision(second, expectedPreviousRevision = null)

        importer.import(first, fileMetadata("file-a", "report.pdf", "folder-a/report.pdf"))
        val imported = importer.import(
            second,
            fileMetadata("file-b", "report.pdf", "folder-b/report.pdf"),
        )

        val sameDocument = imported.relationshipWrites.single {
            it.edge.type == SourceRelationshipType.SAME_LOGICAL_DOCUMENT
        }
        assertEquals(SourceRelationshipState.CANDIDATE, sameDocument.edge.state)
    }

    @Test
    fun candidateIndexTagsDoNotExposeRawAddressOrPath() {
        val rawAddress = "Private.Person+Case@Example.Test"
        val rawPath = "Private/Folder/Secret Report.pdf"
        val metadata = CanonicalSourceMetadata(
            objectKind = SourceObjectKind.FILE,
            origin = SourceMetadataOrigin.CONNECTOR,
            privacyZone = SourcePrivacyZone.SENSITIVE,
            externalObject = SourceExternalObjectRef(
                provider = SourceProviderRef("files"),
                account = SourceAccountRef("files", "private-account"),
                objectKind = SourceObjectKind.FILE,
                externalId = "file-1",
                externalVersion = "v1",
            ),
            actor = SourceActorMetadata(
                actorId = "actor-1",
                displayName = "Private Person",
                address = rawAddress,
            ),
            file = SourceFileMetadata(
                name = "Secret Report.pdf",
                logicalPath = rawPath,
                mimeType = "application/pdf",
            ),
        )

        val tags = SourceImportCandidateKeys.tags(metadata)

        assertTrue(tags.isNotEmpty())
        assertFalse(tags.any { rawAddress in it })
        assertFalse(tags.any { rawPath in it })
        assertTrue(tags.all { it.startsWith("source-import-key:") })
    }

    private fun source(
        id: String,
        createdAt: Instant = at,
    ): Photon = Photon(
        id = PhotonId(id),
        revision = 1L,
        content = "source-$id",
        provenance = Provenance("test", "m209", createdAt),
    )

    private fun personMetadata(
        externalId: String,
        actorId: String,
        address: String,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.EMAIL,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef("mail"),
            account = SourceAccountRef("mail", "account-1"),
            objectKind = SourceObjectKind.EMAIL,
            externalId = externalId,
            externalVersion = "v1",
        ),
        timestamps = SourceTimestamps(
            occurredAt = at,
            observedAt = at,
            importedAt = at,
        ),
        actor = SourceActorMetadata(
            actorId = actorId,
            displayName = "Person",
            address = address,
        ),
    )

    private fun documentMetadata(
        externalId: String,
        logicalId: String,
        filename: String,
        sequence: String,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.DOCUMENT,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef("files"),
            account = SourceAccountRef("files", "account-1"),
            objectKind = SourceObjectKind.DOCUMENT,
            externalId = externalId,
            externalVersion = "v" + sequence,
        ),
        timestamps = SourceTimestamps(
            occurredAt = at,
            observedAt = at,
            importedAt = at,
        ),
        document = SourceDocumentMetadata(
            logicalDocumentId = logicalId,
            title = filename,
        ),
        file = SourceFileMetadata(
            name = filename,
            logicalPath = "docs/" + filename,
            mimeType = "application/pdf",
        ),
        technical = SourceTechnicalMetadata(
            attributes = mapOf("document:version-sequence" to sequence),
        ),
    )

    private fun fileMetadata(
        externalId: String,
        filename: String,
        path: String,
    ): CanonicalSourceMetadata = CanonicalSourceMetadata(
        objectKind = SourceObjectKind.FILE,
        origin = SourceMetadataOrigin.CONNECTOR,
        privacyZone = SourcePrivacyZone.PRIVATE,
        externalObject = SourceExternalObjectRef(
            provider = SourceProviderRef("files"),
            account = SourceAccountRef("files", "account-1"),
            objectKind = SourceObjectKind.FILE,
            externalId = externalId,
            externalVersion = "v1",
        ),
        timestamps = SourceTimestamps(
            occurredAt = at,
            observedAt = at,
            importedAt = at,
        ),
        file = SourceFileMetadata(
            name = filename,
            logicalPath = path,
            mimeType = "application/pdf",
        ),
    )

    private class MemoryRevisionedPhotonRepository : RevisionedPhotonRepository {
        private val values = linkedMapOf<PhotonRevisionRef, Photon>()

        override suspend fun save(photon: Photon) {
            val current = load(photon.id)
            when (
                val result = saveRevision(
                    photon = photon,
                    expectedPreviousRevision = current?.revision,
                )
            ) {
                is PhotonRevisionWriteResult.Created,
                is PhotonRevisionWriteResult.Advanced,
                is PhotonRevisionWriteResult.Idempotent -> Unit
                is PhotonRevisionWriteResult.Conflict -> error(result.reason)
            }
        }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val current = load(photon.id)
            if (current != null && current.revision == photon.revision) {
                return if (current == photon) {
                    PhotonRevisionWriteResult.Idempotent(photon, current)
                } else {
                    PhotonRevisionWriteResult.Conflict(
                        photon,
                        current,
                        "same revision differs",
                    )
                }
            }
            if (current == null) {
                if (expectedPreviousRevision != null || photon.revision != 1L) {
                    return PhotonRevisionWriteResult.Conflict(
                        photon,
                        null,
                        "invalid first revision",
                    )
                }
                values[PhotonRevisionRef(photon.id, photon.revision)] = photon
                return PhotonRevisionWriteResult.Created(photon)
            }
            if (
                expectedPreviousRevision != current.revision ||
                photon.revision != current.revision + 1L
            ) {
                return PhotonRevisionWriteResult.Conflict(
                    photon,
                    current,
                    "stale revision",
                )
            }
            values[PhotonRevisionRef(photon.id, photon.revision)] = photon
            return PhotonRevisionWriteResult.Advanced(photon, current)
        }

        override suspend fun load(id: PhotonId): Photon? =
            values.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = values[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            load(id)?.let { PhotonRevisionRef(it.id, it.revision) }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val latestById = values.values.groupBy { it.id }.mapValues { (_, photons) ->
                photons.maxBy { it.revision }
            }
            val source = if (query.latestOnly) latestById.values else values.values
            val filtered = source.asSequence()
                .filter { query.ids.isEmpty() || it.id in query.ids }
                .filter { query.phases.isEmpty() || it.phase in query.phases }
                .filter { query.mimeTypes.isEmpty() || it.mimeType in query.mimeTypes }
                .filter { it.tags.containsAll(query.allTags) }
                .map { PhotonRevisionRef(it.id, it.revision) }
                .toList()
            val ordered = when (query.order) {
                PhotonIndexOrder.IDENTITY -> filtered.sortedWith(
                    compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
                )
                else -> filtered.sortedWith(
                    compareBy<PhotonRevisionRef> {
                        requireNotNull(values[it]).provenance.createdAt
                    }.thenBy { it.photonId.value }.thenBy { it.revision }
                )
            }
            val start = query.after?.let { cursor ->
                val index = ordered.indexOf(cursor.lastRef)
                require(index >= 0)
                index + 1
            } ?: 0
            return ordered.drop(start).take(query.limit)
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latest = values.values.groupBy { it.id }.mapValues { (_, photons) ->
                photons.maxBy { it.revision }
            }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = values.size,
                livePhotonCount = latest.size,
                tombstonedPhotonCount = 0,
                latestRefs = latest.mapValues { (_, photon) ->
                    PhotonRevisionRef(photon.id, photon.revision)
                },
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(
                photons = values.values.groupBy { it.id }.values
                    .map { it.maxBy { photon -> photon.revision } },
                unreadableFiles = emptyList(),
            )

        override suspend fun loadAll(): List<Photon> = loadReport().photons

        override suspend fun delete(id: PhotonId) {
            values.keys.filter { it.photonId == id }.forEach(values::remove)
        }
    }
}
