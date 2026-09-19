package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class DocumentRelationshipResolver : DirectedSourceRelationshipResolver {
    override val resolverId: String = "document-relationship"
    override val resolverVersion: String = "m207/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<DirectedSourceRelationshipResolution> {
        val results = mutableListOf<DirectedSourceRelationshipResolution>()
        val leftDocument = left.metadata.document
        val rightDocument = right.metadata.document

        val sameDocumentEvidence = mutableListOf<SourceRelationshipEvidence>()
        val leftLogicalId = leftDocument?.logicalDocumentId?.trim()?.takeIf { it.isNotEmpty() }
        val rightLogicalId = rightDocument?.logicalDocumentId?.trim()?.takeIf { it.isNotEmpty() }

        if (leftLogicalId != null && leftLogicalId == rightLogicalId) {
            sameDocumentEvidence += positive(
                sourceRef = left.sourceRef,
                family = RelationshipEvidenceFamily.STRUCTURAL,
                kind = RelationshipEvidenceKind.DOCUMENT_ID,
                strength = EvidenceStrength.DETERMINISTIC,
                confidence = 1.0,
                explanation = "exact logical document id match",
            )
        }

        val leftName = left.metadata.file?.name?.canonicalText()
            ?: leftDocument?.title?.canonicalText()
        val rightName = right.metadata.file?.name?.canonicalText()
            ?: rightDocument?.title?.canonicalText()
        if (leftName != null && leftName == rightName) {
            sameDocumentEvidence += positive(
                sourceRef = right.sourceRef,
                family = RelationshipEvidenceFamily.CONTEXT,
                kind = RelationshipEvidenceKind.FILENAME,
                strength = EvidenceStrength.SUPPORTING,
                confidence = 0.6,
                explanation = "same filename/title is supporting evidence only",
            )
        }

        val leftPath = left.metadata.file?.logicalPath?.canonicalText()
        val rightPath = right.metadata.file?.logicalPath?.canonicalText()
        if (leftPath != null && leftPath == rightPath) {
            sameDocumentEvidence += positive(
                sourceRef = right.sourceRef,
                family = RelationshipEvidenceFamily.STRUCTURAL,
                kind = RelationshipEvidenceKind.PATH,
                strength = EvidenceStrength.SUPPORTING,
                confidence = 0.7,
                explanation = "same logical path",
            )
        }

        if (sameDocumentEvidence.isNotEmpty()) {
            val ordered = listOf(left.sourceRef, right.sourceRef).sortedWith(REF_ORDER)
            results += DirectedSourceRelationshipResolution(
                sourceRef = ordered[0],
                targetRef = ordered[1],
                type = SourceRelationshipType.SAME_LOGICAL_DOCUMENT,
                evidence = sameDocumentEvidence,
            )
        }

        explicitParentRevision(left, right)?.let(results::add)
        explicitParentRevision(right, left)?.let(results::add)

        if (leftLogicalId != null && leftLogicalId == rightLogicalId) {
            val leftSequence = versionSequence(left)
            val rightSequence = versionSequence(right)
            when {
                leftSequence != null && rightSequence != null && leftSequence > rightSequence ->
                    results += sequenceRevision(left, right)
                leftSequence != null && rightSequence != null && rightSequence > leftSequence ->
                    results += sequenceRevision(right, left)
            }
        }

        return results
            .distinctBy { listOf(it.sourceRef, it.targetRef, it.type, it.evidence.map { e -> e.evidenceId }) }
            .sortedWith(
                compareBy<DirectedSourceRelationshipResolution> { it.sourceRef.photonId.value }
                    .thenBy { it.targetRef.photonId.value }
                    .thenBy { it.type.name }
            )
    }

    private fun explicitParentRevision(
        child: SourceResolutionInput,
        possibleParent: SourceResolutionInput,
    ): DirectedSourceRelationshipResolution? {
        val parentVersion = child.metadata.technical.attributes["document:parent-external-version"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (parentVersion != possibleParent.metadata.externalObject.externalVersion) return null
        val childLogicalId = child.metadata.document?.logicalDocumentId
        val parentLogicalId = possibleParent.metadata.document?.logicalDocumentId
        if (childLogicalId == null || childLogicalId != parentLogicalId) return null

        return DirectedSourceRelationshipResolution(
            sourceRef = child.sourceRef,
            targetRef = possibleParent.sourceRef,
            type = SourceRelationshipType.REVISION_OF,
            evidence = listOf(
                positive(
                    sourceRef = child.sourceRef,
                    family = RelationshipEvidenceFamily.STRUCTURAL,
                    kind = RelationshipEvidenceKind.VERSION_ID,
                    strength = EvidenceStrength.DETERMINISTIC,
                    confidence = 1.0,
                    explanation = "explicit parent external version",
                )
            ),
        )
    }

    private fun sequenceRevision(
        newer: SourceResolutionInput,
        older: SourceResolutionInput,
    ): DirectedSourceRelationshipResolution = DirectedSourceRelationshipResolution(
        sourceRef = newer.sourceRef,
        targetRef = older.sourceRef,
        type = SourceRelationshipType.REVISION_OF,
        evidence = listOf(
            positive(
                sourceRef = newer.sourceRef,
                family = RelationshipEvidenceFamily.TEMPORAL,
                kind = RelationshipEvidenceKind.VERSION_ID,
                strength = EvidenceStrength.STRONG,
                confidence = 0.9,
                explanation = "explicit monotonic document version sequence",
            )
        ),
    )

    private fun versionSequence(input: SourceResolutionInput): Long? =
        input.metadata.technical.attributes["document:version-sequence"]
            ?.trim()
            ?.toLongOrNull()

    private fun positive(
        sourceRef: app.lifeos.core.model.PhotonRevisionRef,
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
        trim().replace(Regex("\\s+"), " ").lowercase()

    private companion object {
        val REF_ORDER = compareBy<app.lifeos.core.model.PhotonRevisionRef> { it.photonId.value }
            .thenBy { it.revision }
    }
}
