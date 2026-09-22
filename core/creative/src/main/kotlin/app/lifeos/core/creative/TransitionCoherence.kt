package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.DocumentArgumentRelationKind
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.StableCognitiveIds

enum class ParagraphTransitionKind {
    NONE,
    SEQUENCE,
    SUPPORT,
    ELABORATION,
    CONTRAST,
}

data class CoherentParagraph(
    val paragraphFingerprint: String,
    val ordinal: Int,
    val transitionKind: ParagraphTransitionKind,
    val transitionText: String?,
    val sourceText: String,
    val renderedText: String,
    val relationEvidenceFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(paragraphFingerprint.matches(SHA_256_B436))
        require(ordinal >= 0)
        require(sourceText.isNotBlank())
        require(
            relationEvidenceFingerprints ==
                relationEvidenceFingerprints.distinct().sorted()
        )
        require(
            relationEvidenceFingerprints.all { it.matches(SHA_256_B436) }
        )
        if (transitionKind == ParagraphTransitionKind.NONE) {
            require(transitionText == null)
            require(renderedText == sourceText)
        } else {
            require(!transitionText.isNullOrBlank())
            require(renderedText == transitionText + " " + sourceText)
        }
        if (
            transitionKind == ParagraphTransitionKind.SUPPORT ||
            transitionKind == ParagraphTransitionKind.ELABORATION ||
            transitionKind == ParagraphTransitionKind.CONTRAST
        ) {
            require(relationEvidenceFingerprints.isNotEmpty()) {
                "Semantic B436 transitions require exact B433 relation evidence"
            }
        }
        require(
            fingerprint == coherentParagraphFingerprint(
                paragraphFingerprint,
                ordinal,
                transitionKind,
                transitionText,
                sourceText,
                renderedText,
                relationEvidenceFingerprints,
            )
        )
    }

    val sourceParagraphMutationAuthority: Boolean get() = false
    val factualAdditionAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
}

data class CoherentDocumentDraft(
    val documentGoalFingerprint: String,
    val argumentPlanFingerprint: String,
    val paragraphCompositionFingerprint: String,
    val paragraphs: List<CoherentParagraph>,
    val renderedText: String,
    val fingerprint: String,
) {
    init {
        require(documentGoalFingerprint.matches(SHA_256_B436))
        require(argumentPlanFingerprint.matches(SHA_256_B436))
        require(paragraphCompositionFingerprint.matches(SHA_256_B436))
        require(paragraphs.isNotEmpty())
        require(paragraphs.map { it.ordinal } == paragraphs.indices.toList())
        require(renderedText == paragraphs.joinToString("\n\n") { it.renderedText })
        require(
            fingerprint == coherentDraftFingerprint(
                documentGoalFingerprint,
                argumentPlanFingerprint,
                paragraphCompositionFingerprint,
                paragraphs,
                renderedText,
            )
        )
    }

    val factualAdditionAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
}

/**
 * B436 adds presentation-only transitions between exact B435 paragraphs.
 *
 * A semantic connector such as contrast/support/elaboration is permitted only when B433 supplied
 * an exact evidenced relation crossing the paragraph boundary. Otherwise B436 can use only a
 * presentation-order SEQUENCE connector. B435 source paragraph text is never rewritten.
 */
class TransitionCoherenceEngine {
    fun compose(
        goal: DocumentGoal,
        argumentPlan: DocumentArgumentPlan,
        paragraphs: ParagraphComposition,
    ): CoherentDocumentDraft {
        require(argumentPlan.documentGoalFingerprint == goal.fingerprint)
        require(paragraphs.argumentPlanFingerprint == argumentPlan.fingerprint)

        val byClaim = argumentPlan.steps.associateBy { it.claimId }
        val coherent = paragraphs.paragraphs.mapIndexed { index, paragraph ->
            if (index == 0) {
                coherentParagraph(
                    paragraph = paragraph,
                    ordinal = index,
                    kind = ParagraphTransitionKind.NONE,
                    transition = null,
                    relationEvidence = emptyList(),
                )
            } else {
                val previous = paragraphs.paragraphs[index - 1]
                val crossing = argumentPlan.relations.filter { relation ->
                    relation.fromClaimId in previous.claimIds &&
                        relation.toClaimId in paragraph.claimIds &&
                        relation.fromClaimId in byClaim &&
                        relation.toClaimId in byClaim
                }
                val semantic = crossing
                    .filter {
                        it.kind != DocumentArgumentRelationKind.SEQUENCE &&
                            it.evidenceFingerprint != null
                    }
                    .sortedWith(
                        compareByDescending<app.lifeos.core.model.DocumentArgumentRelation> {
                            relationPriority(it.kind)
                        }.thenBy { it.fingerprint }
                    )
                    .firstOrNull()

                val kind = when (semantic?.kind) {
                    DocumentArgumentRelationKind.CONTRASTS ->
                        ParagraphTransitionKind.CONTRAST
                    DocumentArgumentRelationKind.SUPPORTS ->
                        ParagraphTransitionKind.SUPPORT
                    DocumentArgumentRelationKind.ELABORATES ->
                        ParagraphTransitionKind.ELABORATION
                    DocumentArgumentRelationKind.SEQUENCE,
                    null -> ParagraphTransitionKind.SEQUENCE
                }
                val relationEvidence = semantic?.evidenceFingerprint
                    ?.let(::listOf)
                    .orEmpty()
                coherentParagraph(
                    paragraph = paragraph,
                    ordinal = index,
                    kind = kind,
                    transition = transitionFor(goal.languageTag, kind),
                    relationEvidence = relationEvidence,
                )
            }
        }

        val rendered = coherent.joinToString("\n\n") { it.renderedText }
        return CoherentDocumentDraft(
            documentGoalFingerprint = goal.fingerprint,
            argumentPlanFingerprint = argumentPlan.fingerprint,
            paragraphCompositionFingerprint = paragraphs.fingerprint,
            paragraphs = coherent,
            renderedText = rendered,
            fingerprint = coherentDraftFingerprint(
                goal.fingerprint,
                argumentPlan.fingerprint,
                paragraphs.fingerprint,
                coherent,
                rendered,
            ),
        )
    }

    private fun coherentParagraph(
        paragraph: DocumentParagraph,
        ordinal: Int,
        kind: ParagraphTransitionKind,
        transition: String?,
        relationEvidence: List<String>,
    ): CoherentParagraph {
        val rendered = if (transition == null) {
            paragraph.text
        } else {
            transition + " " + paragraph.text
        }
        val evidence = relationEvidence.distinct().sorted()
        return CoherentParagraph(
            paragraphFingerprint = paragraph.fingerprint,
            ordinal = ordinal,
            transitionKind = kind,
            transitionText = transition,
            sourceText = paragraph.text,
            renderedText = rendered,
            relationEvidenceFingerprints = evidence,
            fingerprint = coherentParagraphFingerprint(
                paragraph.fingerprint,
                ordinal,
                kind,
                transition,
                paragraph.text,
                rendered,
                evidence,
            ),
        )
    }
}

private fun transitionFor(
    languageTag: String,
    kind: ParagraphTransitionKind,
): String? {
    if (kind == ParagraphTransitionKind.NONE) return null
    val german = languageTag.lowercase().startsWith("de")
    return if (german) {
        when (kind) {
            ParagraphTransitionKind.NONE -> null
            ParagraphTransitionKind.SEQUENCE -> "Anschließend"
            ParagraphTransitionKind.SUPPORT -> "Ergänzend"
            ParagraphTransitionKind.ELABORATION -> "Genauer"
            ParagraphTransitionKind.CONTRAST -> "Demgegenüber"
        }
    } else {
        when (kind) {
            ParagraphTransitionKind.NONE -> null
            ParagraphTransitionKind.SEQUENCE -> "Next,"
            ParagraphTransitionKind.SUPPORT -> "Additionally,"
            ParagraphTransitionKind.ELABORATION -> "More specifically,"
            ParagraphTransitionKind.CONTRAST -> "By contrast,"
        }
    }
}

private fun relationPriority(
    kind: DocumentArgumentRelationKind,
): Int = when (kind) {
    DocumentArgumentRelationKind.CONTRASTS -> 3
    DocumentArgumentRelationKind.SUPPORTS -> 2
    DocumentArgumentRelationKind.ELABORATES -> 1
    DocumentArgumentRelationKind.SEQUENCE -> 0
}

private fun coherentParagraphFingerprint(
    paragraphFingerprint: String,
    ordinal: Int,
    transitionKind: ParagraphTransitionKind,
    transitionText: String?,
    sourceText: String,
    renderedText: String,
    relationEvidenceFingerprints: List<String>,
): String = StableCognitiveIds.fingerprint(
    "coherent-paragraph/v1",
    paragraphFingerprint,
    ordinal.toString(),
    transitionKind.name,
    transitionText.orEmpty(),
    sourceText,
    renderedText,
    relationEvidenceFingerprints.joinToString("\u001f"),
)

private fun coherentDraftFingerprint(
    documentGoalFingerprint: String,
    argumentPlanFingerprint: String,
    paragraphCompositionFingerprint: String,
    paragraphs: List<CoherentParagraph>,
    renderedText: String,
): String = StableCognitiveIds.fingerprint(
    "coherent-document-draft/v1",
    documentGoalFingerprint,
    argumentPlanFingerprint,
    paragraphCompositionFingerprint,
    *paragraphs.map { it.fingerprint }.toTypedArray(),
    renderedText,
)

private val SHA_256_B436 = Regex("[0-9a-f]{64}")
