package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.source.SourceMetadataRepository
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEdge
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipPolicy
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipWriteResult
import java.time.Instant

class StructuredRelationshipCoordinator(
    private val metadata: SourceMetadataRepository,
    private val relationships: PhotonBackedSourceRelationshipRepository,
    private val policy: SourceRelationshipPolicy = SourceRelationshipPolicy(),
    private val resolvers: List<DirectedSourceRelationshipResolver> = listOf(
        ConversationRelationshipResolver(),
        DocumentRelationshipResolver(),
    ),
) {
    init {
        require(resolvers.map { it.resolverId }.distinct().size == resolvers.size)
    }

    suspend fun resolve(
        leftRef: PhotonRevisionRef,
        rightRef: PhotonRevisionRef,
        evaluatedAt: Instant,
    ): List<SourceRelationshipWriteResult> {
        require(leftRef != rightRef)
        val leftRecord = requireNotNull(metadata.load(leftRef)) {
            "Missing canonical source metadata for left structured resolution input"
        }
        val rightRecord = requireNotNull(metadata.load(rightRef)) {
            "Missing canonical source metadata for right structured resolution input"
        }
        val left = SourceResolutionInput(leftRecord.sourceRef, leftRecord.metadata)
        val right = SourceResolutionInput(rightRecord.sourceRef, rightRecord.metadata)

        val grouped = resolvers
            .flatMap { resolver -> resolver.resolve(left, right) }
            .groupBy { Triple(it.sourceRef, it.targetRef, it.type) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<Triple<PhotonRevisionRef, PhotonRevisionRef, app.lifeos.core.runtime.sourcegraph.SourceRelationshipType>, List<DirectedSourceRelationshipResolution>>> {
                    it.key.first.photonId.value
                }.thenBy { it.key.second.photonId.value }
                    .thenBy { it.key.third.name }
            )

        return grouped.map { (key, candidates) ->
            val evidence = candidates
                .flatMap { it.evidence }
                .distinctBy { it.evidenceId }
            val blockers = candidates
                .flatMap { it.blockers }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            val decision = policy.evaluate(key.third, evidence, blockers)
            val existing = relationships.load(key.first, key.second, key.third)
            val desired = SourceRelationshipEdge(
                source = key.first,
                target = key.second,
                type = key.third,
                state = decision.state,
                confidence = decision.confidence,
                positiveEvidence = decision.positiveEvidence,
                negativeEvidence = decision.negativeEvidence,
                blockers = decision.blockers,
                resolverId = RESOLVER_ID,
                resolverVersion = RESOLVER_VERSION,
                createdAt = existing?.createdAt ?: evaluatedAt,
                lastEvaluatedAt = evaluatedAt,
            )
            if (existing != null && existing.sameResolutionAs(desired)) {
                relationships.save(existing)
            } else {
                relationships.save(desired)
            }
        }
    }

    private fun SourceRelationshipEdge.sameResolutionAs(other: SourceRelationshipEdge): Boolean =
        source == other.source &&
            target == other.target &&
            type == other.type &&
            state == other.state &&
            confidence == other.confidence &&
            positiveEvidence == other.positiveEvidence &&
            negativeEvidence == other.negativeEvidence &&
            blockers == other.blockers &&
            resolverId == other.resolverId &&
            resolverVersion == other.resolverVersion

    companion object {
        const val RESOLVER_ID = "structured-relationship-coordinator"
        const val RESOLVER_VERSION = "m207/v1"
    }
}
