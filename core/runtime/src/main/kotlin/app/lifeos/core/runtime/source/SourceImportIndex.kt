package app.lifeos.core.runtime.source

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexCursor
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.model.source.SourceObjectKind
import java.time.Instant

class SourceImportCandidateCapacityExceededException(
    val candidateCount: Int,
    val capacity: Int,
) : IllegalStateException(
    "Canonical source-import candidate capacity exceeded: $candidateCount > $capacity"
) {
    init {
        require(candidateCount > capacity)
        require(capacity > 0)
    }
}

object SourceImportIndexPhotonFactory {
    const val MIME_TYPE = "application/vnd.lifeos.source-import-index+text"
    const val ROOT_TAG = "source-import-index"
    const val SCHEMA = "source-import-index/v1"

    fun photonId(sourceRef: PhotonRevisionRef): PhotonId = PhotonId(
        "source-import-index-" + StableCognitiveIds.fingerprint(
            "source-import-index-photon/v1",
            sourceRef.photonId.value,
            sourceRef.revision.toString(),
        )
    )

    fun create(
        metadataPhoton: Photon,
        record: SourceMetadataRecord,
    ): Photon {
        val sourceRef = record.sourceRef
        require(metadataPhoton.id == SourceMetadataPhotonFactory.photonId(sourceRef))
        require(metadataPhoton.mimeType == SourceMetadataPhotonFactory.MIME_TYPE)
        require(SourceMetadataPhotonFactory.ROOT_TAG in metadataPhoton.tags)

        return Photon(
            id = photonId(sourceRef),
            revision = 1L,
            content = buildString {
                appendLine("schema=" + SCHEMA)
                appendLine("metadata_id=" + metadataPhoton.id.value)
                appendLine("metadata_revision=" + metadataPhoton.revision)
                append("metadata_fingerprint=" + record.metadata.metadataFingerprint)
            },
            mimeType = MIME_TYPE,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "canonical-source-import-index",
                actor = SCHEMA,
                createdAt = metadataPhoton.provenance.createdAt,
                parentIds = setOf(sourceRef.photonId, metadataPhoton.id),
            ),
            relations = setOf(
                PhotonRelation(
                    target = metadataPhoton.id,
                    type = RelationType.REFERENCES,
                    weight = 1.0,
                )
            ),
            tags = buildSet {
                add("life-memory-management")
                add(ROOT_TAG)
                add("source-import-ref:" + SourceMetadataPhotonFactory.sourceRefFingerprint(sourceRef))
                addAll(SourceImportCandidateKeys.tags(record.metadata))
            },
        )
    }
}

object SourceImportCandidateKeys {
    fun tags(metadata: CanonicalSourceMetadata): Set<String> = buildSet {
        val account = metadata.externalObject.account.fingerprint
        add(key("object", metadata.externalObject.objectFingerprint))

        metadata.actor?.let { actor ->
            actor.actorId?.canonical()?.let { add(key("actor-account", account, it)) }
            actor.address?.canonicalAddress()?.let { add(key("actor-address", it)) }
        }

        metadata.conversation?.let { conversation ->
            conversation.conversationId?.canonical()?.let {
                add(key("conversation", metadata.externalObject.provider.providerId, account, it))
            }
            conversation.threadId?.canonical()?.let {
                add(key("thread", metadata.externalObject.provider.providerId, account, it))
            }
            conversation.participants.forEach { participant ->
                participant.actorId?.canonical()?.let { add(key("actor-account", account, it)) }
                participant.address?.canonicalAddress()?.let { add(key("actor-address", it)) }
            }
        }

        metadata.document?.let { document ->
            document.logicalDocumentId?.canonical()?.let { add(key("document", it)) }
            document.subject?.canonicalText()?.let { add(key("subject-account", account, it)) }
        }

        metadata.file?.let { file ->
            file.binarySha256?.canonical()?.let { add(key("binary-sha256", it)) }
            file.logicalPath?.canonicalPath()?.let { add(key("path-account", account, it)) }
            file.name.canonicalText()?.let { add(key("filename-account", account, it)) }
        }

        metadata.projectHint?.let { project ->
            project.explicitProjectId?.canonical()?.let { add(key("project", it)) }
            project.repository?.canonicalText()?.let { add(key("repository", it)) }
            project.issue?.canonicalText()?.let { add(key("issue", it)) }
            project.projectName?.canonicalText()?.let { add(key("project-name", it)) }
        }

        val attributes = metadata.technical.attributes
        LINK_KEYS.forEach { (attribute, family) ->
            attributes[attribute]?.canonical()?.let { add(key(family, it)) }
        }

        when (metadata.objectKind) {
            SourceObjectKind.PROJECT -> add(key("project", metadata.externalObject.externalId))
            SourceObjectKind.TASK -> add(key("task", metadata.externalObject.externalId))
            SourceObjectKind.CALENDAR_EVENT -> add(key("event", metadata.externalObject.externalId))
            else -> Unit
        }
    }.toSortedSet()

    private fun key(
        family: String,
        vararg values: String,
    ): String = "source-import-key:" + family + ":" + StableCognitiveIds.fingerprint(
        "source-import-candidate-key/v1",
        family,
        *values,
    )

    private fun String.canonical(): String? =
        trim().takeIf { it.isNotEmpty() }

    private fun String.canonicalText(): String? =
        trim().replace(Regex("\\s+"), " ").lowercase().takeIf { it.isNotEmpty() }

    private fun String.canonicalPath(): String? =
        replace('\\', '/').trim().lowercase().takeIf { it.isNotEmpty() }

    private fun String.canonicalAddress(): String? {
        val raw = trim().takeIf { it.isNotEmpty() } ?: return null
        return if ('@' in raw) {
            raw.lowercase()
        } else {
            raw.filter { it.isDigit() || it == '+' }.takeIf { it.isNotEmpty() }
        }
    }

    private val LINK_KEYS = mapOf(
        "entity:organization-id" to "organization",
        "repository:id" to "repository",
        "issue:id" to "issue",
        "project:id" to "project",
        "decision:id" to "decision",
        "decision:proposes-id" to "decision",
        "decision:decides-id" to "decision",
        "decision:approves-id" to "decision",
        "decision:rejects-id" to "decision",
        "decision:revises-id" to "decision",
        "decision:implements-id" to "decision",
        "goal:id" to "goal",
        "task:goal-id" to "goal",
        "task:advances-goal-id" to "goal",
        "task:id" to "task",
        "task:blocks-id" to "task",
        "task:depends-on-id" to "task",
        "task:implements-id" to "task",
        "task:resolves-id" to "task",
        "task:next-action-for-id" to "task",
        "event:id" to "event",
        "event:uid" to "event",
        "calendar:uid" to "event",
        "event:belongs-to-id" to "event",
    )
}

class SourceImportIndexRepository(
    private val photons: RevisionedPhotonRepository,
    private val maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
) {
    init {
        require(maxCandidates in 1..HARD_MAX_CANDIDATES)
    }

    suspend fun ensureBackfilled(): Photon {
        photons.load(BACKFILL_MARKER_ID)?.let { marker ->
            validateBackfillMarker(marker)
            return marker
        }

        val indexed = mutableListOf<Photon>()
        var cursor: PhotonIndexCursor? = null
        while (true) {
            val refs = photons.query(
                PhotonIndexQuery(
                    allTags = setOf(SourceMetadataPhotonFactory.ROOT_TAG),
                    latestOnly = true,
                    order = PhotonIndexOrder.IDENTITY,
                    after = cursor,
                    limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
                )
            )
            for (ref in refs) {
                val metadataPhoton = requireNotNull(photons.load(ref)) {
                    "Source metadata index references a missing exact revision"
                }
                val record = SourceMetadataCodec.decode(metadataPhoton.content)
                indexed += ensureIndexed(metadataPhoton, record)
            }
            if (refs.size < PhotonIndexQuery.HARD_PAGE_LIMIT) break
            cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, refs.last())
        }

        val marker = Photon(
            id = BACKFILL_MARKER_ID,
            revision = 1L,
            content = buildString {
                appendLine("schema=" + BACKFILL_SCHEMA)
                append("complete=true")
            },
            mimeType = BACKFILL_MIME_TYPE,
            semanticMass = 0.0,
            energy = 0.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "canonical-source-import-index",
                actor = BACKFILL_SCHEMA,
                createdAt = Instant.EPOCH,
            ),
            tags = setOf(
                "life-memory-management",
                "source-import-index-backfill",
                "source-import-index-backfill:" + BACKFILL_SCHEMA,
            ),
        )
        saveImmutable(marker)
        return marker
    }

    suspend fun ensureIndexed(
        metadataPhoton: Photon,
        record: SourceMetadataRecord,
    ): Photon {
        val desired = SourceImportIndexPhotonFactory.create(metadataPhoton, record)
        return saveImmutable(desired)
    }

    suspend fun candidates(
        sourceRef: PhotonRevisionRef,
        metadata: CanonicalSourceMetadata,
    ): List<PhotonRevisionRef> {
        val found = linkedMapOf<String, PhotonRevisionRef>()
        for (tag in SourceImportCandidateKeys.tags(metadata)) {
            var cursor: PhotonIndexCursor? = null
            while (true) {
                val refs = photons.query(
                    PhotonIndexQuery(
                        allTags = setOf(SourceImportIndexPhotonFactory.ROOT_TAG, tag),
                        latestOnly = true,
                        order = PhotonIndexOrder.IDENTITY,
                        after = cursor,
                        limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
                    )
                )
                for (indexRef in refs) {
                    val indexPhoton = requireNotNull(photons.load(indexRef)) {
                        "Source import index references a missing exact revision"
                    }
                    val record = decodeIndex(indexPhoton)
                    if (record.sourceRef != sourceRef) {
                        val candidateKey =
                            record.sourceRef.photonId.value + "@" + record.sourceRef.revision
                        found[candidateKey] = record.sourceRef
                        if (found.size > maxCandidates) {
                            throw SourceImportCandidateCapacityExceededException(found.size, maxCandidates)
                        }
                    }
                }
                if (refs.size < PhotonIndexQuery.HARD_PAGE_LIMIT) break
                cursor = PhotonIndexCursor(PhotonIndexOrder.IDENTITY, refs.last())
            }
        }
        return found.values.sortedWith(
            compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
        )
    }

    private suspend fun saveImmutable(photon: Photon): Photon {
        val existing = photons.load(photon.id)
        if (existing != null) {
            require(existing == photon) {
                "Source import index identity collides with different content"
            }
            return existing
        }
        return when (val result = photons.saveRevision(photon, expectedPreviousRevision = null)) {
            is PhotonRevisionWriteResult.Created -> result.photon
            is PhotonRevisionWriteResult.Idempotent -> {
                require(result.photon == photon)
                result.photon
            }
            is PhotonRevisionWriteResult.Advanced ->
                error("Immutable source import index unexpectedly advanced")
            is PhotonRevisionWriteResult.Conflict ->
                error("Source import index write conflict: " + result.reason)
        }
    }

    private suspend fun decodeIndex(indexPhoton: Photon): SourceMetadataRecord {
        require(indexPhoton.mimeType == SourceImportIndexPhotonFactory.MIME_TYPE)
        require(SourceImportIndexPhotonFactory.ROOT_TAG in indexPhoton.tags)
        val fields = linkedMapOf<String, String>()
        indexPhoton.content.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val separator = line.indexOf('=')
            require(separator > 0)
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(fields.put(key, value) == null) {
                "Duplicate source import index field: $key"
            }
        }
        require(fields["schema"] == SourceImportIndexPhotonFactory.SCHEMA)
        val metadataId = PhotonId(requireNotNull(fields["metadata_id"]))
        val metadataRevision = requireNotNull(fields["metadata_revision"]).toLong()
        val metadataPhoton = requireNotNull(
            photons.load(PhotonRevisionRef(metadataId, metadataRevision))
        ) {
            "Source import index references missing canonical metadata"
        }
        val record = SourceMetadataCodec.decode(metadataPhoton.content)
        require(fields["metadata_fingerprint"] == record.metadata.metadataFingerprint)
        require(indexPhoton.id == SourceImportIndexPhotonFactory.photonId(record.sourceRef))
        return record
    }

    private fun validateBackfillMarker(marker: Photon) {
        require(marker.id == BACKFILL_MARKER_ID)
        require(marker.mimeType == BACKFILL_MIME_TYPE)
        require("source-import-index-backfill" in marker.tags)
        require(marker.content.lineSequence().any { it == "schema=" + BACKFILL_SCHEMA })
    }

    private companion object {
        const val DEFAULT_MAX_CANDIDATES = 2_048
        const val HARD_MAX_CANDIDATES = 16_384
        const val BACKFILL_SCHEMA = "m209/v1"
        const val BACKFILL_MIME_TYPE = "application/vnd.lifeos.source-import-index-backfill+text"
        val BACKFILL_MARKER_ID = PhotonId(
            "source-import-index-backfill-" + StableCognitiveIds.fingerprint(
                "source-import-index-backfill-marker/v1",
                BACKFILL_SCHEMA,
            )
        )
    }
}
