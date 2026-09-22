package app.lifeos.core.creative

import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentStructurePlan
import app.lifeos.core.model.StableCognitiveIds
import java.nio.charset.StandardCharsets

data class StructuredTextArtifact(
    val documentGoalFingerprint: String,
    val structureFingerprint: String,
    val draftFingerprint: String,
    val citationPlanFingerprint: String,
    val factualValidationFingerprint: String,
    val revisionReportFingerprint: String,
    val outputFormat: DocumentOutputFormat,
    val mediaType: String,
    val content: String,
    val contentFingerprint: String,
    val renderedClaimIds: List<String>,
    val citationEvidenceStableKeys: List<String>,
    val fingerprint: String,
) {
    init {
        require(documentGoalFingerprint.matches(SHA_256_B443))
        require(structureFingerprint.matches(SHA_256_B443))
        require(draftFingerprint.matches(SHA_256_B443))
        require(citationPlanFingerprint.matches(SHA_256_B443))
        require(factualValidationFingerprint.matches(SHA_256_B443))
        require(revisionReportFingerprint.matches(SHA_256_B443))
        require(outputFormat == DocumentOutputFormat.MARKDOWN || outputFormat == DocumentOutputFormat.HTML)
        require(
            mediaType == when (outputFormat) {
                DocumentOutputFormat.MARKDOWN -> "text/markdown; charset=utf-8"
                DocumentOutputFormat.HTML -> "text/html; charset=utf-8"
                else -> error("unsupported structured text format")
            }
        )
        require(content.isNotBlank())
        require(
            contentFingerprint == structuredTextContentFingerprint(
                outputFormat,
                mediaType,
                content,
            )
        )
        require(renderedClaimIds.isNotEmpty())
        require(renderedClaimIds == renderedClaimIds.distinct().sorted())
        require(citationEvidenceStableKeys.isNotEmpty())
        require(citationEvidenceStableKeys == citationEvidenceStableKeys.distinct())
        require(citationEvidenceStableKeys.none(String::isBlank))
        require(
            fingerprint == structuredTextArtifactFingerprint(
                documentGoalFingerprint,
                structureFingerprint,
                draftFingerprint,
                citationPlanFingerprint,
                factualValidationFingerprint,
                revisionReportFingerprint,
                outputFormat,
                mediaType,
                contentFingerprint,
                renderedClaimIds,
                citationEvidenceStableKeys,
            )
        )
    }

    val payload: ByteArray
        get() = content.toByteArray(StandardCharsets.UTF_8)

    val factualAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
}

/**
 * B443 renders an already-converged, factually closed B431-B440 document into deterministic
 * Markdown or HTML bytes.
 *
 * Rendering is presentation-only. It cannot add claims, choose new evidence, rewrite factual
 * prose, finalize an artifact, publish it, or widen Owner Policy.
 */
class StructuredTextArtifactRenderer {
    fun render(
        goal: DocumentGoal,
        structure: DocumentStructurePlan,
        paragraphs: ParagraphComposition,
        draft: CoherentDocumentDraft,
        citations: CitationBindingPlan,
        factualValidation: FactualDraftValidationReport,
        revisionReport: IterativeDraftRevisionReport,
    ): StructuredTextArtifact {
        require(
            goal.outputFormat == DocumentOutputFormat.MARKDOWN ||
                goal.outputFormat == DocumentOutputFormat.HTML
        ) {
            "B443 accepts MARKDOWN or HTML DocumentGoal only"
        }
        require(structure.documentGoalFingerprint == goal.fingerprint)
        require(paragraphs.structureFingerprint == structure.fingerprint)
        require(draft.documentGoalFingerprint == goal.fingerprint)
        require(draft.paragraphCompositionFingerprint == paragraphs.fingerprint)
        require(citations.semanticPlanFingerprint == structure.semanticPlanFingerprint)
        require(citations.argumentPlanFingerprint == draft.argumentPlanFingerprint)
        require(citations.coherentDraftFingerprint == draft.fingerprint)
        require(factualValidation.semanticPlanFingerprint == structure.semanticPlanFingerprint)
        require(factualValidation.argumentPlanFingerprint == draft.argumentPlanFingerprint)
        require(factualValidation.coherentDraftFingerprint == draft.fingerprint)
        require(factualValidation.citationPlanFingerprint == citations.fingerprint)
        require(factualValidation.passed) {
            "B443 refuses a draft that failed B438 factual closure"
        }
        require(revisionReport.state == DraftRevisionLoopState.CONVERGED) {
            "B443 requires B440 convergence"
        }
        require(revisionReport.finalEvidence.documentGoalFingerprint == goal.fingerprint)
        require(revisionReport.finalEvidence.draftFingerprint == draft.fingerprint)
        require(
            revisionReport.finalEvidence.factualValidationFingerprint ==
                factualValidation.fingerprint
        )
        require(revisionReport.finalEvidence.factualPassed)
        require(
            revisionReport.finalEvidence.critiqueStatus == DocumentCritiqueStatus.PASSED
        ) {
            "B443 requires a clean final B439 critique"
        }

        require(
            draft.paragraphs.map { it.paragraphFingerprint } ==
                paragraphs.paragraphs.map { it.fingerprint }
        ) {
            "B443 draft/paragraph lineage mismatch"
        }
        require(citations.coveredClaimIds == paragraphs.claimIds.sorted()) {
            "B443 requires exact citation coverage for rendered claims"
        }

        val sourceParagraphByFingerprint =
            paragraphs.paragraphs.associateBy { it.fingerprint }
        val bindingsByParagraph =
            citations.bindings.groupBy { it.paragraphFingerprint }

        val citationKeysByParagraph = draft.paragraphs.associate { coherent ->
            val source = requireNotNull(
                sourceParagraphByFingerprint[coherent.paragraphFingerprint]
            ) {
                "B443 coherent paragraph missing exact B435 source"
            }
            val bindings = bindingsByParagraph[source.fingerprint].orEmpty()
            require(bindings.isNotEmpty()) {
                "B443 refuses an uncited rendered paragraph"
            }
            coherent.paragraphFingerprint to bindings
                .flatMap { it.evidenceStableKeys }
                .distinct()
                .sorted()
        }

        val citationKeys = draft.paragraphs
            .flatMap { citationKeysByParagraph.getValue(it.paragraphFingerprint) }
            .distinct()
        require(citationKeys.isNotEmpty())

        val citationIndex = citationKeys
            .mapIndexed { index, key -> key to index + 1 }
            .toMap()

        val content = when (goal.outputFormat) {
            DocumentOutputFormat.MARKDOWN -> renderMarkdown(
                draft,
                sourceParagraphByFingerprint,
                citationKeysByParagraph,
                citationIndex,
                citationKeys,
            )
            DocumentOutputFormat.HTML -> renderHtml(
                goal,
                draft,
                sourceParagraphByFingerprint,
                citationKeysByParagraph,
                citationIndex,
                citationKeys,
            )
            else -> error("B443 unsupported output format")
        }

        val mediaType = when (goal.outputFormat) {
            DocumentOutputFormat.MARKDOWN -> "text/markdown; charset=utf-8"
            DocumentOutputFormat.HTML -> "text/html; charset=utf-8"
            else -> error("B443 unsupported output format")
        }
        val contentFingerprint = structuredTextContentFingerprint(
            goal.outputFormat,
            mediaType,
            content,
        )
        val renderedClaims = citations.coveredClaimIds.distinct().sorted()

        return StructuredTextArtifact(
            documentGoalFingerprint = goal.fingerprint,
            structureFingerprint = structure.fingerprint,
            draftFingerprint = draft.fingerprint,
            citationPlanFingerprint = citations.fingerprint,
            factualValidationFingerprint = factualValidation.fingerprint,
            revisionReportFingerprint = revisionReport.fingerprint,
            outputFormat = goal.outputFormat,
            mediaType = mediaType,
            content = content,
            contentFingerprint = contentFingerprint,
            renderedClaimIds = renderedClaims,
            citationEvidenceStableKeys = citationKeys,
            fingerprint = structuredTextArtifactFingerprint(
                goal.fingerprint,
                structure.fingerprint,
                draft.fingerprint,
                citations.fingerprint,
                factualValidation.fingerprint,
                revisionReport.fingerprint,
                goal.outputFormat,
                mediaType,
                contentFingerprint,
                renderedClaims,
                citationKeys,
            ),
        )
    }

    private fun renderMarkdown(
        draft: CoherentDocumentDraft,
        sourceParagraphByFingerprint: Map<String, DocumentParagraph>,
        citationKeysByParagraph: Map<String, List<String>>,
        citationIndex: Map<String, Int>,
        citationKeys: List<String>,
    ): String {
        val body = draft.paragraphs.joinToString("\n\n") { coherent ->
            val source = sourceParagraphByFingerprint.getValue(coherent.paragraphFingerprint)
            val references = citationKeysByParagraph
                .getValue(coherent.paragraphFingerprint)
                .joinToString(separator = "") { key ->
                    "[^" + citationIndex.getValue(key) + "]"
                }
            "<!-- section:" + markdownCommentSafe(source.sectionKey) + " -->\n" +
                coherent.renderedText + references
        }
        val footnotes = citationKeys.joinToString("\n") { key ->
            "[^" + citationIndex.getValue(key) + "]: " + key
        }
        return body + "\n\n" + footnotes + "\n"
    }

    private fun renderHtml(
        goal: DocumentGoal,
        draft: CoherentDocumentDraft,
        sourceParagraphByFingerprint: Map<String, DocumentParagraph>,
        citationKeysByParagraph: Map<String, List<String>>,
        citationIndex: Map<String, Int>,
        citationKeys: List<String>,
    ): String = buildString {
        append("<article data-document-goal-fingerprint=\"")
        append(escapeHtml(goal.fingerprint))
        append("\">\n")
        draft.paragraphs.forEach { coherent ->
            val source = sourceParagraphByFingerprint.getValue(coherent.paragraphFingerprint)
            append("  <section data-section-key=\"")
            append(escapeHtml(source.sectionKey))
            append("\"><p>")
            append(escapeHtml(coherent.renderedText))
            citationKeysByParagraph.getValue(coherent.paragraphFingerprint).forEach { key ->
                val index = citationIndex.getValue(key)
                append("<sup><a href=\"#cite-")
                append(index)
                append("\" aria-label=\"citation ")
                append(index)
                append("\">[")
                append(index)
                append("]</a></sup>")
            }
            append("</p></section>\n")
        }
        append("  <ol class=\"citations\">\n")
        citationKeys.forEach { key ->
            val index = citationIndex.getValue(key)
            append("    <li id=\"cite-")
            append(index)
            append("\"><code>")
            append(escapeHtml(key))
            append("</code></li>\n")
        }
        append("  </ol>\n")
        append("</article>\n")
    }
}

private fun markdownCommentSafe(value: String): String =
    value.replace("--", "- -").replace("\n", " ").replace("\r", " ")

private fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}

private fun structuredTextContentFingerprint(
    outputFormat: DocumentOutputFormat,
    mediaType: String,
    content: String,
): String = StableCognitiveIds.fingerprint(
    "structured-text-content/v1",
    outputFormat.name,
    mediaType,
    content,
)

private fun structuredTextArtifactFingerprint(
    documentGoalFingerprint: String,
    structureFingerprint: String,
    draftFingerprint: String,
    citationPlanFingerprint: String,
    factualValidationFingerprint: String,
    revisionReportFingerprint: String,
    outputFormat: DocumentOutputFormat,
    mediaType: String,
    contentFingerprint: String,
    renderedClaimIds: List<String>,
    citationEvidenceStableKeys: List<String>,
): String = StableCognitiveIds.fingerprint(
    "structured-text-artifact/v1",
    documentGoalFingerprint,
    structureFingerprint,
    draftFingerprint,
    citationPlanFingerprint,
    factualValidationFingerprint,
    revisionReportFingerprint,
    outputFormat.name,
    mediaType,
    contentFingerprint,
    renderedClaimIds.joinToString("\u001f"),
    citationEvidenceStableKeys.joinToString("\u001f"),
)

private val SHA_256_B443 = Regex("[0-9a-f]{64}")
