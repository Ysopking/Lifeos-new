package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.StableCognitiveIds

enum class FactualDraftIssueKind {
    UNRESOLVED_CLAIM,
    MISSING_SENTENCE,
    CLAIM_FINGERPRINT_MISMATCH,
    EVIDENCE_MISMATCH,
    MISSING_CITATION_BINDING,
    PARAGRAPH_TEXT_MISMATCH,
    COHERENT_SOURCE_TEXT_MISMATCH,
    FOREIGN_CLAIM,
}

data class FactualDraftIssue(
    val kind: FactualDraftIssueKind,
    val claimId: String?,
    val detail: String,
    val fingerprint: String,
) {
    init {
        require(claimId == null || claimId.isNotBlank())
        require(detail.isNotBlank())
        require(
            fingerprint == factualIssueFingerprint(
                kind,
                claimId,
                detail,
            )
        )
    }
}

data class FactualDraftValidationReport(
    val semanticPlanFingerprint: String,
    val argumentPlanFingerprint: String,
    val coherentDraftFingerprint: String,
    val citationPlanFingerprint: String,
    val validatedClaimIds: List<String>,
    val issues: List<FactualDraftIssue>,
    val passed: Boolean,
    val fingerprint: String,
) {
    init {
        require(semanticPlanFingerprint.matches(SHA_256_B438))
        require(argumentPlanFingerprint.matches(SHA_256_B438))
        require(coherentDraftFingerprint.matches(SHA_256_B438))
        require(citationPlanFingerprint.matches(SHA_256_B438))
        require(validatedClaimIds == validatedClaimIds.distinct().sorted())
        require(issues == issues.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(passed == issues.isEmpty())
        require(
            fingerprint == factualReportFingerprint(
                semanticPlanFingerprint,
                argumentPlanFingerprint,
                coherentDraftFingerprint,
                citationPlanFingerprint,
                validatedClaimIds,
                issues,
                passed,
            )
        )
    }

    val factualPromotionAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
}

/**
 * B438 validates that all factual text in the B436 draft is still an exact realization of resolved
 * SemanticArtifactClaims and that every claim has exact B437 evidence binding.
 *
 * It does not judge external truth. It verifies lineage/closure: no foreign or unresolved claim,
 * no sentence/paragraph substitution, no evidence drift and no uncited factual carrier.
 */
class FactualDraftValidator {
    fun validate(
        semanticPlan: SemanticArtifactPlan,
        argumentPlan: DocumentArgumentPlan,
        sentences: Collection<ClaimSentence>,
        paragraphs: ParagraphComposition,
        coherentDraft: CoherentDocumentDraft,
        citations: CitationBindingPlan,
    ): FactualDraftValidationReport {
        val issues = mutableListOf<FactualDraftIssue>()

        if (argumentPlan.semanticPlanFingerprint != semanticPlan.fingerprint) {
            issues += issue(
                FactualDraftIssueKind.FOREIGN_CLAIM,
                null,
                "argument-plan-semantic-plan-mismatch",
            )
        }
        if (paragraphs.argumentPlanFingerprint != argumentPlan.fingerprint) {
            issues += issue(
                FactualDraftIssueKind.PARAGRAPH_TEXT_MISMATCH,
                null,
                "paragraph-argument-plan-mismatch",
            )
        }
        if (
            coherentDraft.argumentPlanFingerprint != argumentPlan.fingerprint ||
            coherentDraft.paragraphCompositionFingerprint != paragraphs.fingerprint
        ) {
            issues += issue(
                FactualDraftIssueKind.COHERENT_SOURCE_TEXT_MISMATCH,
                null,
                "coherent-draft-lineage-mismatch",
            )
        }
        if (
            citations.semanticPlanFingerprint != semanticPlan.fingerprint ||
            citations.argumentPlanFingerprint != argumentPlan.fingerprint ||
            citations.coherentDraftFingerprint != coherentDraft.fingerprint
        ) {
            issues += issue(
                FactualDraftIssueKind.MISSING_CITATION_BINDING,
                null,
                "citation-plan-lineage-mismatch",
            )
        }

        val sentenceByStep = sentences.groupBy { it.argumentStepFingerprint }
        val citationByClaim = citations.bindings.associateBy { it.claimId }
        val paragraphBySentence = buildMap<String, DocumentParagraph> {
            paragraphs.paragraphs.forEach { paragraph ->
                paragraph.sentenceFingerprints.forEach { sentenceFingerprint ->
                    put(sentenceFingerprint, paragraph)
                }
            }
        }
        val coherentByParagraph =
            coherentDraft.paragraphs.associateBy { it.paragraphFingerprint }

        val validated = mutableListOf<String>()
        argumentPlan.steps.forEach { step ->
            val claim = semanticPlan.claim(step.claimId)
            if (claim == null) {
                issues += issue(
                    FactualDraftIssueKind.FOREIGN_CLAIM,
                    step.claimId,
                    "claim-not-in-semantic-plan",
                )
                return@forEach
            }
            if (step.claimId in semanticPlan.unresolvedClaimIds) {
                issues += issue(
                    FactualDraftIssueKind.UNRESOLVED_CLAIM,
                    step.claimId,
                    "claim-is-unresolved",
                )
                return@forEach
            }
            if (step.claimFingerprint != claim.fingerprint) {
                issues += issue(
                    FactualDraftIssueKind.CLAIM_FINGERPRINT_MISMATCH,
                    step.claimId,
                    "argument-step-claim-fingerprint-mismatch",
                )
                return@forEach
            }

            val matchingSentences = sentenceByStep[step.fingerprint].orEmpty()
            if (matchingSentences.size != 1) {
                issues += issue(
                    FactualDraftIssueKind.MISSING_SENTENCE,
                    step.claimId,
                    "expected-exactly-one-sentence",
                )
                return@forEach
            }
            val sentence = matchingSentences.single()
            if (
                sentence.claimId != claim.claimId ||
                sentence.claimFingerprint != claim.fingerprint
            ) {
                issues += issue(
                    FactualDraftIssueKind.CLAIM_FINGERPRINT_MISMATCH,
                    step.claimId,
                    "sentence-claim-binding-mismatch",
                )
                return@forEach
            }
            val evidence = claim.evidence.map { it.stableKey }.distinct().sorted()
            if (sentence.evidenceStableKeys != evidence) {
                issues += issue(
                    FactualDraftIssueKind.EVIDENCE_MISMATCH,
                    step.claimId,
                    "sentence-evidence-mismatch",
                )
                return@forEach
            }

            val citation = citationByClaim[step.claimId]
            if (
                citation == null ||
                citation.claimFingerprint != claim.fingerprint ||
                citation.sentenceFingerprint != sentence.fingerprint ||
                citation.evidenceStableKeys != evidence
            ) {
                issues += issue(
                    FactualDraftIssueKind.MISSING_CITATION_BINDING,
                    step.claimId,
                    "exact-citation-binding-missing",
                )
                return@forEach
            }

            val paragraph = paragraphBySentence[sentence.fingerprint]
            if (
                paragraph == null ||
                citation.paragraphFingerprint != paragraph.fingerprint
            ) {
                issues += issue(
                    FactualDraftIssueKind.PARAGRAPH_TEXT_MISMATCH,
                    step.claimId,
                    "sentence-paragraph-binding-mismatch",
                )
                return@forEach
            }

            val expectedParagraphText = paragraph.sentenceFingerprints.map { fp ->
                sentences.singleOrNull { it.fingerprint == fp }?.text
            }
            if (
                expectedParagraphText.any { it == null } ||
                paragraph.text != expectedParagraphText.filterNotNull().joinToString(" ")
            ) {
                issues += issue(
                    FactualDraftIssueKind.PARAGRAPH_TEXT_MISMATCH,
                    step.claimId,
                    "paragraph-text-not-lossless-sentence-composition",
                )
                return@forEach
            }

            val coherent = coherentByParagraph[paragraph.fingerprint]
            if (coherent == null || coherent.sourceText != paragraph.text) {
                issues += issue(
                    FactualDraftIssueKind.COHERENT_SOURCE_TEXT_MISMATCH,
                    step.claimId,
                    "coherent-source-text-differs-from-paragraph",
                )
                return@forEach
            }

            validated += step.claimId
        }

        val canonicalIssues = issues
            .distinctBy { it.fingerprint }
            .sortedBy { it.fingerprint }
        val validatedClaims = validated.distinct().sorted()
        val passed = canonicalIssues.isEmpty() &&
            validatedClaims == argumentPlan.steps.map { it.claimId }.distinct().sorted()

        val finalIssues = if (
            !passed &&
            canonicalIssues.isEmpty()
        ) {
            listOf(
                issue(
                    FactualDraftIssueKind.FOREIGN_CLAIM,
                    null,
                    "claim-coverage-incomplete",
                )
            )
        } else {
            canonicalIssues
        }

        val finalPassed = finalIssues.isEmpty()
        return FactualDraftValidationReport(
            semanticPlanFingerprint = semanticPlan.fingerprint,
            argumentPlanFingerprint = argumentPlan.fingerprint,
            coherentDraftFingerprint = coherentDraft.fingerprint,
            citationPlanFingerprint = citations.fingerprint,
            validatedClaimIds = validatedClaims,
            issues = finalIssues.sortedBy { it.fingerprint },
            passed = finalPassed,
            fingerprint = factualReportFingerprint(
                semanticPlan.fingerprint,
                argumentPlan.fingerprint,
                coherentDraft.fingerprint,
                citations.fingerprint,
                validatedClaims,
                finalIssues.sortedBy { it.fingerprint },
                finalPassed,
            ),
        )
    }

    private fun issue(
        kind: FactualDraftIssueKind,
        claimId: String?,
        detail: String,
    ): FactualDraftIssue =
        FactualDraftIssue(
            kind = kind,
            claimId = claimId,
            detail = detail,
            fingerprint = factualIssueFingerprint(
                kind,
                claimId,
                detail,
            ),
        )
}

private fun factualIssueFingerprint(
    kind: FactualDraftIssueKind,
    claimId: String?,
    detail: String,
): String = StableCognitiveIds.fingerprint(
    "factual-draft-issue/v1",
    kind.name,
    claimId.orEmpty(),
    detail,
)

private fun factualReportFingerprint(
    semanticPlanFingerprint: String,
    argumentPlanFingerprint: String,
    coherentDraftFingerprint: String,
    citationPlanFingerprint: String,
    validatedClaimIds: List<String>,
    issues: List<FactualDraftIssue>,
    passed: Boolean,
): String = StableCognitiveIds.fingerprint(
    "factual-draft-validation-report/v1",
    semanticPlanFingerprint,
    argumentPlanFingerprint,
    coherentDraftFingerprint,
    citationPlanFingerprint,
    passed.toString(),
    validatedClaimIds.joinToString("\u001f"),
    *issues.map { it.fingerprint }.toTypedArray(),
)

private val SHA_256_B438 = Regex("[0-9a-f]{64}")
