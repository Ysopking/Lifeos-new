package app.lifeos.core.runtime.informationasset

import java.time.Instant

enum class InformationAssetValidationSeverity {
    INFO,
    WARNING,
    ERROR,
    BLOCKING,
}

enum class InformationAssetValidationCode {
    INTEGRITY_FAILURE,
    MISSING_REQUIRED_SEMANTIC_KEY,
    UNKNOWN_SOURCE_PHOTON_STATE,
    UNSUPPORTED_NON_ASSUMPTION_CLAIM,
    CONVERGED_WITH_UNRESOLVED_CLAIM,
    CONVERGED_WITH_OPEN_CONFLICT,
    EXPIRED_EVIDENCE,
    PRIMARY_DOMAIN_DROPPED,
    REPRESENTED_DOMAIN_DROPPED,
    DOMAIN_PROFILE_REQUIREMENT,
    PROFILE_FAILURE,
}

data class InformationAssetValidationViolation(
    val code: InformationAssetValidationCode,
    val severity: InformationAssetValidationSeverity,
    val path: String,
    val message: String,
) {
    init {
        require(path.isNotBlank()) { "Validation violation path must not be blank" }
        require(message.isNotBlank()) { "Validation violation message must not be blank" }
    }
}

data class InformationAssetValidationReport(
    val revisionId: InformationAssetRevisionId,
    val evaluatedAt: Instant,
    val violations: List<InformationAssetValidationViolation>,
) {
    val isValid: Boolean
        get() = violations.none {
            it.severity == InformationAssetValidationSeverity.ERROR ||
                it.severity == InformationAssetValidationSeverity.BLOCKING
        }

    val hasBlockingViolation: Boolean
        get() = violations.any { it.severity == InformationAssetValidationSeverity.BLOCKING }
}

/**
 * Domain-specific validators extend semantic validation without replacing the common integrity contract.
 * Implementations must be deterministic for the same revision and evaluation instant.
 */
interface InformationAssetValidationProfile {
    val id: String

    fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): List<InformationAssetValidationViolation>
}

/**
 * Upstream semantic validator for InformationAssets. This is deliberately separate from the
 * downstream collaborative ArtifactValidator: it validates the truth-bearing semantic bundle
 * before any document/report/code/image materialization occurs.
 */
class InformationAssetValidator(
    private val profiles: Map<InformationAssetKind, List<InformationAssetValidationProfile>> = emptyMap(),
) {
    fun validate(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
    ): InformationAssetValidationReport {
        val violations = mutableListOf<InformationAssetValidationViolation>()

        runCatching { InformationAssetRevisionIntegrity.requireValid(revision) }
            .exceptionOrNull()
            ?.let { error ->
                violations += violation(
                    InformationAssetValidationCode.INTEGRITY_FAILURE,
                    InformationAssetValidationSeverity.BLOCKING,
                    "manifest",
                    error.message ?: "InformationAsset integrity validation failed",
                )
            }

        validateSemanticRequirements(revision, violations)
        validateSources(revision, evaluatedAt, violations)
        validateResolution(revision, violations)
        validateDomains(revision, violations)
        validateProfiles(revision, evaluatedAt, violations)

        return InformationAssetValidationReport(
            revisionId = revision.manifest.id,
            evaluatedAt = evaluatedAt,
            violations = violations.sortedWith(
                compareByDescending<InformationAssetValidationViolation> { it.severity.ordinal }
                    .thenBy { it.code.name }
                    .thenBy { it.path }
                    .thenBy { it.message }
            ),
        )
    }

    private fun validateSemanticRequirements(
        revision: InformationAssetRevision,
        violations: MutableList<InformationAssetValidationViolation>,
    ) {
        val presentKeys = revision.claims
            .asSequence()
            .filter { it.state != InformationClaimState.REJECTED }
            .map { it.semanticKey }
            .toSet()

        (revision.request.requiredSemanticKeys - presentKeys)
            .sorted()
            .forEach { key ->
                violations += violation(
                    InformationAssetValidationCode.MISSING_REQUIRED_SEMANTIC_KEY,
                    InformationAssetValidationSeverity.BLOCKING,
                    "claims[$key]",
                    "Required semantic key is not represented by a non-rejected claim",
                )
            }

        revision.claims.forEach { claim ->
            if (claim.state != InformationClaimState.ASSUMPTION && claim.evidenceBindingIds.isEmpty()) {
                violations += violation(
                    InformationAssetValidationCode.UNSUPPORTED_NON_ASSUMPTION_CLAIM,
                    InformationAssetValidationSeverity.BLOCKING,
                    "claims[${claim.id.value}]",
                    "Only explicit assumptions may exist without evidence",
                )
            }
        }
    }

    private fun validateSources(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
        violations: MutableList<InformationAssetValidationViolation>,
    ) {
        val sourceFingerprints = revision.manifest.sourcePhotons
            .mapTo(mutableSetOf()) { it.fingerprint() }

        revision.evidenceBindings.forEach { binding ->
            if (binding.source.fingerprint() !in sourceFingerprints) {
                violations += violation(
                    InformationAssetValidationCode.UNKNOWN_SOURCE_PHOTON_STATE,
                    InformationAssetValidationSeverity.BLOCKING,
                    "evidence[${binding.id.value}].source",
                    "Evidence binding references a Photon state not retained by the manifest",
                )
            }
            if (!binding.validity.contains(evaluatedAt)) {
                violations += violation(
                    InformationAssetValidationCode.EXPIRED_EVIDENCE,
                    InformationAssetValidationSeverity.WARNING,
                    "evidence[${binding.id.value}].validity",
                    "Evidence is outside its declared temporal validity at evaluation time",
                )
            }
        }
    }

    private fun validateResolution(
        revision: InformationAssetRevision,
        violations: MutableList<InformationAssetValidationViolation>,
    ) {
        if (revision.manifest.resolution != InformationAssetResolutionState.CONVERGED) return

        revision.claims
            .filter { it.state == InformationClaimState.UNRESOLVED }
            .forEach { claim ->
                violations += violation(
                    InformationAssetValidationCode.CONVERGED_WITH_UNRESOLVED_CLAIM,
                    InformationAssetValidationSeverity.BLOCKING,
                    "claims[${claim.id.value}]",
                    "A converged InformationAsset cannot contain an unresolved claim",
                )
            }

        revision.conflicts
            .filter { it.state == InformationConflictResolutionState.OPEN }
            .forEach { conflict ->
                violations += violation(
                    InformationAssetValidationCode.CONVERGED_WITH_OPEN_CONFLICT,
                    InformationAssetValidationSeverity.BLOCKING,
                    "conflicts[${conflict.id.value}]",
                    "A converged InformationAsset cannot contain an open conflict",
                )
            }
    }

    private fun validateDomains(
        revision: InformationAssetRevision,
        violations: MutableList<InformationAssetValidationViolation>,
    ) {
        if (revision.request.primaryDomainId !in revision.manifest.domainIds) {
            violations += violation(
                InformationAssetValidationCode.PRIMARY_DOMAIN_DROPPED,
                InformationAssetValidationSeverity.BLOCKING,
                "manifest.domainIds",
                "Manifest dropped the request primary domain",
            )
        }

        val representedDomains = buildSet {
            add(revision.request.primaryDomainId)
            revision.evidenceBindings.forEach { add(it.domainId) }
            revision.claims.forEach { add(it.domainId) }
            revision.conflicts.forEach { add(it.domainId) }
        }
        if (!revision.manifest.domainIds.containsAll(representedDomains)) {
            violations += violation(
                InformationAssetValidationCode.REPRESENTED_DOMAIN_DROPPED,
                InformationAssetValidationSeverity.BLOCKING,
                "manifest.domainIds",
                "Manifest dropped one or more represented domains",
            )
        }
    }

    private fun validateProfiles(
        revision: InformationAssetRevision,
        evaluatedAt: Instant,
        violations: MutableList<InformationAssetValidationViolation>,
    ) {
        profiles[revision.request.kind].orEmpty()
            .sortedBy { it.id }
            .forEach { profile ->
                runCatching { profile.validate(revision, evaluatedAt) }
                    .onSuccess { violations += it }
                    .onFailure { error ->
                        violations += violation(
                            InformationAssetValidationCode.PROFILE_FAILURE,
                            InformationAssetValidationSeverity.BLOCKING,
                            "profiles[${profile.id}]",
                            error.message ?: "Domain validation profile failed",
                        )
                    }
            }
    }

    private fun violation(
        code: InformationAssetValidationCode,
        severity: InformationAssetValidationSeverity,
        path: String,
        message: String,
    ): InformationAssetValidationViolation = InformationAssetValidationViolation(
        code = code,
        severity = severity,
        path = path,
        message = message,
    )
}
