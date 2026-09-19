package app.lifeos.core.runtime.sourcegraph.resolver

import app.lifeos.core.runtime.sourcegraph.EvidencePolarity
import app.lifeos.core.runtime.sourcegraph.EvidenceStrength
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceFamily
import app.lifeos.core.runtime.sourcegraph.RelationshipEvidenceKind
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipEvidence
import app.lifeos.core.runtime.sourcegraph.SourceRelationshipType

class ExactSourceIdentityResolver : SourceRelationshipResolver {
    override val resolverId: String = "exact-source-identity"
    override val resolverVersion: String = "m206/v1"

    override fun resolve(
        left: SourceResolutionInput,
        right: SourceResolutionInput,
    ): List<SourceRelationshipResolution> = buildList {
        val leftObject = left.metadata.externalObject
        val rightObject = right.metadata.externalObject

        if (leftObject.objectFingerprint == rightObject.objectFingerprint) {
            add(
                SourceRelationshipResolution(
                    type = SourceRelationshipType.SAME_OBJECT,
                    evidence = listOf(
                        SourceRelationshipEvidence.create(
                            family = RelationshipEvidenceFamily.IDENTITY,
                            kind = RelationshipEvidenceKind.EXACT_EXTERNAL_ID,
                            strength = EvidenceStrength.DETERMINISTIC,
                            polarity = EvidencePolarity.POSITIVE,
                            sourceRef = left.sourceRef,
                            confidence = 1.0,
                            explanation = "provider/account/object-kind/external-id match",
                        )
                    ),
                )
            )
        }

        val leftHash = left.metadata.file?.binarySha256
        val rightHash = right.metadata.file?.binarySha256
        if (leftHash != null && leftHash == rightHash) {
            add(
                SourceRelationshipResolution(
                    type = SourceRelationshipType.DUPLICATE_OF,
                    evidence = listOf(
                        SourceRelationshipEvidence.create(
                            family = RelationshipEvidenceFamily.IDENTITY,
                            kind = RelationshipEvidenceKind.HASH,
                            strength = EvidenceStrength.DETERMINISTIC,
                            polarity = EvidencePolarity.POSITIVE,
                            sourceRef = left.sourceRef,
                            confidence = 1.0,
                            explanation = "exact binary SHA-256 match",
                        )
                    ),
                )
            )
        }

        val leftDocument = left.metadata.document?.logicalDocumentId
        val rightDocument = right.metadata.document?.logicalDocumentId
        if (leftDocument != null && leftDocument == rightDocument) {
            add(
                SourceRelationshipResolution(
                    type = SourceRelationshipType.SAME_LOGICAL_DOCUMENT,
                    evidence = listOf(
                        SourceRelationshipEvidence.create(
                            family = RelationshipEvidenceFamily.STRUCTURAL,
                            kind = RelationshipEvidenceKind.DOCUMENT_ID,
                            strength = EvidenceStrength.DETERMINISTIC,
                            polarity = EvidencePolarity.POSITIVE,
                            sourceRef = left.sourceRef,
                            confidence = 1.0,
                            explanation = "exact logical document id match",
                        )
                    ),
                )
            )
        }
    }
}
