package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.model.source.CanonicalSourceMetadata
import app.lifeos.core.runtime.source.SourceMetadataPhotonFactory
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipState
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import java.time.Instant

enum class LifeEntityType {
    PERSON,
    ORGANIZATION,
    PROJECT,
    ACCOUNT,
    LOCATION,
    DOCUMENT,
    CONVERSATION,
    EVENT,
    GOAL,
    TASK,
    ARTIFACT,
    REPOSITORY,
}

data class LifeEntity(
    val id: String,
    val type: LifeEntityType,
    val label: String,
    val sourcePhotonIds: Set<PhotonId>,
    val confidence: Double,
    val aliases: Set<String> = emptySet(),
)

data class LifeGraphEvent(
    val id: String,
    val occurredAt: Instant,
    val sourcePhotonId: PhotonId,
    val entityIds: Set<String>,
    val tags: Set<String>,
    val summary: String,
)

data class LifeGraphRelationship(
    val id: String,
    val fromEntityId: String,
    val toEntityId: String,
    val type: String,
    val sourcePhotonIds: Set<PhotonId>,
    val confidence: Double,
    val canonicalType: SourceRelationshipType? = null,
    val canonicalState: SourceRelationshipState? = null,
    val sourceRevisionRefs: Set<PhotonRevisionRef> = emptySet(),
    val evidenceFingerprint: String? = null,
) {
    init {
        require(confidence.isFinite() && confidence in 0.0..1.0)
        if (canonicalType != null) {
            require(type == canonicalType.name) {
                "Canonical relationship text/type mismatch"
            }
            requireNotNull(canonicalState) {
                "Canonical relationship requires ledger state"
            }
            require(sourceRevisionRefs.isNotEmpty()) {
                "Canonical relationship requires exact source revision refs"
            }
            require(!evidenceFingerprint.isNullOrBlank()) {
                "Canonical relationship requires evidence fingerprint"
            }
        }
    }
}

data class LifeGraphSnapshot(
    val entities: List<LifeEntity>,
    val events: List<LifeGraphEvent>,
    val relationships: List<LifeGraphRelationship>,
    val fingerprint: String,
)

/**
 * Deterministic, rebuildable projection of explicit entity evidence plus conservative alias and
 * relationship evidence. Candidate aliases are only applied when all qualifying evidence agrees on
 * one canonical label; ambiguous aliases remain separate entities rather than inventing identity.
 */
class LifeGraphProjector {
    fun project(photons: Collection<Photon>): LifeGraphSnapshot {
        val latest = photons.groupBy { it.id }.map { (_, revisions) -> revisions.maxBy { it.revision } }
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })
        val canonicalProjection = CanonicalLifeGraphProjection.project(latest)
        val canonical = latest
            .filterNot { "causal-ledger" in it.tags || "life-memory-management" in it.tags }
            .filterNot(::isCanonicalProjectionInfrastructure)
        val byId = canonical.associateBy { it.id }
        val aliases = resolveAliases(canonical)

        val entitySources = linkedMapOf<Pair<LifeEntityType, String>, MutableSet<PhotonId>>()
        canonical.forEach { photon ->
            explicitEntities(photon).forEach { raw ->
                val key = canonicalEntity(raw, aliases)
                entitySources.getOrPut(key) { linkedSetOf() } += photon.id
            }
        }
        aliases.values.forEach { alias ->
            val key = alias.type to alias.canonical
            if (key in entitySources) entitySources.getValue(key) += alias.sourcePhotonIds
        }

        val entities = entitySources.entries.map { (key, sources) ->
            val (type, label) = key
            val sourceConfidence = sources.mapNotNull(byId::get).map { it.confidence }
            val entityAliases = aliases.values
                .filter { it.type == type && it.canonical.equals(label, ignoreCase = true) }
                .map { it.alias }
                .toSortedSet()
            LifeEntity(
                id = entityId(type, label),
                type = type,
                label = label,
                sourcePhotonIds = sources.toSet(),
                confidence = sourceConfidence.averageOrOne().coerceIn(0.0, 1.0),
                aliases = entityAliases,
            )
        }.sortedBy { it.id }
        val entityIdByKey = entities.associateBy({ it.type to it.label }, { it.id })

        val events = canonical.map { photon ->
            val entityIds = explicitEntities(photon)
                .map { canonicalEntity(it, aliases) }
                .mapNotNull(entityIdByKey::get)
                .toSortedSet()
            LifeGraphEvent(
                id = "event-" + StableCognitiveIds.fingerprint("life-event/v1", photon.id.value, photon.revision.toString()),
                occurredAt = photon.provenance.createdAt,
                sourcePhotonId = photon.id,
                entityIds = entityIds,
                tags = photon.tags.toSortedSet(),
                summary = photon.content.replace(Regex("\\s+"), " ").trim().take(512),
            )
        }

        val coOccurrence = canonical.flatMap { photon ->
            val ids = explicitEntities(photon)
                .map { canonicalEntity(it, aliases) }
                .mapNotNull(entityIdByKey::get)
                .distinct()
                .sorted()
            buildList {
                for (left in ids.indices) {
                    for (right in (left + 1) until ids.size) {
                        add(
                            LifeGraphRelationship(
                                id = "relation-" + StableCognitiveIds.fingerprint(
                                    "life-relation/v1",
                                    ids[left],
                                    ids[right],
                                    photon.id.value,
                                ),
                                fromEntityId = ids[left],
                                toEntityId = ids[right],
                                type = "CO_OCCURRENCE",
                                sourcePhotonIds = setOf(photon.id),
                                confidence = minOf(photon.confidence, MAX_COOCCURRENCE_CONFIDENCE),
                            )
                        )
                    }
                }
            }
        }
        val explicitRelationships = canonical.flatMap { photon ->
            relationshipEvidence(photon).mapNotNull { evidence ->
                val from = entityIdByKey[canonicalEntity(evidence.from, aliases)] ?: return@mapNotNull null
                val to = entityIdByKey[canonicalEntity(evidence.to, aliases)] ?: return@mapNotNull null
                LifeGraphRelationship(
                    id = "relation-" + StableCognitiveIds.fingerprint(
                        "life-relation-evidence/v1",
                        from,
                        to,
                        evidence.type,
                        photon.id.value,
                    ),
                    fromEntityId = from,
                    toEntityId = to,
                    type = evidence.type,
                    sourcePhotonIds = setOf(photon.id),
                    confidence = photon.confidence,
                )
            }
        }
        val legacyRelationships = (coOccurrence + explicitRelationships)
            .groupBy { Triple(it.fromEntityId, it.toEntityId, it.type) }
            .map { (key, matches) ->
                val sources = matches.flatMap { it.sourcePhotonIds }.toSortedSet(compareBy { it.value })
                val confidence = if (key.third == CO_OCCURRENCE_TYPE) {
                    matches.maxOf { it.confidence }.coerceAtMost(MAX_COOCCURRENCE_CONFIDENCE)
                } else {
                    matches.maxOf { it.confidence }
                }
                LifeGraphRelationship(
                    id = "relation-" + StableCognitiveIds.fingerprint(
                        "life-relation-aggregate/v2",
                        key.first,
                        key.second,
                        key.third,
                        *sources.map { it.value }.toTypedArray(),
                    ),
                    fromEntityId = key.first,
                    toEntityId = key.second,
                    type = key.third,
                    sourcePhotonIds = sources,
                    confidence = confidence,
                )
            }
        val allEntities = (entities + canonicalProjection.entities)
            .distinctBy { it.id }
            .sortedBy { it.id }
        val relationships = (legacyRelationships + canonicalProjection.relationships)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<LifeGraphRelationship> { it.fromEntityId }
                    .thenBy { it.toEntityId }
                    .thenBy { it.type }
                    .thenBy { it.id }
            )

        val fingerprint = StableCognitiveIds.fingerprint(
            "life-graph/v3",
            *buildList {
                allEntities.forEach { entity ->
                    add("e:${entity.id}:${entity.type.name}:${java.lang.Double.toHexString(entity.confidence)}:${entity.aliases.joinToString(",")}")
                }
                events.forEach { add("v:${it.id}") }
                relationships.forEach { relationship ->
                    add(
                        "r:${relationship.id}:${relationship.type}:" +
                            "${relationship.canonicalState?.name.orEmpty()}:" +
                            "${relationship.evidenceFingerprint.orEmpty()}:" +
                            java.lang.Double.toHexString(relationship.confidence)
                    )
                }
            }.toTypedArray(),
        )
        return LifeGraphSnapshot(allEntities, events, relationships, fingerprint)
    }

    private data class AliasEvidence(
        val type: LifeEntityType,
        val alias: String,
        val canonical: String,
        val sourcePhotonIds: Set<PhotonId>,
    )

    private data class RelationshipEvidence(
        val type: String,
        val from: Pair<LifeEntityType, String>,
        val to: Pair<LifeEntityType, String>,
    )

    private fun resolveAliases(photons: List<Photon>): Map<Pair<LifeEntityType, String>, AliasEvidence> {
        val candidates = photons.flatMap { photon ->
            if (photon.confidence < MIN_ALIAS_CONFIDENCE) return@flatMap emptyList()
            photon.tags.mapNotNull { tag -> parseAlias(tag, photon.id) }
        }
        return candidates.groupBy { it.type to it.alias.lowercase() }.mapNotNull { (key, matches) ->
            val canonicalLabels = matches.map { it.canonical.lowercase() }.distinct()
            if (canonicalLabels.size != 1) return@mapNotNull null
            val canonicalLabel = matches.minBy { it.canonical }.canonical
            key to AliasEvidence(
                type = matches.first().type,
                alias = matches.minBy { it.alias }.alias,
                canonical = canonicalLabel,
                sourcePhotonIds = matches.flatMap { it.sourcePhotonIds }.toSet(),
            )
        }.toMap()
    }

    private fun parseAlias(tag: String, source: PhotonId): AliasEvidence? {
        if (!tag.startsWith(ALIAS_PREFIX)) return null
        val payload = tag.removePrefix(ALIAS_PREFIX)
        val typeRaw = payload.substringBefore(':', missingDelimiterValue = "")
        val mapping = payload.substringAfter(':', missingDelimiterValue = "")
        val alias = mapping.substringBefore('=', missingDelimiterValue = "").trim()
        val canonical = mapping.substringAfter('=', missingDelimiterValue = "").trim()
        val type = runCatching { LifeEntityType.valueOf(typeRaw.trim().uppercase()) }.getOrNull() ?: return null
        if (alias.isBlank() || canonical.isBlank()) return null
        return AliasEvidence(type, alias, canonical, setOf(source))
    }

    private fun relationshipEvidence(photon: Photon): List<RelationshipEvidence> = photon.tags.mapNotNull { tag ->
        if (!tag.startsWith(RELATIONSHIP_PREFIX)) return@mapNotNull null
        val parts = tag.removePrefix(RELATIONSHIP_PREFIX).split('|')
        if (parts.size != 3) return@mapNotNull null
        val type = parts[0].trim().uppercase().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val from = parseTypedLabel(parts[1]) ?: return@mapNotNull null
        val to = parseTypedLabel(parts[2]) ?: return@mapNotNull null
        RelationshipEvidence(type, from, to)
    }

    private fun parseTypedLabel(raw: String): Pair<LifeEntityType, String>? {
        val type = runCatching {
            LifeEntityType.valueOf(raw.substringBefore(':', missingDelimiterValue = "").trim().uppercase())
        }.getOrNull() ?: return null
        val label = raw.substringAfter(':', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() } ?: return null
        return type to label
    }

    private fun explicitEntities(photon: Photon): Set<Pair<LifeEntityType, String>> = photon.tags.mapNotNull { tag ->
        ENTITY_PREFIXES.entries.firstNotNullOfOrNull { (prefix, type) ->
            if (tag.startsWith(prefix)) {
                tag.substringAfter(prefix).trim().takeIf(String::isNotBlank)?.let { type to it }
            } else null
        }
    }.toSet()

    private fun canonicalEntity(
        raw: Pair<LifeEntityType, String>,
        aliases: Map<Pair<LifeEntityType, String>, AliasEvidence>,
    ): Pair<LifeEntityType, String> {
        val alias = aliases[raw.first to raw.second.lowercase()] ?: return raw
        return raw.first to alias.canonical
    }

    private fun entityId(type: LifeEntityType, label: String): String =
        "entity-" + StableCognitiveIds.fingerprint("life-entity/v1", type.name, label.lowercase())

    private fun isCanonicalProjectionInfrastructure(photon: Photon): Boolean =
        (photon.mimeType == SourceMetadataPhotonFactory.MIME_TYPE &&
            SourceMetadataPhotonFactory.ROOT_TAG in photon.tags) ||
            (photon.mimeType == PhotonBackedSourceRelationshipRepository.MIME_TYPE &&
                PhotonBackedSourceRelationshipRepository.ROOT_TAG in photon.tags)

    private fun List<Double>.averageOrOne(): Double = if (isEmpty()) 1.0 else average()

    private companion object {
        const val MIN_ALIAS_CONFIDENCE = 0.5
        const val MAX_COOCCURRENCE_CONFIDENCE = 0.25
        const val CO_OCCURRENCE_TYPE = "CO_OCCURRENCE"
        const val ALIAS_PREFIX = "entity-alias:"
        const val RELATIONSHIP_PREFIX = "entity-relationship:"
        val ENTITY_PREFIXES = linkedMapOf(
            "person:" to LifeEntityType.PERSON,
            "organization:" to LifeEntityType.ORGANIZATION,
            "project:" to LifeEntityType.PROJECT,
            "account:" to LifeEntityType.ACCOUNT,
            "location:" to LifeEntityType.LOCATION,
            "document:" to LifeEntityType.DOCUMENT,
            "conversation:" to LifeEntityType.CONVERSATION,
            "event:" to LifeEntityType.EVENT,
            "goal:" to LifeEntityType.GOAL,
            "task:" to LifeEntityType.TASK,
            "artifact:" to LifeEntityType.ARTIFACT,
            "repository:" to LifeEntityType.REPOSITORY,
        )
    }
}

data class LifeSourceCursor(
    val sourceId: String,
    val position: String?,
    val adapterVersion: String = DEFAULT_ADAPTER_VERSION,
) {
    init {
        require(sourceId.isNotBlank())
        require(adapterVersion.isNotBlank())
    }

    companion object {
        const val DEFAULT_ADAPTER_VERSION = "life-source-adapter/v1"
    }
}

data class LifeSourceRecord(
    val sourceId: String,
    val recordId: String,
    val observedAt: Instant,
    val payload: String,
    val mimeType: String = "text/plain",
    val tags: Set<String> = emptySet(),
    val confidence: Double = 1.0,
    val metadata: CanonicalSourceMetadata? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(recordId.isNotBlank())
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class LifeIngestBatch(
    val sourceId: String,
    val cursorBefore: LifeSourceCursor,
    val cursorAfter: LifeSourceCursor,
    val perception: PerceptionBatch,
)

/** Permission-aware ingest contract. Adapters must supply already-authorized records. */
class ContinuousLifeIngestEngine(
    private val perception: PerceptionFusionEngine = PerceptionFusionEngine(),
) {
    fun ingest(
        cursor: LifeSourceCursor,
        records: Collection<LifeSourceRecord>,
        nextPosition: String?,
        authorized: Boolean,
    ): LifeIngestBatch {
        require(authorized) { "Life source is not authorized" }
        require(records.all { it.sourceId == cursor.sourceId }) { "All records must belong to the source cursor" }
        val signals = records.distinctBy { it.recordId }.map { record ->
            PerceptionSignal(
                source = PerceptionSource.APP_EVENT,
                sourceId = "${record.sourceId}:${record.recordId}",
                observedAt = record.observedAt,
                payload = record.payload,
                mimeType = record.mimeType,
                confidence = record.confidence,
                salience = 0.5,
                tags = record.tags + setOf(
                    "life-ingest",
                    "source:${record.sourceId}",
                    "source-record:${record.recordId}",
                    "source-adapter:${cursor.adapterVersion}",
                ),
            )
        }
        return LifeIngestBatch(
            sourceId = cursor.sourceId,
            cursorBefore = cursor,
            cursorAfter = LifeSourceCursor(cursor.sourceId, nextPosition, cursor.adapterVersion),
            perception = perception.fuse(signals),
        )
    }
}
