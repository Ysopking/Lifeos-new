package app.lifeos.core.runtime.thought

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.field.TemporalValidity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.RelationType
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ThoughtProjectionInput(
    val photon: Photon,
    val fieldDomainId: FieldDomainId,
    val semanticKey: String,
    val validity: TemporalValidity = TemporalValidity.UNBOUNDED,
    val verification: ThoughtVerificationStatus = ThoughtVerificationStatus.UNVERIFIED,
) {
    init {
        require(semanticKey.isNotBlank()) { "Thought projection semantic key must not be blank" }
    }
}

sealed interface ThoughtProjectionResult {
    val snapshot: ThoughtMatrixSnapshot

    data class Applied(
        override val snapshot: ThoughtMatrixSnapshot,
        val replacedRevision: Long? = null,
    ) : ThoughtProjectionResult

    data class Unchanged(override val snapshot: ThoughtMatrixSnapshot) : ThoughtProjectionResult

    data class Stale(
        override val snapshot: ThoughtMatrixSnapshot,
        val existingRevision: Long,
        val rejectedRevision: Long,
    ) : ThoughtProjectionResult

    data class Conflict(
        override val snapshot: ThoughtMatrixSnapshot,
        val conflict: ThoughtProjectionConflict,
    ) : ThoughtProjectionResult
}

data class ThoughtMatrixRebuildReport(
    val snapshot: ThoughtMatrixSnapshot,
    val changed: Boolean,
    val acceptedPhotonIds: List<PhotonId>,
    val conflicts: List<ThoughtProjectionConflict>,
)

/**
 * Rebuildable projection over durable Photon truth. The matrix never mutates source Photons and
 * does not become an ownership store: callers can discard it and reconstruct the same content
 * fingerprint from the same projection inputs.
 */
class ThoughtMatrixV2(
    private val now: () -> Instant = Instant::now,
) {
    private data class ProjectedSource(
        val node: ThoughtNode,
        val relations: List<ThoughtRelation>,
        val fingerprint: String,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ThoughtMatrixSnapshot.empty(now()))
    val state: StateFlow<ThoughtMatrixSnapshot> = mutableState.asStateFlow()

    suspend fun project(input: ThoughtProjectionInput): ThoughtProjectionResult = mutex.withLock {
        val projected = projectSource(input)
        val current = mutableState.value
        val existing = current.nodes.firstOrNull { it.photonId == input.photon.id }

        if (existing == null) {
            val next = buildSnapshot(
                revision = current.revision + 1,
                nodes = current.nodes + projected.node,
                relations = current.relations + projected.relations,
                conflicts = current.conflicts.filterNot { it.photonId == input.photon.id },
                capturedAt = now(),
            )
            mutableState.value = next
            return@withLock ThoughtProjectionResult.Applied(next)
        }

        if (projected.node.sourceRevision < existing.sourceRevision) {
            return@withLock ThoughtProjectionResult.Stale(
                snapshot = current,
                existingRevision = existing.sourceRevision,
                rejectedRevision = projected.node.sourceRevision,
            )
        }

        val existingRelations = current.relations.filter { it.sourcePhotonId == existing.photonId }
        val existingFingerprint = projectionFingerprint(existing, existingRelations)
        if (projected.node.sourceRevision == existing.sourceRevision) {
            if (projected.fingerprint == existingFingerprint) {
                return@withLock ThoughtProjectionResult.Unchanged(current)
            }

            val conflict = ThoughtProjectionConflict(
                photonId = existing.photonId,
                sourceRevision = existing.sourceRevision,
                fingerprints = listOf(existingFingerprint, projected.fingerprint).distinct().sorted(),
            )
            val conflicts = (current.conflicts.filterNot { it.photonId == existing.photonId } + conflict)
                .sortedWith(ThoughtMatrixSnapshot.conflictOrdering())
            val nodes = current.nodes.map { node ->
                if (node.photonId == existing.photonId) {
                    node.copy(verification = ThoughtVerificationStatus.CONFLICTED)
                } else {
                    node
                }
            }
            val next = buildSnapshot(
                revision = current.revision + 1,
                nodes = nodes,
                relations = current.relations,
                conflicts = conflicts,
                capturedAt = now(),
            )
            mutableState.value = next
            return@withLock ThoughtProjectionResult.Conflict(next, conflict)
        }

        val next = buildSnapshot(
            revision = current.revision + 1,
            nodes = current.nodes.filterNot { it.photonId == existing.photonId } + projected.node,
            relations = current.relations.filterNot { it.sourcePhotonId == existing.photonId } + projected.relations,
            conflicts = current.conflicts.filterNot { it.photonId == existing.photonId },
            capturedAt = now(),
        )
        mutableState.value = next
        ThoughtProjectionResult.Applied(next, replacedRevision = existing.sourceRevision)
    }

    suspend fun rebuild(
        inputs: Iterable<ThoughtProjectionInput>,
        capturedAt: Instant = now(),
    ): ThoughtMatrixRebuildReport = mutex.withLock {
        val projected = inputs.map(::projectSource)
        val nodes = mutableListOf<ThoughtNode>()
        val relations = mutableListOf<ThoughtRelation>()
        val conflicts = mutableListOf<ThoughtProjectionConflict>()

        projected.groupBy { it.node.photonId }
            .toSortedMap(compareBy { it.value })
            .forEach { (photonId, versions) ->
                val highestRevision = versions.maxOf { it.node.sourceRevision }
                val highest = versions
                    .filter { it.node.sourceRevision == highestRevision }
                    .distinctBy { it.fingerprint }
                    .sortedBy { it.fingerprint }
                if (highest.size > 1) {
                    conflicts += ThoughtProjectionConflict(
                        photonId = photonId,
                        sourceRevision = highestRevision,
                        fingerprints = highest.map { it.fingerprint }.sorted(),
                    )
                } else {
                    nodes += highest.single().node
                    relations += highest.single().relations
                }
            }

        val current = mutableState.value
        val candidate = buildSnapshot(
            revision = current.revision + 1,
            nodes = nodes,
            relations = relations,
            conflicts = conflicts,
            capturedAt = capturedAt,
        )
        val changed = candidate.contentFingerprint != current.contentFingerprint
        val next = if (changed) {
            candidate
        } else {
            current.copy(capturedAt = capturedAt)
        }
        mutableState.value = next
        ThoughtMatrixRebuildReport(
            snapshot = next,
            changed = changed,
            acceptedPhotonIds = next.nodes.map { it.photonId },
            conflicts = next.conflicts,
        )
    }

    suspend fun snapshot(capturedAt: Instant = now()): ThoughtMatrixSnapshot = mutex.withLock {
        mutableState.value.copy(capturedAt = capturedAt)
    }

    private fun projectSource(input: ThoughtProjectionInput): ProjectedSource {
        val photon = input.photon
        val sourceFingerprint = sourcePhotonFingerprint(photon)
        val node = ThoughtNode(
            provenance = ThoughtProvenance(
                sourcePhotonId = photon.id,
                sourceRevision = photon.revision,
                sourceFingerprint = sourceFingerprint,
                source = photon.provenance.source,
                actor = photon.provenance.actor,
                createdAt = photon.provenance.createdAt,
            ),
            fieldDomainId = input.fieldDomainId,
            semanticKey = input.semanticKey,
            summary = photon.content.trim().replace(Regex("\\s+"), " ").take(MAX_SUMMARY_LENGTH),
            semanticMass = photon.semanticMass,
            energy = photon.energy,
            confidence = photon.confidence,
            validity = input.validity,
            lifecycle = lifecycle(photon.phase),
            verification = input.verification,
            tags = photon.tags.toSortedSet(),
        )
        val relations = photon.relations
            .map { relation ->
                ThoughtRelation.create(
                    sourcePhotonId = photon.id,
                    targetPhotonId = relation.target,
                    type = relationType(relation.type),
                    weight = relation.weight,
                    sourceRevision = photon.revision,
                    origin = "photon-relation",
                )
            }
            .sortedBy { it.id }
        return ProjectedSource(
            node = node,
            relations = relations,
            fingerprint = projectionFingerprint(node, relations),
        )
    }

    private fun buildSnapshot(
        revision: Long,
        nodes: Iterable<ThoughtNode>,
        relations: Iterable<ThoughtRelation>,
        conflicts: Iterable<ThoughtProjectionConflict>,
        capturedAt: Instant,
    ) = ThoughtMatrixSnapshot(
        revision = revision,
        nodes = nodes.sortedWith(ThoughtMatrixSnapshot.nodeOrdering()),
        relations = relations.distinctBy { it.id }.sortedBy { it.id },
        conflicts = conflicts.sortedWith(ThoughtMatrixSnapshot.conflictOrdering()),
        capturedAt = capturedAt,
    )

    private fun sourcePhotonFingerprint(photon: Photon): String = StableFieldIds.fingerprint(
        "photon/v1",
        photon.id.value,
        photon.revision.toString(),
        photon.content,
        photon.mimeType,
        photon.phase.name,
        photon.semanticMass.toString(),
        photon.energy.toString(),
        photon.confidence.toString(),
        photon.provenance.source,
        photon.provenance.actor,
        photon.provenance.createdAt.toString(),
        *photon.provenance.parentIds.map { "parent:${it.value}" }.sorted().toTypedArray(),
        *photon.tags.map { "tag:$it" }.sorted().toTypedArray(),
        *photon.relations.map {
            "relation:${it.target.value}:${it.type.name}:${it.weight}"
        }.sorted().toTypedArray(),
    )

    private fun projectionFingerprint(node: ThoughtNode, relations: List<ThoughtRelation>): String =
        StableFieldIds.fingerprint(
            "thought-projection/v1",
            node.photonId.value,
            node.sourceRevision.toString(),
            node.provenance.sourceFingerprint,
            node.fieldDomainId.value,
            node.semanticKey,
            node.summary,
            node.semanticMass.toString(),
            node.energy.toString(),
            node.confidence.toString(),
            node.validity.validFrom?.toString().orEmpty(),
            node.validity.validUntilExclusive?.toString().orEmpty(),
            node.lifecycle.name,
            node.verification.name,
            *node.tags.map { "tag:$it" }.sorted().toTypedArray(),
            *relations.sortedBy { it.id }.map { "relation:${it.id}" }.toTypedArray(),
        )

    private fun lifecycle(phase: PhotonPhase): ThoughtLifecycleStatus = when (phase) {
        PhotonPhase.CREATED,
        PhotonPhase.ACTIVE -> ThoughtLifecycleStatus.ACTIVE
        PhotonPhase.REFLECTING -> ThoughtLifecycleStatus.REFLECTING
        PhotonPhase.CONVERGED -> ThoughtLifecycleStatus.CONVERGED
        PhotonPhase.ARCHIVED -> ThoughtLifecycleStatus.ARCHIVED
    }

    private fun relationType(type: RelationType): ThoughtRelationType = when (type) {
        RelationType.DERIVED_FROM -> ThoughtRelationType.DERIVED_FROM
        RelationType.SUPPORTS -> ThoughtRelationType.SUPPORTS
        RelationType.CONTRADICTS -> ThoughtRelationType.CONTRADICTS
        RelationType.REFERENCES -> ThoughtRelationType.REFERENCES
        RelationType.TRANSFORMS -> ThoughtRelationType.TRANSFORMS
    }

    private companion object {
        const val MAX_SUMMARY_LENGTH = 240
    }
}
