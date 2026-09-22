package app.lifeos.core.model

enum class DocumentArgumentStepRole {
    INTRODUCE,
    DEVELOP,
    SYNTHESIZE,
}

enum class DocumentArgumentRelationKind {
    SEQUENCE,
    SUPPORTS,
    ELABORATES,
    CONTRASTS,
}

data class DocumentArgumentRelationEvidence(
    val fromClaimId: String,
    val toClaimId: String,
    val kind: DocumentArgumentRelationKind,
    val evidenceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(fromClaimId.isNotBlank())
        require(toClaimId.isNotBlank())
        require(fromClaimId != toClaimId)
        require(kind != DocumentArgumentRelationKind.SEQUENCE) {
            "B433 sequence is structural and does not use semantic relation evidence"
        }
        require(evidenceFingerprint.matches(SHA_256_B433))
        require(
            fingerprint == relationEvidenceFingerprint(
                fromClaimId,
                toClaimId,
                kind,
                evidenceFingerprint,
            )
        )
    }

    companion object {
        fun create(
            fromClaimId: String,
            toClaimId: String,
            kind: DocumentArgumentRelationKind,
            evidenceFingerprint: String,
        ): DocumentArgumentRelationEvidence =
            DocumentArgumentRelationEvidence(
                fromClaimId = fromClaimId,
                toClaimId = toClaimId,
                kind = kind,
                evidenceFingerprint = evidenceFingerprint,
                fingerprint = relationEvidenceFingerprint(
                    fromClaimId,
                    toClaimId,
                    kind,
                    evidenceFingerprint,
                ),
            )
    }
}

data class DocumentArgumentStep(
    val ordinal: Int,
    val sectionKey: String,
    val claimId: String,
    val claimFingerprint: String,
    val role: DocumentArgumentStepRole,
    val fingerprint: String,
) {
    init {
        require(ordinal >= 0)
        require(sectionKey.isNotBlank())
        require(claimId.isNotBlank())
        require(claimFingerprint.matches(SHA_256_B433))
        require(
            fingerprint == argumentStepFingerprint(
                ordinal,
                sectionKey,
                claimId,
                claimFingerprint,
                role,
            )
        )
    }

    val proseAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
}

data class DocumentArgumentRelation(
    val fromClaimId: String,
    val toClaimId: String,
    val kind: DocumentArgumentRelationKind,
    val evidenceFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(fromClaimId.isNotBlank())
        require(toClaimId.isNotBlank())
        require(fromClaimId != toClaimId)
        when (kind) {
            DocumentArgumentRelationKind.SEQUENCE ->
                require(evidenceFingerprint == null)
            DocumentArgumentRelationKind.SUPPORTS,
            DocumentArgumentRelationKind.ELABORATES,
            DocumentArgumentRelationKind.CONTRASTS ->
                require(evidenceFingerprint?.matches(SHA_256_B433) == true)
        }
        require(
            fingerprint == argumentRelationFingerprint(
                fromClaimId,
                toClaimId,
                kind,
                evidenceFingerprint,
            )
        )
    }
}

data class DocumentArgumentPlan(
    val documentGoalFingerprint: String,
    val structureFingerprint: String,
    val semanticPlanFingerprint: String,
    val steps: List<DocumentArgumentStep>,
    val relations: List<DocumentArgumentRelation>,
    val fingerprint: String,
) {
    init {
        require(documentGoalFingerprint.matches(SHA_256_B433))
        require(structureFingerprint.matches(SHA_256_B433))
        require(semanticPlanFingerprint.matches(SHA_256_B433))
        require(steps.isNotEmpty())
        require(steps.map { it.ordinal } == steps.indices.toList())
        require(steps.map { it.claimId }.distinct().size == steps.size)
        require(relations == relations.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        val claimIds = steps.map { it.claimId }.toSet()
        require(relations.all { it.fromClaimId in claimIds && it.toClaimId in claimIds })
        require(
            fingerprint == argumentPlanFingerprint(
                documentGoalFingerprint,
                structureFingerprint,
                semanticPlanFingerprint,
                steps,
                relations,
            )
        )
    }

    val factualRelationCreationAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val proseAuthority: Boolean get() = false
    val artifactFinalizationAuthority: Boolean get() = false
}

/**
 * B433 creates an argument/narrative order over the exact B432 claim structure.
 *
 * Adjacent claims receive presentation-only SEQUENCE relations. SUPPORTS, ELABORATES and
 * CONTRASTS may appear only when exact external relation evidence is supplied. This keeps
 * rhetorical planning from silently inventing factual relationships.
 */
class DocumentArgumentPlanner {
    fun plan(
        goal: DocumentGoal,
        structure: DocumentStructurePlan,
        semanticPlan: SemanticArtifactPlan,
        relationEvidence: Collection<DocumentArgumentRelationEvidence> = emptyList(),
    ): DocumentArgumentPlan {
        require(goal.fingerprint == structure.documentGoalFingerprint)
        require(goal.semanticPlanFingerprint == semanticPlan.fingerprint)
        require(structure.semanticPlanFingerprint == semanticPlan.fingerprint)
        require(structure.coveredClaimIds == goal.requiredClaimIds.sorted())

        val claimsById = semanticPlan.resolvedClaims().associateBy { it.claimId }
        require(goal.requiredClaimIds.all { it in claimsById })

        val orderedPairs = structure.sections
            .sortedBy { it.ordinal }
            .flatMap { section ->
                section.claimIds.sorted().map { section to it }
            }

        val steps = orderedPairs.mapIndexed { index, (section, claimId) ->
            val claim = claimsById.getValue(claimId)
            val role = when (section.role) {
                DocumentSectionRole.OPENING -> DocumentArgumentStepRole.INTRODUCE
                DocumentSectionRole.CLOSING,
                DocumentSectionRole.SYNTHESIS -> DocumentArgumentStepRole.SYNTHESIZE
                DocumentSectionRole.DEVELOPMENT -> DocumentArgumentStepRole.DEVELOP
            }
            DocumentArgumentStep(
                ordinal = index,
                sectionKey = section.sectionKey,
                claimId = claimId,
                claimFingerprint = claim.fingerprint,
                role = role,
                fingerprint = argumentStepFingerprint(
                    index,
                    section.sectionKey,
                    claimId,
                    claim.fingerprint,
                    role,
                ),
            )
        }

        val sequenceRelations = steps.zipWithNext().map { (from, to) ->
            DocumentArgumentRelation(
                fromClaimId = from.claimId,
                toClaimId = to.claimId,
                kind = DocumentArgumentRelationKind.SEQUENCE,
                evidenceFingerprint = null,
                fingerprint = argumentRelationFingerprint(
                    from.claimId,
                    to.claimId,
                    DocumentArgumentRelationKind.SEQUENCE,
                    null,
                ),
            )
        }

        val canonicalEvidence = relationEvidence
            .groupBy { it.fingerprint }
            .map { (_, same) ->
                require(same.all { it == same.first() }) {
                    "Conflicting B433 relation evidence identity"
                }
                same.first()
            }
            .sortedBy { it.fingerprint }

        val allowed = goal.requiredClaimIds.toSet()
        require(canonicalEvidence.all {
            it.fromClaimId in allowed && it.toClaimId in allowed
        }) {
            "B433 relation evidence references a foreign claim"
        }

        val evidencedRelations = canonicalEvidence.map { evidence ->
            DocumentArgumentRelation(
                fromClaimId = evidence.fromClaimId,
                toClaimId = evidence.toClaimId,
                kind = evidence.kind,
                evidenceFingerprint = evidence.evidenceFingerprint,
                fingerprint = argumentRelationFingerprint(
                    evidence.fromClaimId,
                    evidence.toClaimId,
                    evidence.kind,
                    evidence.evidenceFingerprint,
                ),
            )
        }

        val relations = (sequenceRelations + evidencedRelations)
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }

        return DocumentArgumentPlan(
            documentGoalFingerprint = goal.fingerprint,
            structureFingerprint = structure.fingerprint,
            semanticPlanFingerprint = semanticPlan.fingerprint,
            steps = steps,
            relations = relations,
            fingerprint = argumentPlanFingerprint(
                goal.fingerprint,
                structure.fingerprint,
                semanticPlan.fingerprint,
                steps,
                relations,
            ),
        )
    }
}

private fun relationEvidenceFingerprint(
    fromClaimId: String,
    toClaimId: String,
    kind: DocumentArgumentRelationKind,
    evidenceFingerprint: String,
): String = StableCognitiveIds.fingerprint(
    "document-argument-relation-evidence/v1",
    fromClaimId,
    toClaimId,
    kind.name,
    evidenceFingerprint,
)

private fun argumentStepFingerprint(
    ordinal: Int,
    sectionKey: String,
    claimId: String,
    claimFingerprint: String,
    role: DocumentArgumentStepRole,
): String = StableCognitiveIds.fingerprint(
    "document-argument-step/v1",
    ordinal.toString(),
    sectionKey,
    claimId,
    claimFingerprint,
    role.name,
)

private fun argumentRelationFingerprint(
    fromClaimId: String,
    toClaimId: String,
    kind: DocumentArgumentRelationKind,
    evidenceFingerprint: String?,
): String = StableCognitiveIds.fingerprint(
    "document-argument-relation/v1",
    fromClaimId,
    toClaimId,
    kind.name,
    evidenceFingerprint.orEmpty(),
)

private fun argumentPlanFingerprint(
    documentGoalFingerprint: String,
    structureFingerprint: String,
    semanticPlanFingerprint: String,
    steps: List<DocumentArgumentStep>,
    relations: List<DocumentArgumentRelation>,
): String = StableCognitiveIds.fingerprint(
    "document-argument-plan/v1",
    documentGoalFingerprint,
    structureFingerprint,
    semanticPlanFingerprint,
    *steps.map { it.fingerprint }.toTypedArray(),
    *relations.map { it.fingerprint }.toTypedArray(),
)

private val SHA_256_B433 = Regex("[0-9a-f]{64}")
