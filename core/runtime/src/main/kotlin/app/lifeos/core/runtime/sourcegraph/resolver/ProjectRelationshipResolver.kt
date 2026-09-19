package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.source.SourceObjectKind
import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class ProjectRelationshipResolver : DirectedSourceRelationshipResolver {
    override val resolverId: String = "project-relationship"
    override val resolverVersion: String = "m208/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> {
        val results = mutableListOf<DirectedSourceRelationshipResolution>()
        val evidence = mutableListOf<SourceRelationshipEvidence>()
        val sameAccount =
            left.metadata.externalObject.account.fingerprint ==
                right.metadata.externalObject.account.fingerprint

        val leftHint = left.metadata.projectHint
        val rightHint = right.metadata.projectHint
        val leftId = projectIdentity(left)
        val rightId = projectIdentity(right)

        if (sameAccount && leftId != null && leftId == rightId) {
            evidence += positive(
                left.sourceRef,
                RelationshipEvidenceFamily.IDENTITY,
                RelationshipEvidenceKind.PROJECT_ID,
                EvidenceStrength.DETERMINISTIC,
                1.0,
                "exact provider-account project id match",
            )
        }

        val leftRepository = leftHint?.repository?.canonicalText()
            ?: left.metadata.technical.attributes["repository:id"]?.canonicalText()
        val rightRepository = rightHint?.repository?.canonicalText()
            ?: right.metadata.technical.attributes["repository:id"]?.canonicalText()
        if (leftRepository != null && leftRepository == rightRepository) {
            evidence += positive(
                right.sourceRef,
                RelationshipEvidenceFamily.OPERATIONAL,
                RelationshipEvidenceKind.REPOSITORY_ID,
                EvidenceStrength.STRONG,
                0.94,
                "same explicit repository identity",
            )
        }

        val leftIssue = leftHint?.issue?.canonicalText()
            ?: left.metadata.technical.attributes["issue:id"]?.canonicalText()
        val rightIssue = rightHint?.issue?.canonicalText()
            ?: right.metadata.technical.attributes["issue:id"]?.canonicalText()
        if (leftIssue != null && leftIssue == rightIssue) {
            evidence += positive(
                right.sourceRef,
                RelationshipEvidenceFamily.CONTEXT,
                RelationshipEvidenceKind.ISSUE_ID,
                EvidenceStrength.STRONG,
                0.91,
                "same explicit project issue identity",
            )
        }

        val leftName = leftHint?.projectName?.canonicalText()
        val rightName = rightHint?.projectName?.canonicalText()
        if (leftName != null && leftName == rightName) {
            evidence += positive(
                right.sourceRef,
                RelationshipEvidenceFamily.SEMANTIC,
                RelationshipEvidenceKind.TITLE,
                EvidenceStrength.SUPPORTING,
                0.68,
                "normalized project name match",
            )
        }

        actorKey(left)?.let { leftActor ->
            if (leftActor == actorKey(right)) {
                evidence += positive(
                    right.sourceRef,
                    RelationshipEvidenceFamily.ENTITY,
                    RelationshipEvidenceKind.ENTITY_MATCH,
                    EvidenceStrength.SUPPORTING,
                    0.65,
                    "same explicit project actor identity",
                )
            }
        }

        if (evidence.isNotEmpty()) {
            val ordered = listOf(left.sourceRef, right.sourceRef).sortedWith(REF_ORDER)
            results += DirectedSourceRelationshipResolution(
                sourceRef = ordered[0],
                targetRef = ordered[1],
                type = SourceRelationshipType.SAME_PROJECT,
                evidence = evidence,
            )
        }

        membership(left, right)?.let(results::add)
        membership(right, left)?.let(results::add)

        return results
            .distinctBy { Triple(it.sourceRef, it.targetRef, it.type) }
            .sortedWith(
                compareBy<DirectedSourceRelationshipResolution> { it.sourceRef.photonId.value }
                    .thenBy { it.targetRef.photonId.value }
                    .thenBy { it.type.name }
            )
    }

    private fun membership(
        member: SourceResolutionInput,
        possibleProject: SourceResolutionInput,
    ): DirectedSourceRelationshipResolution? {
        if (possibleProject.metadata.objectKind != SourceObjectKind.PROJECT) return null
        val memberProjectId = member.metadata.projectHint?.explicitProjectId?.canonicalText()
            ?: member.metadata.technical.attributes["project:id"]?.canonicalText()
            ?: return null
        val targetProjectId = projectIdentity(possibleProject)?.canonicalText() ?: return null
        if (memberProjectId != targetProjectId) return null
        if (
            member.metadata.externalObject.provider.providerId !=
                possibleProject.metadata.externalObject.provider.providerId
        ) return null

        return DirectedSourceRelationshipResolution(
            sourceRef = member.sourceRef,
            targetRef = possibleProject.sourceRef,
            type = SourceRelationshipType.BELONGS_TO_PROJECT,
            evidence = listOf(
                positive(
                    member.sourceRef,
                    RelationshipEvidenceFamily.OPERATIONAL,
                    RelationshipEvidenceKind.PROJECT_ID,
                    EvidenceStrength.DETERMINISTIC,
                    1.0,
                    "explicit project membership id",
                )
            ),
        )
    }

    private fun projectIdentity(input: SourceResolutionInput): String? =
        input.metadata.projectHint?.explicitProjectId?.trim()?.takeIf { it.isNotEmpty() }
            ?: input.metadata.technical.attributes["project:id"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: input.metadata.externalObject.externalId
                .takeIf { input.metadata.objectKind == SourceObjectKind.PROJECT }
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    private fun actorKey(input: SourceResolutionInput): String? =
        input.metadata.actor?.actorId?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
            ?: input.metadata.actor?.address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { "address:$it" }

    private fun positive(
        sourceRef: PhotonRevisionRef,
        family: RelationshipEvidenceFamily,
        kind: RelationshipEvidenceKind,
        strength: EvidenceStrength,
        confidence: Double,
        explanation: String,
    ): SourceRelationshipEvidence = SourceRelationshipEvidence.create(
        family = family,
        kind = kind,
        strength = strength,
        polarity = EvidencePolarity.POSITIVE,
        sourceRef = sourceRef,
        confidence = confidence,
        explanation = explanation,
    )

    private fun String.canonicalText(): String =
        trim().replace(Regex("\\s+"), " ").lowercase().takeIf { it.isNotEmpty() } ?: ""

    private companion object {
        val REF_ORDER = compareBy<PhotonRevisionRef> { it.photonId.value }.thenBy { it.revision }
    }
}
