package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentStructurePlan
import app.lifeos.core.model.StableCognitiveIds
import java.util.Locale

enum class DocumentCritiqueDimension {
    FACTUAL_INTEGRITY,
    COMPLETENESS,
    REDUNDANCY,
    STYLE,
    COHERENCE,
}

enum class DocumentCritiqueSeverity {
    WARNING,
    ERROR,
}

enum class DocumentCritiqueStatus {
    PASSED,
    REVISION_RECOMMENDED,
    BLOCKED,
}

data class DocumentCritiqueFinding(
    val dimension: DocumentCritiqueDimension,
    val severity: DocumentCritiqueSeverity,
    val code: String,
    val detail: String,
    val subjects: List<String>,
    val fingerprint: String,
) {
    init {
        require(code.isNotBlank())
        require(detail.isNotBlank())
        require(subjects == subjects.distinct().sorted())
        require(subjects.size <= MAX_CRITIQUE_SUBJECTS_B439)
        require(subjects.none(String::isBlank))
        require(
            fingerprint == critiqueFindingFingerprint(
                dimension,
                severity,
                code,
                detail,
                subjects,
            )
        )
    }

    val rewriteAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
}

data class DocumentCritiqueMetrics(
    val requiredClaimCount: Int,
    val validatedClaimCount: Int,
    val paragraphCount: Int,
    val duplicateParagraphCount: Int,
    val transitionCount: Int,
    val distinctTransitionCount: Int,
    val totalCharacters: Int,
) {
    init {
        require(requiredClaimCount > 0)
        require(validatedClaimCount >= 0)
        require(paragraphCount > 0)
        require(duplicateParagraphCount >= 0)
        require(transitionCount >= 0)
        require(distinctTransitionCount >= 0)
        require(distinctTransitionCount <= transitionCount)
        require(totalCharacters > 0)
    }
}

data class DocumentCritiqueReport(
    val documentGoalFingerprint: String,
    val coherentDraftFingerprint: String,
    val factualValidationFingerprint: String,
    val metrics: DocumentCritiqueMetrics,
    val findings: List<DocumentCritiqueFinding>,
    val status: DocumentCritiqueStatus,
    val fingerprint: String,
) {
    init {
        require(documentGoalFingerprint.matches(SHA_256_B439))
        require(coherentDraftFingerprint.matches(SHA_256_B439))
        require(factualValidationFingerprint.matches(SHA_256_B439))
        require(findings == findings.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(
            status == when {
                findings.any { it.severity == DocumentCritiqueSeverity.ERROR } ->
                    DocumentCritiqueStatus.BLOCKED
                findings.isNotEmpty() ->
                    DocumentCritiqueStatus.REVISION_RECOMMENDED
                else ->
                    DocumentCritiqueStatus.PASSED
            }
        )
        require(
            fingerprint == critiqueReportFingerprint(
                documentGoalFingerprint,
                coherentDraftFingerprint,
                factualValidationFingerprint,
                metrics,
                findings,
                status,
            )
        )
    }

    val rewriteAuthority: Boolean get() = false
    val factualAuthority: Boolean get() = false
    val ownerStyleAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
}

/**
 * B439 evaluates an exact B431-B438 document lineage without rewriting it.
 *
 * The critic checks factual closure, required-claim coverage, exact-stage completeness,
 * deterministic redundancy signals and only explicitly machine-readable style constraints.
 * Natural-language constraints are not silently interpreted as style rules. B441 owns learned
 * owner style; B439 does not infer it.
 */
class DocumentCritic {
    fun critique(
        goal: DocumentGoal,
        structure: DocumentStructurePlan,
        argumentPlan: DocumentArgumentPlan,
        paragraphs: ParagraphComposition,
        coherentDraft: CoherentDocumentDraft,
        citations: CitationBindingPlan,
        factualValidation: FactualDraftValidationReport,
    ): DocumentCritiqueReport {
        require(structure.documentGoalFingerprint == goal.fingerprint)
        require(argumentPlan.documentGoalFingerprint == goal.fingerprint)
        require(argumentPlan.structureFingerprint == structure.fingerprint)
        require(paragraphs.argumentPlanFingerprint == argumentPlan.fingerprint)
        require(paragraphs.structureFingerprint == structure.fingerprint)
        require(coherentDraft.documentGoalFingerprint == goal.fingerprint)
        require(coherentDraft.argumentPlanFingerprint == argumentPlan.fingerprint)
        require(coherentDraft.paragraphCompositionFingerprint == paragraphs.fingerprint)
        require(citations.argumentPlanFingerprint == argumentPlan.fingerprint)
        require(citations.coherentDraftFingerprint == coherentDraft.fingerprint)
        require(factualValidation.argumentPlanFingerprint == argumentPlan.fingerprint)
        require(factualValidation.coherentDraftFingerprint == coherentDraft.fingerprint)
        require(factualValidation.citationPlanFingerprint == citations.fingerprint)

        val findings = mutableListOf<DocumentCritiqueFinding>()
        val requiredClaims = goal.requiredClaimIds.sorted()

        if (!factualValidation.passed) {
            findings += finding(
                DocumentCritiqueDimension.FACTUAL_INTEGRITY,
                DocumentCritiqueSeverity.ERROR,
                "factual-validation-failed",
                "B438 factual closure failed; the draft is not eligible for finalization.",
                factualValidation.issues.map { it.fingerprint },
            )
        }

        checkCoverage(findings, "structure", requiredClaims, structure.coveredClaimIds)
        checkCoverage(
            findings,
            "argument-plan",
            requiredClaims,
            argumentPlan.steps.map { it.claimId }.distinct().sorted(),
        )
        checkCoverage(
            findings,
            "paragraph-composition",
            requiredClaims,
            paragraphs.claimIds.distinct().sorted(),
        )
        checkCoverage(findings, "citation-plan", requiredClaims, citations.coveredClaimIds)
        checkCoverage(
            findings,
            "factual-validation",
            requiredClaims,
            factualValidation.validatedClaimIds,
        )

        val duplicatedParagraphs = coherentDraft.paragraphs
            .groupBy { normalizeProse(it.sourceText) }
            .values
            .filter { group -> group.size > 1 }
        duplicatedParagraphs.forEach { group ->
            findings += finding(
                DocumentCritiqueDimension.REDUNDANCY,
                DocumentCritiqueSeverity.WARNING,
                "duplicate-paragraph-content",
                "Multiple paragraphs carry the same normalized source text.",
                group.map { it.paragraphFingerprint },
            )
        }

        val transitionTexts = coherentDraft.paragraphs
            .mapNotNull { it.transitionText?.let(::normalizeProse) }
        if (transitionTexts.size >= 3 && transitionTexts.distinct().size == 1) {
            findings += finding(
                DocumentCritiqueDimension.STYLE,
                DocumentCritiqueSeverity.WARNING,
                "repeated-transition-pattern",
                "Three or more transitions reuse one identical normalized transition.",
                coherentDraft.paragraphs
                    .filter { it.transitionText != null }
                    .map { it.fingerprint },
            )
        }

        styleConstraints(goal).forEach { constraint ->
            when (constraint) {
                is ParsedStyleConstraint.MaxParagraphChars -> {
                    coherentDraft.paragraphs
                        .filter { it.renderedText.length > constraint.max }
                        .forEach { paragraph ->
                            findings += finding(
                                DocumentCritiqueDimension.STYLE,
                                DocumentCritiqueSeverity.WARNING,
                                "max-paragraph-chars-exceeded",
                                "Paragraph exceeds explicit style:max-paragraph-chars=${constraint.max}.",
                                listOf(paragraph.fingerprint),
                            )
                        }
                }
                is ParsedStyleConstraint.MaxDocumentChars -> {
                    if (coherentDraft.renderedText.length > constraint.max) {
                        findings += finding(
                            DocumentCritiqueDimension.STYLE,
                            DocumentCritiqueSeverity.WARNING,
                            "max-document-chars-exceeded",
                            "Draft exceeds explicit style:max-document-chars=${constraint.max}.",
                            listOf(coherentDraft.fingerprint),
                        )
                    }
                }
                ParsedStyleConstraint.RequireTransitionVariety -> {
                    if (transitionTexts.size >= 2 && transitionTexts.distinct().size < 2) {
                        findings += finding(
                            DocumentCritiqueDimension.STYLE,
                            DocumentCritiqueSeverity.WARNING,
                            "transition-variety-required",
                            "Explicit style:require-transition-variety is not satisfied.",
                            coherentDraft.paragraphs
                                .filter { it.transitionText != null }
                                .map { it.fingerprint },
                        )
                    }
                }
            }
        }

        val compositionFingerprints = paragraphs.paragraphs.map { it.fingerprint }
        val coherentFingerprints = coherentDraft.paragraphs.map { it.paragraphFingerprint }
        if (compositionFingerprints != coherentFingerprints) {
            findings += finding(
                DocumentCritiqueDimension.COHERENCE,
                DocumentCritiqueSeverity.ERROR,
                "paragraph-order-lineage-mismatch",
                "Coherent draft paragraph lineage differs from paragraph composition order.",
                (compositionFingerprints + coherentFingerprints).distinct(),
            )
        }

        val canonicalFindings = findings
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        val status = when {
            canonicalFindings.any { it.severity == DocumentCritiqueSeverity.ERROR } ->
                DocumentCritiqueStatus.BLOCKED
            canonicalFindings.isNotEmpty() ->
                DocumentCritiqueStatus.REVISION_RECOMMENDED
            else ->
                DocumentCritiqueStatus.PASSED
        }
        val duplicateCount = duplicatedParagraphs.sumOf { it.size - 1 }
        val metrics = DocumentCritiqueMetrics(
            requiredClaimCount = requiredClaims.size,
            validatedClaimCount = factualValidation.validatedClaimIds.size,
            paragraphCount = coherentDraft.paragraphs.size,
            duplicateParagraphCount = duplicateCount,
            transitionCount = transitionTexts.size,
            distinctTransitionCount = transitionTexts.distinct().size,
            totalCharacters = coherentDraft.renderedText.length,
        )

        return DocumentCritiqueReport(
            documentGoalFingerprint = goal.fingerprint,
            coherentDraftFingerprint = coherentDraft.fingerprint,
            factualValidationFingerprint = factualValidation.fingerprint,
            metrics = metrics,
            findings = canonicalFindings,
            status = status,
            fingerprint = critiqueReportFingerprint(
                goal.fingerprint,
                coherentDraft.fingerprint,
                factualValidation.fingerprint,
                metrics,
                canonicalFindings,
                status,
            ),
        )
    }

    private fun checkCoverage(
        findings: MutableList<DocumentCritiqueFinding>,
        stage: String,
        expected: List<String>,
        actual: List<String>,
    ) {
        if (expected == actual.sorted()) return
        val actualSet = actual.toSet()
        val expectedSet = expected.toSet()
        val missing = expected.filterNot(actualSet::contains)
        val extra = actual.filterNot(expectedSet::contains)
        findings += finding(
            DocumentCritiqueDimension.COMPLETENESS,
            DocumentCritiqueSeverity.ERROR,
            "claim-coverage-mismatch:$stage",
            "Required claim coverage differs at $stage; missing=${missing.size}, extra=${extra.size}.",
            (missing + extra).distinct(),
        )
    }
}

private sealed interface ParsedStyleConstraint {
    data class MaxParagraphChars(val max: Int) : ParsedStyleConstraint
    data class MaxDocumentChars(val max: Int) : ParsedStyleConstraint
    data object RequireTransitionVariety : ParsedStyleConstraint
}

private fun styleConstraints(goal: DocumentGoal): List<ParsedStyleConstraint> =
    goal.constraints.mapNotNull { raw ->
        when {
            raw.matches(MAX_PARAGRAPH_CONSTRAINT_B439) -> {
                val max = MAX_PARAGRAPH_CONSTRAINT_B439.matchEntire(raw)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                max?.takeIf { it in 1..MAX_STYLE_LIMIT_B439 }
                    ?.let(ParsedStyleConstraint::MaxParagraphChars)
            }
            raw.matches(MAX_DOCUMENT_CONSTRAINT_B439) -> {
                val max = MAX_DOCUMENT_CONSTRAINT_B439.matchEntire(raw)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                max?.takeIf { it in 1..MAX_STYLE_LIMIT_B439 }
                    ?.let(ParsedStyleConstraint::MaxDocumentChars)
            }
            raw == "style:require-transition-variety" ->
                ParsedStyleConstraint.RequireTransitionVariety
            else -> null
        }
    }

private fun finding(
    dimension: DocumentCritiqueDimension,
    severity: DocumentCritiqueSeverity,
    code: String,
    detail: String,
    subjects: Collection<String>,
): DocumentCritiqueFinding {
    val canonicalSubjects = subjects
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .take(MAX_CRITIQUE_SUBJECTS_B439)
    return DocumentCritiqueFinding(
        dimension = dimension,
        severity = severity,
        code = code,
        detail = detail,
        subjects = canonicalSubjects,
        fingerprint = critiqueFindingFingerprint(
            dimension,
            severity,
            code,
            detail,
            canonicalSubjects,
        ),
    )
}

private fun normalizeProse(value: String): String =
    value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private fun critiqueFindingFingerprint(
    dimension: DocumentCritiqueDimension,
    severity: DocumentCritiqueSeverity,
    code: String,
    detail: String,
    subjects: List<String>,
): String = StableCognitiveIds.fingerprint(
    "document-critique-finding/v1",
    dimension.name,
    severity.name,
    code,
    detail,
    subjects.joinToString("\u001f"),
)

private fun critiqueReportFingerprint(
    documentGoalFingerprint: String,
    coherentDraftFingerprint: String,
    factualValidationFingerprint: String,
    metrics: DocumentCritiqueMetrics,
    findings: List<DocumentCritiqueFinding>,
    status: DocumentCritiqueStatus,
): String = StableCognitiveIds.fingerprint(
    "document-critique-report/v1",
    documentGoalFingerprint,
    coherentDraftFingerprint,
    factualValidationFingerprint,
    metrics.requiredClaimCount.toString(),
    metrics.validatedClaimCount.toString(),
    metrics.paragraphCount.toString(),
    metrics.duplicateParagraphCount.toString(),
    metrics.transitionCount.toString(),
    metrics.distinctTransitionCount.toString(),
    metrics.totalCharacters.toString(),
    status.name,
    *findings.map { it.fingerprint }.toTypedArray(),
)

private val SHA_256_B439 = Regex("[0-9a-f]{64}")
private val MAX_PARAGRAPH_CONSTRAINT_B439 = Regex("style:max-paragraph-chars=([0-9]{1,6})")
private val MAX_DOCUMENT_CONSTRAINT_B439 = Regex("style:max-document-chars=([0-9]{1,6})")
private const val MAX_STYLE_LIMIT_B439 = 500_000
private const val MAX_CRITIQUE_SUBJECTS_B439 = 32
