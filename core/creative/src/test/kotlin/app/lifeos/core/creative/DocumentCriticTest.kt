package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlan
import app.lifeos.core.model.DocumentArgumentPlanner
import app.lifeos.core.model.DocumentDepth
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentPurpose
import app.lifeos.core.model.DocumentStructurePlan
import app.lifeos.core.model.DocumentStructurePlanner
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCriticTest {
    @Test
    fun exact_closed_document_passes_without_rewrite_authority() {
        val b = bundle()

        val report = DocumentCritic().critique(
            b.goal,
            b.structure,
            b.argument,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
        )

        assertEquals(DocumentCritiqueStatus.PASSED, report.status)
        assertTrue(report.findings.isEmpty())
        assertEquals(b.goal.requiredClaimIds.size, report.metrics.validatedClaimCount)
        assertFalse(report.rewriteAuthority)
        assertFalse(report.factualAuthority)
        assertFalse(report.ownerStyleAuthority)
        assertFalse(report.finalizationAuthority)
    }

    @Test
    fun failed_b438_validation_blocks_document() {
        val b = bundle()
        val failed = FactualDraftValidator().validate(
            b.semantic,
            b.argument,
            b.sentences.dropLast(1),
            b.composition,
            b.coherent,
            b.citations,
        )

        val report = DocumentCritic().critique(
            b.goal,
            b.structure,
            b.argument,
            b.composition,
            b.coherent,
            b.citations,
            failed,
        )

        assertEquals(DocumentCritiqueStatus.BLOCKED, report.status)
        assertTrue(
            report.findings.any {
                it.dimension == DocumentCritiqueDimension.FACTUAL_INTEGRITY
            }
        )
        assertTrue(
            report.findings.any {
                it.dimension == DocumentCritiqueDimension.COMPLETENESS
            }
        )
    }

    @Test
    fun duplicate_paragraph_content_requests_revision_without_blocking() {
        val b = bundle(duplicateContent = true)

        val report = DocumentCritic().critique(
            b.goal,
            b.structure,
            b.argument,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
        )

        assertEquals(DocumentCritiqueStatus.REVISION_RECOMMENDED, report.status)
        assertEquals(1, report.metrics.duplicateParagraphCount)
        assertTrue(
            report.findings.any {
                it.dimension == DocumentCritiqueDimension.REDUNDANCY &&
                    it.code == "duplicate-paragraph-content"
            }
        )
    }

    @Test
    fun explicit_machine_readable_style_constraint_is_enforced() {
        val b = bundle(constraints = listOf("style:max-paragraph-chars=10"))

        val report = DocumentCritic().critique(
            b.goal,
            b.structure,
            b.argument,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
        )

        assertEquals(DocumentCritiqueStatus.REVISION_RECOMMENDED, report.status)
        assertTrue(
            report.findings.any {
                it.dimension == DocumentCritiqueDimension.STYLE &&
                    it.code == "max-paragraph-chars-exceeded"
            }
        )
    }

    @Test
    fun critique_is_deterministic() {
        val b = bundle(constraints = listOf("style:max-document-chars=20"))
        val critic = DocumentCritic()

        assertEquals(
            critic.critique(
                b.goal,
                b.structure,
                b.argument,
                b.composition,
                b.coherent,
                b.citations,
                b.validation,
            ),
            critic.critique(
                b.goal,
                b.structure,
                b.argument,
                b.composition,
                b.coherent,
                b.citations,
                b.validation,
            ),
        )
    }

    private fun bundle(
        duplicateContent: Boolean = false,
        constraints: List<String> = emptyList(),
    ): Bundle {
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..2).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId("critic-" + index),
                            index.toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = if (duplicateContent) {
                        "Supported shared claim"
                    } else {
                        "Supported claim " + index
                    },
                )
            },
            sourceWorldRevision = 13L,
        )
        val goal = DocumentGoal.create(
            semanticPlan = semantic,
            purpose = DocumentPurpose.EXPLAIN,
            audience = "reader",
            languageTag = "en",
            outputFormat = DocumentOutputFormat.MARKDOWN,
            depth = DocumentDepth.EXHAUSTIVE,
            constraints = constraints,
        )
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(goal, structure, semantic)
        val sentences = argument.steps.map { step ->
            ClaimToSentenceRealizer().realize(
                goal,
                argument,
                semantic,
                step,
            )
        }
        val composition = ParagraphCompositionEngine().compose(
            structure,
            argument,
            sentences,
        )
        val coherent = TransitionCoherenceEngine().compose(
            goal,
            argument,
            composition,
        )
        val citations = CitationBinder().bind(
            semantic,
            argument,
            sentences,
            composition,
            coherent,
        )
        val validation = FactualDraftValidator().validate(
            semantic,
            argument,
            sentences,
            composition,
            coherent,
            citations,
        )
        return Bundle(
            semantic,
            goal,
            structure,
            argument,
            sentences,
            composition,
            coherent,
            citations,
            validation,
        )
    }

    private data class Bundle(
        val semantic: SemanticArtifactPlan,
        val goal: DocumentGoal,
        val structure: DocumentStructurePlan,
        val argument: DocumentArgumentPlan,
        val sentences: List<ClaimSentence>,
        val composition: ParagraphComposition,
        val coherent: CoherentDocumentDraft,
        val citations: CitationBindingPlan,
        val validation: FactualDraftValidationReport,
    )
}
