package app.lifeos.core.runtime.sourcegraph

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

enum class RelationshipFamily {
    IDENTITY,
    STRUCTURAL,
    VERSION,
    CONVERSATION,
    ENTITY,
    PROJECT,
    GOAL_TASK,
    DECISION,
    SEMANTIC,
    EVIDENCE,
    PROVENANCE,
    TEMPORAL,
    EVENT,
    ARTIFACT,
}

enum class SourceRelationshipType(
    val family: RelationshipFamily,
    val mergeSensitive: Boolean = false,
) {
    SAME_OBJECT(RelationshipFamily.IDENTITY, true),
    DUPLICATE_OF(RelationshipFamily.IDENTITY, true),
    ALIAS_OF(RelationshipFamily.IDENTITY, true),
    SAME_LOGICAL_DOCUMENT(RelationshipFamily.VERSION, true),
    SAME_PERSON(RelationshipFamily.IDENTITY, true),
    SAME_ORGANIZATION(RelationshipFamily.IDENTITY, true),
    SAME_ACCOUNT(RelationshipFamily.IDENTITY, true),
    SAME_CONVERSATION(RelationshipFamily.CONVERSATION, true),
    SAME_PROJECT(RelationshipFamily.PROJECT, true),
    SAME_EVENT(RelationshipFamily.EVENT, true),

    REVISION_OF(RelationshipFamily.VERSION),
    SUPERSEDES(RelationshipFamily.VERSION),
    BRANCH_OF(RelationshipFamily.VERSION),
    MERGED_FROM(RelationshipFamily.VERSION),
    UPDATED_BY(RelationshipFamily.VERSION),

    PART_OF(RelationshipFamily.STRUCTURAL),
    ATTACHMENT_OF(RelationshipFamily.STRUCTURAL),
    SECTION_OF(RelationshipFamily.STRUCTURAL),

    BELONGS_TO_CONVERSATION(RelationshipFamily.CONVERSATION),
    REPLY_TO(RelationshipFamily.CONVERSATION),
    QUOTES(RelationshipFamily.CONVERSATION),
    FORWARDS(RelationshipFamily.CONVERSATION),
    CONTINUES(RelationshipFamily.CONVERSATION),

    BELONGS_TO_PROJECT(RelationshipFamily.PROJECT),
    BELONGS_TO_GOAL(RelationshipFamily.GOAL_TASK),
    BELONGS_TO_EVENT(RelationshipFamily.EVENT),

    IMPLEMENTS(RelationshipFamily.GOAL_TASK),
    ADVANCES(RelationshipFamily.GOAL_TASK),
    BLOCKS(RelationshipFamily.GOAL_TASK),
    DEPENDS_ON(RelationshipFamily.GOAL_TASK),
    RESOLVES(RelationshipFamily.GOAL_TASK),
    NEXT_ACTION_FOR(RelationshipFamily.GOAL_TASK),

    PROPOSES(RelationshipFamily.DECISION),
    DECIDES(RelationshipFamily.DECISION),
    APPROVES(RelationshipFamily.DECISION),
    REJECTS(RelationshipFamily.DECISION),
    REVISES_DECISION(RelationshipFamily.DECISION),
    IMPLEMENTS_DECISION(RelationshipFamily.DECISION),

    SAME_CLAIM(RelationshipFamily.EVIDENCE, true),
    SUPPORTS(RelationshipFamily.EVIDENCE),
    CONTRADICTS(RelationshipFamily.EVIDENCE),
    REFINES(RelationshipFamily.EVIDENCE),
    EXPLAINS(RelationshipFamily.EVIDENCE),
    SUMMARIZES(RelationshipFamily.EVIDENCE),
    CORRECTS(RelationshipFamily.EVIDENCE),

    SAME_TOPIC(RelationshipFamily.SEMANTIC),
    RELATED_TOPIC(RelationshipFamily.SEMANTIC),
    SUBTOPIC_OF(RelationshipFamily.SEMANTIC),

    AUTHORED_BY(RelationshipFamily.ENTITY),
    SENT_BY(RelationshipFamily.ENTITY),
    SENT_TO(RelationshipFamily.ENTITY),
    INVOLVES_PERSON(RelationshipFamily.ENTITY),
    INVOLVES_ORGANIZATION(RelationshipFamily.ENTITY),

    DERIVED_FROM(RelationshipFamily.PROVENANCE),
    EXTRACTED_FROM(RelationshipFamily.PROVENANCE),
    GENERATED_FROM(RelationshipFamily.PROVENANCE),
    BUILT_FROM(RelationshipFamily.PROVENANCE),
    IMPORTED_FROM(RelationshipFamily.PROVENANCE),
    OBSERVED_FROM_SOURCE(RelationshipFamily.PROVENANCE),

    PRECEDES(RelationshipFamily.TEMPORAL),
    FOLLOWS(RelationshipFamily.TEMPORAL),
    OVERLAPS(RelationshipFamily.TEMPORAL),
}

enum class RelationshipEvidenceFamily {
    IDENTITY,
    STRUCTURAL,
    ENTITY,
    SEMANTIC,
    TEMPORAL,
    SOCIAL,
    OPERATIONAL,
    CONTEXT,
}

enum class RelationshipEvidenceKind {
    EXACT_EXTERNAL_ID,
    HASH,
    THREAD_ID,
    REPLY_ID,
    MESSAGE_ID,
    PROJECT_ID,
    REPOSITORY_ID,
    ISSUE_ID,
    DECISION_ID,
    REQUIREMENT_ID,
    ARTIFACT_ID,
    OUTPUT_ID,
    DOCUMENT_ID,
    VERSION_ID,
    ACCOUNT_ID,
    ACTOR_ID,
    CONTACT_ID,
    ORGANIZATION_ID,
    EMAIL,
    PHONE,
    CALENDAR_UID,
    GIT_SHA,
    PARTICIPANT_OVERLAP,
    FILENAME,
    PATH,
    TITLE,
    SUBJECT,
    ENTITY_MATCH,
    SEMANTIC_SIMILARITY,
    TIME_PROXIMITY,
    USER_CONFIRMATION,
    USER_SEPARATION,
    OPERATIONAL_LINK,
    GOAL_ID,
    TASK_ID,
    EVENT_ID,
    ATTACHMENT_ID,
    QUOTE_LINEAGE,
    FORWARD_LINEAGE,
}

enum class EvidenceStrength {
    DETERMINISTIC,
    STRONG,
    SUPPORTING,
    WEAK,
}

enum class EvidencePolarity {
    POSITIVE,
    NEGATIVE,
}

enum class SourceRelationshipState {
    CANDIDATE,
    MERGE_ELIGIBLE,
    CONFIRMED,
    BLOCKED,
    REJECTED,
    SUPERSEDED,
}

data class SourceRelationshipEvidence(
    val evidenceId: String,
    val family: RelationshipEvidenceFamily,
    val kind: RelationshipEvidenceKind,
    val strength: EvidenceStrength,
    val polarity: EvidencePolarity,
    val sourceRef: PhotonRevisionRef,
    val lineageRoot: PhotonRevisionRef = sourceRef,
    val confidence: Double,
    val explanation: String,
) {
    init {
        require(evidenceId.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))) {
            "Relationship evidence id is not canonical"
        }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(explanation.isNotBlank())
        require(explanation.toByteArray(Charsets.UTF_8).size <= 4 * 1024) {
            "Relationship evidence explanation exceeds bounded size"
        }
    }

    val independenceKey: String
        get() = StableCognitiveIds.fingerprint(
            "relationship-evidence-independence/v1",
            family.name,
            lineageRoot.photonId.value,
            lineageRoot.revision.toString(),
        )

    companion object {
        fun create(
            family: RelationshipEvidenceFamily,
            kind: RelationshipEvidenceKind,
            strength: EvidenceStrength,
            polarity: EvidencePolarity,
            sourceRef: PhotonRevisionRef,
            lineageRoot: PhotonRevisionRef = sourceRef,
            confidence: Double,
            explanation: String,
        ): SourceRelationshipEvidence {
            val id = "evidence-" + StableCognitiveIds.fingerprint(
                "source-relationship-evidence/v1",
                family.name,
                kind.name,
                strength.name,
                polarity.name,
                sourceRef.photonId.value,
                sourceRef.revision.toString(),
                lineageRoot.photonId.value,
                lineageRoot.revision.toString(),
                explanation,
            )
            return SourceRelationshipEvidence(
                evidenceId = id,
                family = family,
                kind = kind,
                strength = strength,
                polarity = polarity,
                sourceRef = sourceRef,
                lineageRoot = lineageRoot,
                confidence = confidence,
                explanation = explanation,
            )
        }
    }
}

data class SourceRelationshipEdge(
    val source: PhotonRevisionRef,
    val target: PhotonRevisionRef,
    val type: SourceRelationshipType,
    val state: SourceRelationshipState,
    val confidence: Double,
    val positiveEvidence: List<SourceRelationshipEvidence>,
    val negativeEvidence: List<SourceRelationshipEvidence>,
    val blockers: List<String>,
    val resolverId: String,
    val resolverVersion: String,
    val createdAt: Instant,
    val lastEvaluatedAt: Instant,
) {
    init {
        require(source != target) { "Relationship edge endpoints must differ" }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(resolverId.matches(Regex("[a-z0-9][a-z0-9._-]{0,127}"))) {
            "Relationship resolver id must be canonical lowercase ASCII"
        }
        require(resolverVersion.isNotBlank())
        require(resolverVersion.toByteArray(Charsets.UTF_8).size <= 256)
        require(!lastEvaluatedAt.isBefore(createdAt))
        require(blockers.none { it.isBlank() })
        require(blockers == blockers.distinct().sorted()) {
            "Relationship blockers must be unique and canonically sorted"
        }
        val all = positiveEvidence + negativeEvidence
        require(all.map { it.evidenceId }.distinct().size == all.size) {
            "Relationship evidence ids must be unique"
        }
        require(positiveEvidence.all { it.polarity == EvidencePolarity.POSITIVE })
        require(negativeEvidence.all { it.polarity == EvidencePolarity.NEGATIVE })
    }

    val edgeId: String
        get() = relationshipEdgeId(source, target, type)

    val independentEvidenceFamilies: Set<RelationshipEvidenceFamily>
        get() = (positiveEvidence + negativeEvidence)
            .groupBy { it.independenceKey }
            .values
            .map { group -> group.minBy { it.family.name }.family }
            .toSortedSet(compareBy { it.name })

    val fingerprint: String
        get() = StableCognitiveIds.fingerprint(
            "source-relationship-edge-state/v1",
            edgeId,
            state.name,
            java.lang.Double.toHexString(confidence),
            resolverId,
            resolverVersion,
            createdAt.toString(),
            lastEvaluatedAt.toString(),
            *positiveEvidence.sortedBy { it.evidenceId }.map { "p:" + it.fingerprintPart() }.toTypedArray(),
            *negativeEvidence.sortedBy { it.evidenceId }.map { "n:" + it.fingerprintPart() }.toTypedArray(),
            *blockers.toTypedArray(),
        )
}

fun relationshipEdgeId(
    source: PhotonRevisionRef,
    target: PhotonRevisionRef,
    type: SourceRelationshipType,
): String = "relationship-" + StableCognitiveIds.fingerprint(
    "source-relationship-edge/v1",
    source.photonId.value,
    source.revision.toString(),
    target.photonId.value,
    target.revision.toString(),
    type.name,
)

private fun SourceRelationshipEvidence.fingerprintPart(): String =
    listOf(
        evidenceId,
        family.name,
        kind.name,
        strength.name,
        polarity.name,
        sourceRef.photonId.value,
        sourceRef.revision.toString(),
        lineageRoot.photonId.value,
        lineageRoot.revision.toString(),
        java.lang.Double.toHexString(confidence),
        explanation,
    ).joinToString("|")
