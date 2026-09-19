package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.source.SourceMetadataRepository
import app.lifeos.core.runtime.sourcegraph.PhotonBackedSourceRelationshipRepository
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEdge
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipPolicy
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipWriteResult
import java.time.Instant

class CanonicalSourceResolutionCoordinator(
    private val metadata: SourceMetadataRepository,
    private val relationships: PhotonBackedSourceRelationshipRepository,
    private val policy: SourceRelationshipPolicy = SourceRelationshipPolicy(),
    private val identityResolvers: List<SourceRelationshipResolver> = listOf(
        ExactSourceIdentityResolver(),
        AccountIdentityResolver(),
        PersonIdentityResolver(),
        OrganizationIdentityResolver(),
    ),
    private val directedResolvers: List<DirectedSourceRelationshipResolver> = listOf(
        ConversationRelationshipResolver(),
        DocumentRelationshipResolver(),
        ProjectRelationshipResolver(),
        DecisionRelationshipResolver(),
        GoalTaskRelationshipResolver(),
        EventRelationshipResolver(),
    ),
) {
    init {
        val ids = identityResolvers.map { it.resolverId } + directedResolvers.map { it.resolverId }
        require(ids.distinct().size == ids.size) {
            "Canonical source resolver ids must be unique"
        }
    }

    suspend fun resolve(
        firstRef: PhotonRevisionRef,
        secondRef: PhotonRevisionRef,
        evaluatedAt: Instant,
    ): List<SourceRelationshipWriteResult> {
        require(firstRef != secondRef) {
            "Canonical source resolution requires two distinct exact source revisions"
        }
        val ordered = listOf(firstRef, secondRef).sortedWith(REF_ORDER)
        val leftRecord = requireNotNull(metadata.load(ordered[0])) {
            "Missing canonical source metadata for first import-resolution input"
        }
        val rightRecord = requireNotNull(metadata.load(ordered[1])) {
            "Missing canonical source metadata for second import-resolution input"
        }
        val left = SourceResolutionInput(leftRecord.sourceRef, leftRecord.metadata)
        val right = SourceResolutionInput(rightRecord.sourceRef, rightRecord.metadata)

        val proposals = mutableListOf<DirectedSourceRelationshipResolution>()
        identityResolvers.forEach { resolver ->
            resolver.resolve(left, right).forEach { proposal ->
                proposals += DirectedSourceRelationshipResolution(
                    sourceRef = left.sourceRef,
                    targetRef = right.sourceRef,
                    type = proposal.type,
                    evidence = proposal.evidence,
                    blockers = proposal.blockers,
                )
            }
        }
        directedResolvers.forEach { resolver ->
            proposals += resolver.resolve(left, right)
        }

        val grouped = proposals
            .groupBy { Triple(it.sourceRef, it.targetRef, it.type) }
            .entries
            .sortedWith(
                compareBy<Map.Entry<Triple<PhotonRevisionRef, PhotonRevisionRef, SourceRelationshipType>, List<DirectedSourceRelationshipResolution>>> {
                    it.key.first.photonId.value
                }.thenBy { it.key.first.revision }
                    .thenBy { it.key.second.photonId.value }
                    .thenBy { it.key.second.revision }
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
            val effectiveEvaluatedAt = maxOf(
                evaluatedAt,
                existing?.lastEvaluatedAt ?: evaluatedAt,
            )
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
                lastEvaluatedAt = effectiveEvaluatedAt,
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
        const val RESOLVER_ID = "canonical-source-resolution"
        const val RESOLVER_VERSION = "m209/v1"
        private val REF_ORDER =
            compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
    }
}
