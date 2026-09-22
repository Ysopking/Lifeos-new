package app.lifeos.core.model

enum class DocumentPurpose {
    INFORM,
    EXPLAIN,
    SUMMARIZE,
    INSTRUCT,
    ANALYZE,
    RECORD,
}

enum class DocumentOutputFormat {
    PLAIN_TEXT,
    MARKDOWN,
    PDF,
    DOCX,
    HTML,
}

enum class DocumentDepth {
    BRIEF,
    STANDARD,
    DEEP,
    EXHAUSTIVE,
}

data class DocumentGoal(
    val semanticPlanFingerprint: String,
    val sourceWorldRevision: Long,
    val purpose: DocumentPurpose,
    val audience: String,
    val languageTag: String,
    val outputFormat: DocumentOutputFormat,
    val depth: DocumentDepth,
    val requiredClaimIds: List<String>,
    val constraints: List<String>,
    val fingerprint: String,
) {
    init {
        require(semanticPlanFingerprint.matches(SHA_256_B431))
        require(sourceWorldRevision >= 0L)
        require(audience.isNotBlank() && audience.length <= MAX_AUDIENCE_CHARS)
        require(languageTag.matches(LANGUAGE_TAG_REGEX_B431))
        require(requiredClaimIds.isNotEmpty())
        require(requiredClaimIds == requiredClaimIds.distinct().sorted())
        require(requiredClaimIds.none(String::isBlank))
        require(constraints == constraints.distinct().sorted())
        require(constraints.none(String::isBlank))
        require(constraints.size <= MAX_DOCUMENT_CONSTRAINTS)
        require(
            fingerprint == documentGoalFingerprint(
                semanticPlanFingerprint,
                sourceWorldRevision,
                purpose,
                audience,
                languageTag,
                outputFormat,
                depth,
                requiredClaimIds,
                constraints,
            )
        )
    }

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
    val artifactFinalizationAuthority: Boolean get() = false

    companion object {
        fun create(
            semanticPlan: SemanticArtifactPlan,
            purpose: DocumentPurpose,
            audience: String,
            languageTag: String,
            outputFormat: DocumentOutputFormat,
            depth: DocumentDepth,
            requiredClaimIds: Collection<String> =
                semanticPlan.resolvedClaims().map { it.claimId },
            constraints: Collection<String> = emptyList(),
        ): DocumentGoal {
            require(
                semanticPlan.kind == SemanticArtifactKind.TEXT ||
                    semanticPlan.kind == SemanticArtifactKind.PDF
            ) {
                "B431 DocumentGoal requires a textual semantic artifact plan"
            }
            val resolved = semanticPlan.resolvedClaims().map { it.claimId }.toSet()
            val requested = requiredClaimIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            require(requested.isNotEmpty()) {
                "B431 requires at least one resolved claim"
            }
            require(requested.all { it in resolved }) {
                "B431 cannot require unresolved or foreign claims"
            }
            val canonicalConstraints = constraints
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            val audienceValue = audience.trim()
            val languageValue = languageTag.trim().lowercase()
            return DocumentGoal(
                semanticPlanFingerprint = semanticPlan.fingerprint,
                sourceWorldRevision = semanticPlan.sourceWorldRevision,
                purpose = purpose,
                audience = audienceValue,
                languageTag = languageValue,
                outputFormat = outputFormat,
                depth = depth,
                requiredClaimIds = requested,
                constraints = canonicalConstraints,
                fingerprint = documentGoalFingerprint(
                    semanticPlan.fingerprint,
                    semanticPlan.sourceWorldRevision,
                    purpose,
                    audienceValue,
                    languageValue,
                    outputFormat,
                    depth,
                    requested,
                    canonicalConstraints,
                ),
            )
        }
    }
}

private fun documentGoalFingerprint(
    semanticPlanFingerprint: String,
    sourceWorldRevision: Long,
    purpose: DocumentPurpose,
    audience: String,
    languageTag: String,
    outputFormat: DocumentOutputFormat,
    depth: DocumentDepth,
    requiredClaimIds: List<String>,
    constraints: List<String>,
): String = StableCognitiveIds.fingerprint(
    "document-goal/v1",
    semanticPlanFingerprint,
    sourceWorldRevision.toString(),
    purpose.name,
    audience,
    languageTag,
    outputFormat.name,
    depth.name,
    requiredClaimIds.joinToString("\u001f"),
    constraints.joinToString("\u001f"),
)

private val SHA_256_B431 = Regex("[0-9a-f]{64}")
private val LANGUAGE_TAG_REGEX_B431 =
    Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3}")
private const val MAX_AUDIENCE_CHARS = 240
private const val MAX_DOCUMENT_CONSTRAINTS = 32
