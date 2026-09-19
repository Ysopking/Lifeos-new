package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.source.SourceMetadataRepository
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEdge
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipPolicy
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipWriteResult
import java.time.Instant

class EntityResolutionCoordinator(
    private val metadata: SourceMetadataRepository,
    private val relationships: PhotonBackedSourceRelationshipRepository,
    private val policy: SourceRelationshipPolicy = SourceRelationshipPolicy(),
    private val resolvers: List<SourceRelationshipResolver> = listOf(
        ExactSourceIdentityResolver(),
        AccountIdentityResolver(),
        PersonIdentityResolver(),
        OrganizationIdentityResolver(),
    ),
) {
    init {
        require(resolvers.map { it.resolverId }.distinct().size == resolvers.size) {
            "Entity-resolution resolver ids must be unique"
        }
    }

    suspend fun resolve(
        leftRef: PhotonRevisionRef,
        rightRef: PhotonRevisionRef,
        evaluatedAt: Instant,
    ): List<SourceRelationshipWriteResult> {
        require(leftRef != rightRef) { "Entity resolution requires two distinct source revisions" }
        val ordered = listOf(leftRef, rightRef).sortedWith(
            compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
        )
        val leftRecord = requireNotNull(metadata.load(ordered[0])) {
            "Missing canonical source metadata for left resolution input"
        }
        val rightRecord = requireNotNull(metadata.load(ordered[1])) {
            "Missing canonical source metadata for right resolution input"
        }
        val left = SourceResolutionInput(leftRecord.sourceRef, leftRecord.metadata)
        val right = SourceResolutionInput(rightRecord.sourceRef, rightRecord.metadata)

        val proposals = resolvers
            .flatMap { it.resolve(left, right) }
            .groupBy { it.type }
            .toSortedMap(compareBy { it.name })

        return proposals.map { (type, candidates) ->
            val evidence = candidates
                .flatMap { it.evidence }
                .distinctBy { it.evidenceId }
            val blockers = candidates
                .flatMap { it.blockers }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
            val decision = policy.evaluate(type, evidence, blockers)
            val existing = relationships.load(left.sourceRef, right.sourceRef, type)
            val desired = SourceRelationshipEdge(
                source = left.sourceRef,
                target = right.sourceRef,
                type = type,
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
        }.sortedBy { it.edge.edgeId }
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
        const val RESOLVER_ID = "entity-resolution-coordinator"
        const val RESOLVER_VERSION = "m206/v1"
    }
}
