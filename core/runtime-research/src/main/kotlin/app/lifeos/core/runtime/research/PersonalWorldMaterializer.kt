package app.lifeos.core.runtime.research

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.life.SemanticProjectionResult
import app.lifeos.core.runtime.reasoning.TemporalEpisodeGraph
import java.time.Instant

data class PersonalWorldSnapshot(
    val revision: Long,
    val asOf: Instant,
    val financialStateFingerprint: String?,
    val relationshipStateFingerprint: String?,
    val conversationStateFingerprint: String?,
    val lifeGraphFingerprint: String,
    val sourceEvidenceIds: List<String>,
    val temporalEpisodeIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(financialStateFingerprint == null || financialStateFingerprint.isNotBlank())
        require(relationshipStateFingerprint == null || relationshipStateFingerprint.isNotBlank())
        require(conversationStateFingerprint == null || conversationStateFingerprint.isNotBlank())
        require(lifeGraphFingerprint.isNotBlank())
        require(sourceEvidenceIds == sourceEvidenceIds.distinct().sorted())
        require(temporalEpisodeIds == temporalEpisodeIds.distinct().sorted())
        require(
            fingerprint == StableFieldIds.fingerprint(
                "personal-world-snapshot/v1",
                revision.toString(),
                asOf.toString(),
                financialStateFingerprint.orEmpty(),
                relationshipStateFingerprint.orEmpty(),
                conversationStateFingerprint.orEmpty(),
                lifeGraphFingerprint,
                *sourceEvidenceIds.map { "evidence:$it" }.toTypedArray(),
                *temporalEpisodeIds.map { "episode:$it" }.toTypedArray(),
            )
        )
    }

    val directWorldStateMutationAllowed: Boolean
        get() = false

    val effectAuthority: Boolean
        get() = false
}

class PersonalWorldMaterializer {
    fun materialize(
        semanticProjections: Collection<SemanticProjectionResult>,
        temporalEpisodes: Collection<TemporalEpisodeGraph>,
        asOf: Instant,
        revision: Long,
    ): PersonalWorldSnapshot {
        require(revision > 0L)

        val projections = semanticProjections
            .sortedWith(
                compareBy<SemanticProjectionResult> { it.domainId.value }
                    .thenBy { it.projectorId }
                    .thenBy { it.sourceObservationId.value }
            )
        val episodes = temporalEpisodes.sortedBy { it.id }
        val evidenceIds = projections
            .flatMap { projection -> projection.evidence.map { it.id.value } }
            .distinct()
            .sorted()
        val episodeIds = episodes.map { it.id }.distinct().sorted()

        val financeEvidence = projections
            .filter { projection ->
                projection.domainId.value.contains("finance") ||
                    projection.touchedStateDimensions.any {
                        it.value.startsWith("finance.")
                    }
            }
            .flatMap { it.evidence.map { evidence -> evidence.id.value } }
            .distinct()
            .sorted()

        val relationshipEvidence = projections
            .filter { projection ->
                projection.touchedStateDimensions.any {
                    it.value.startsWith("relationship.") ||
                        it.value.startsWith("social.")
                }
            }
            .flatMap { it.evidence.map { evidence -> evidence.id.value } }
            .distinct()
            .sorted()

        val conversationEvidence = projections
            .filter { projection ->
                projection.domainId.value.contains("communication") ||
                    projection.touchedStateDimensions.any {
                        it.value.startsWith("communication.") ||
                            it.value.startsWith("conversation.")
                    }
            }
            .flatMap { it.evidence.map { evidence -> evidence.id.value } }
            .distinct()
            .sorted()

        val lifeGraphFingerprint = StableFieldIds.fingerprint(
            "personal-world-life-graph/v1",
            *evidenceIds.map { "evidence:$it" }.toTypedArray(),
            *episodeIds.map { "episode:$it" }.toTypedArray(),
        )

        fun optionalFingerprint(
            namespace: String,
            ids: List<String>,
        ): String? =
            ids.takeIf { it.isNotEmpty() }?.let {
                StableFieldIds.fingerprint(
                    namespace,
                    *it.toTypedArray(),
                )
            }

        val financial = optionalFingerprint(
            "personal-world-financial/v1",
            financeEvidence,
        )
        val relationship = optionalFingerprint(
            "personal-world-relationship/v1",
            relationshipEvidence,
        )
        val conversation = optionalFingerprint(
            "personal-world-conversation/v1",
            conversationEvidence,
        )

        val fingerprint = StableFieldIds.fingerprint(
            "personal-world-snapshot/v1",
            revision.toString(),
            asOf.toString(),
            financial.orEmpty(),
            relationship.orEmpty(),
            conversation.orEmpty(),
            lifeGraphFingerprint,
            *evidenceIds.map { "evidence:$it" }.toTypedArray(),
            *episodeIds.map { "episode:$it" }.toTypedArray(),
        )

        return PersonalWorldSnapshot(
            revision = revision,
            asOf = asOf,
            financialStateFingerprint = financial,
            relationshipStateFingerprint = relationship,
            conversationStateFingerprint = conversation,
            lifeGraphFingerprint = lifeGraphFingerprint,
            sourceEvidenceIds = evidenceIds,
            temporalEpisodeIds = episodeIds,
            fingerprint = fingerprint,
        )
    }
}
