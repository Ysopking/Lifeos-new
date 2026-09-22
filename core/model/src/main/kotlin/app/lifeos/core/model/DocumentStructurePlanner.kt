package app.lifeos.core.model

enum class DocumentSectionRole {
    OPENING,
    DEVELOPMENT,
    SYNTHESIS,
    CLOSING,
}

data class DocumentSectionPlan(
    val sectionKey: String,
    val ordinal: Int,
    val role: DocumentSectionRole,
    val claimIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(sectionKey.isNotBlank())
        require(ordinal >= 0)
        require(claimIds.isNotEmpty())
        require(claimIds == claimIds.distinct().sorted())
        require(
            fingerprint == documentSectionFingerprint(
                sectionKey,
                ordinal,
                role,
                claimIds,
            )
        )
    }

    val proseAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
}

data class DocumentStructurePlan(
    val documentGoalFingerprint: String,
    val semanticPlanFingerprint: String,
    val sections: List<DocumentSectionPlan>,
    val coveredClaimIds: List<String>,
    val fingerprint: String,
) {
    init {
        require(documentGoalFingerprint.matches(SHA_256_B432))
        require(semanticPlanFingerprint.matches(SHA_256_B432))
        require(sections.isNotEmpty())
        require(sections.map { it.ordinal } == sections.indices.toList())
        require(sections.map { it.sectionKey }.distinct().size == sections.size)
        require(
            sections.flatMap { it.claimIds }.size ==
                sections.flatMap { it.claimIds }.distinct().size
        ) {
            "B432 assigns each required claim to exactly one structural section"
        }
        require(
            coveredClaimIds ==
                sections.flatMap { it.claimIds }.distinct().sorted()
        )
        require(
            fingerprint == documentStructureFingerprint(
                documentGoalFingerprint,
                semanticPlanFingerprint,
                sections,
                coveredClaimIds,
            )
        )
    }

    val proseAuthority: Boolean get() = false
    val argumentAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val artifactFinalizationAuthority: Boolean get() = false
}

class DocumentStructurePlanner {
    fun plan(
        goal: DocumentGoal,
        semanticPlan: SemanticArtifactPlan,
    ): DocumentStructurePlan {
        require(goal.semanticPlanFingerprint == semanticPlan.fingerprint) {
            "B432 semantic plan does not match B431 DocumentGoal"
        }
        require(goal.sourceWorldRevision == semanticPlan.sourceWorldRevision)
        val resolved = semanticPlan.resolvedClaims().map { it.claimId }.toSet()
        require(goal.requiredClaimIds.all { it in resolved })

        val maxClaimsPerSection = when (goal.depth) {
            DocumentDepth.BRIEF -> 8
            DocumentDepth.STANDARD -> 4
            DocumentDepth.DEEP -> 2
            DocumentDepth.EXHAUSTIVE -> 1
        }

        val chunks = goal.requiredClaimIds
            .sorted()
            .chunked(maxClaimsPerSection)

        val sections = chunks.mapIndexed { index, claims ->
            val role = when {
                chunks.size == 1 -> DocumentSectionRole.SYNTHESIS
                index == 0 -> DocumentSectionRole.OPENING
                index == chunks.lastIndex -> DocumentSectionRole.CLOSING
                else -> DocumentSectionRole.DEVELOPMENT
            }
            val key = "section-" + (index + 1)
            DocumentSectionPlan(
                sectionKey = key,
                ordinal = index,
                role = role,
                claimIds = claims.sorted(),
                fingerprint = documentSectionFingerprint(
                    key,
                    index,
                    role,
                    claims.sorted(),
                ),
            )
        }
        val covered = sections.flatMap { it.claimIds }.distinct().sorted()
        require(covered == goal.requiredClaimIds.sorted()) {
            "B432 must cover the exact B431 required claim set"
        }

        return DocumentStructurePlan(
            documentGoalFingerprint = goal.fingerprint,
            semanticPlanFingerprint = semanticPlan.fingerprint,
            sections = sections,
            coveredClaimIds = covered,
            fingerprint = documentStructureFingerprint(
                goal.fingerprint,
                semanticPlan.fingerprint,
                sections,
                covered,
            ),
        )
    }
}

private fun documentSectionFingerprint(
    sectionKey: String,
    ordinal: Int,
    role: DocumentSectionRole,
    claimIds: List<String>,
): String = StableCognitiveIds.fingerprint(
    "document-section-plan/v1",
    sectionKey,
    ordinal.toString(),
    role.name,
    claimIds.joinToString("\u001f"),
)

private fun documentStructureFingerprint(
    documentGoalFingerprint: String,
    semanticPlanFingerprint: String,
    sections: List<DocumentSectionPlan>,
    coveredClaimIds: List<String>,
): String = StableCognitiveIds.fingerprint(
    "document-structure-plan/v1",
    documentGoalFingerprint,
    semanticPlanFingerprint,
    *sections.map { it.fingerprint }.toTypedArray(),
    coveredClaimIds.joinToString("\u001f"),
)

private val SHA_256_B432 = Regex("[0-9a-f]{64}")
