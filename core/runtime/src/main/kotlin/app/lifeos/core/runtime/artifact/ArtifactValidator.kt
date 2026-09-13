package app.lifeos.core.runtime.artifact

import java.time.Instant

class ArtifactValidator(
    private val minimumDistinctModules: Int = 2,
) {
    init {
        require(minimumDistinctModules > 0) { "Minimum artifact module count must be positive" }
    }

    val profile: ArtifactValidationProfile
        get() = ArtifactValidationProfile(
            minimumDistinctModules = minimumDistinctModules,
        )

    fun validate(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
    ): ArtifactValidationResult {
        val issues = mutableListOf<ArtifactValidationIssue>()

        if (contributions.isEmpty()) {
            issues += ArtifactValidationIssue(
                code = ArtifactValidationIssueCode.MISSING_CONTRIBUTIONS,
                message = "Collaborative artifact requires at least one contribution",
            )
        }

        val distinctModules = contributions.map { it.module }.toSet()
        if (contributions.isNotEmpty() && distinctModules.size < minimumDistinctModules) {
            issues += ArtifactValidationIssue(
                code = ArtifactValidationIssueCode.INSUFFICIENT_MODULE_DIVERSITY,
                message = "Collaborative artifact requires at least $minimumDistinctModules distinct modules",
            )
        }

        contributions
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .keys
            .sorted()
            .forEach { duplicateId ->
                issues += ArtifactValidationIssue(
                    code = ArtifactValidationIssueCode.DUPLICATE_CONTRIBUTION_ID,
                    message = "Duplicate artifact contribution id: $duplicateId",
                    contributionId = duplicateId,
                )
            }

        val contributedFields = contributions.map { it.field }.toSet()
        request.requiredFields
            .filterNot { it in contributedFields }
            .sorted()
            .forEach { field ->
                issues += ArtifactValidationIssue(
                    code = ArtifactValidationIssueCode.MISSING_REQUIRED_FIELD,
                    message = "Missing required artifact field: $field",
                )
            }

        contributions.forEach { contribution ->
            if (contribution.provenance.source.isBlank() || contribution.provenance.actor.isBlank()) {
                issues += ArtifactValidationIssue(
                    code = ArtifactValidationIssueCode.INVALID_PROVENANCE,
                    message = "Contribution provenance must record source and actor",
                    contributionId = contribution.id,
                )
            }
            if (finalizedAt < contribution.contributedAt) {
                issues += ArtifactValidationIssue(
                    code = ArtifactValidationIssueCode.INVALID_FINALIZATION_TIME,
                    message = "Artifact finalization cannot predate contribution ${contribution.id}",
                    contributionId = contribution.id,
                )
            }
        }

        if (finalizedAt < request.requestedAt) {
            issues += ArtifactValidationIssue(
                code = ArtifactValidationIssueCode.INVALID_FINALIZATION_TIME,
                message = "Artifact finalization cannot predate its request",
            )
        }

        return ArtifactValidationResult(issues = issues.toList())
    }

    fun requireValid(
        request: CollaborativeArtifactRequest,
        contributions: List<ArtifactContribution>,
        finalizedAt: Instant,
    ): ArtifactValidationEvidence {
        val result = validate(request, contributions, finalizedAt)
        require(result.isValid) {
            result.issues.joinToString(separator = "; ") { issue ->
                "${issue.code}: ${issue.message}"
            }
        }
        return ArtifactValidationEvidence(
            profile = profile,
            result = result,
        )
    }
}
