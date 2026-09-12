package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

enum class LifeEntityType { PERSON, ORGANIZATION, PROJECT, ACCOUNT, LOCATION, DOCUMENT }

data class LifeEntity(
    val id: String,
    val type: LifeEntityType,
    val label: String,
    val sourcePhotonIds: Set<PhotonId>,
    val confidence: Double,
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
)

data class LifeGraphSnapshot(
    val entities: List<LifeEntity>,
    val events: List<LifeGraphEvent>,
    val relationships: List<LifeGraphRelationship>,
    val fingerprint: String,
)

/** Deterministic, rebuildable projection of explicit entity tags and chronological evidence. */
class LifeGraphProjector {
    fun project(photons: Collection<Photon>): LifeGraphSnapshot {
        val canonical = photons.groupBy { it.id }.map { (_, revisions) -> revisions.maxBy { it.revision } }
            .filterNot { "causal-ledger" in it.tags }
            .sortedWith(compareBy<Photon> { it.provenance.createdAt }.thenBy { it.id.value })

        val entitySources = linkedMapOf<Pair<LifeEntityType, String>, MutableSet<PhotonId>>()
        canonical.forEach { photon ->
            explicitEntities(photon).forEach { key -> entitySources.getOrPut(key) { linkedSetOf() } += photon.id }
        }
        val entities = entitySources.entries.map { (key, sources) ->
            val (type, label) = key
            LifeEntity(
                id = entityId(type, label),
                type = type,
                label = label,
                sourcePhotonIds = sources.toSet(),
                confidence = 1.0,
            )
        }.sortedBy { it.id }
        val entityIdByKey = entities.associateBy({ it.type to it.label }, { it.id })

        val events = canonical.map { photon ->
            val entityIds = explicitEntities(photon).mapNotNull(entityIdByKey::get).toSortedSet()
            LifeGraphEvent(
                id = "event-" + StableCognitiveIds.fingerprint("life-event/v1", photon.id.value, photon.revision.toString()),
                occurredAt = photon.provenance.createdAt,
                sourcePhotonId = photon.id,
                entityIds = entityIds,
                tags = photon.tags.toSortedSet(),
                summary = photon.content.replace(Regex("\\s+"), " ").trim().take(512),
            )
        }

        val relationships = canonical.flatMap { photon ->
            val ids = explicitEntities(photon).mapNotNull(entityIdByKey::get).distinct().sorted()
            buildList {
                for (left in ids.indices) {
                    for (right in (left + 1) until ids.size) {
                        val from = ids[left]
                        val to = ids[right]
                        add(
                            LifeGraphRelationship(
                                id = "relation-" + StableCognitiveIds.fingerprint("life-relation/v1", from, to, photon.id.value),
                                fromEntityId = from,
                                toEntityId = to,
                                type = "CO_OCCURRENCE",
                                sourcePhotonIds = setOf(photon.id),
                                confidence = photon.confidence,
                            )
                        )
                    }
                }
            }
        }.groupBy { Triple(it.fromEntityId, it.toEntityId, it.type) }
            .map { (key, matches) ->
                val sources = matches.flatMap { it.sourcePhotonIds }.toSortedSet(compareBy { it.value })
                LifeGraphRelationship(
                    id = "relation-" + StableCognitiveIds.fingerprint(
                        "life-relation-aggregate/v1",
                        key.first,
                        key.second,
                        key.third,
                        *sources.map { it.value }.toTypedArray(),
                    ),
                    fromEntityId = key.first,
                    toEntityId = key.second,
                    type = key.third,
                    sourcePhotonIds = sources,
                    confidence = matches.map { it.confidence }.average().coerceIn(0.0, 1.0),
                )
            }.sortedBy { it.id }

        val fingerprint = StableCognitiveIds.fingerprint(
            "life-graph/v1",
            *buildList {
                entities.forEach { add("e:${it.id}") }
                events.forEach { add("v:${it.id}") }
                relationships.forEach { add("r:${it.id}") }
            }.toTypedArray(),
        )
        return LifeGraphSnapshot(entities, events, relationships, fingerprint)
    }

    private fun explicitEntities(photon: Photon): Set<Pair<LifeEntityType, String>> = photon.tags.mapNotNull { tag ->
        ENTITY_PREFIXES.entries.firstNotNullOfOrNull { (prefix, type) ->
            if (tag.startsWith(prefix)) {
                tag.substringAfter(prefix).trim().takeIf(String::isNotBlank)?.let { type to it }
            } else null
        }
    }.toSet()

    private fun entityId(type: LifeEntityType, label: String): String =
        "entity-" + StableCognitiveIds.fingerprint("life-entity/v1", type.name, label.lowercase())

    private companion object {
        val ENTITY_PREFIXES = linkedMapOf(
            "person:" to LifeEntityType.PERSON,
            "organization:" to LifeEntityType.ORGANIZATION,
            "project:" to LifeEntityType.PROJECT,
            "account:" to LifeEntityType.ACCOUNT,
            "location:" to LifeEntityType.LOCATION,
            "document:" to LifeEntityType.DOCUMENT,
        )
    }
}

data class LifeSourceCursor(val sourceId: String, val position: String?) {
    init { require(sourceId.isNotBlank()) }
}

data class LifeSourceRecord(
    val sourceId: String,
    val recordId: String,
    val observedAt: Instant,
    val payload: String,
    val mimeType: String = "text/plain",
    val tags: Set<String> = emptySet(),
    val confidence: Double = 1.0,
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
                tags = record.tags + setOf("life-ingest", "source:${record.sourceId}", "source-record:${record.recordId}"),
            )
        }
        return LifeIngestBatch(
            sourceId = cursor.sourceId,
            cursorBefore = cursor,
            cursorAfter = LifeSourceCursor(cursor.sourceId, nextPosition),
            perception = perception.fuse(signals),
        )
    }
}
