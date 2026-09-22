package app.lifeos.core.creative

import app.lifeos.core.model.DocumentArgumentPlanner
import app.lifeos.core.model.DocumentDepth
import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentPurpose
import app.lifeos.core.model.DocumentStructurePlanner
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.SemanticArtifactClaim
import app.lifeos.core.model.SemanticArtifactKind
import app.lifeos.core.model.SemanticArtifactPlan
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactualDraftValidatorTest {
    @Test
    fun exact_closed_draft_passes_lineage_validation() {
        val b = bundle()

        val report = FactualDraftValidator().validate(
            b.semantic,
            b.argument,
            b.sentences,
            b.composition,
            b.coherent,
            b.citations,
        )

        assertTrue(report.passed)
        assertTrue(report.issues.isEmpty())
        assertTrue(
            report.validatedClaimIds ==
                b.argument.steps.map { it.claimId }.sorted()
        )
        assertFalse(report.factualPromotionAuthority)
        assertFalse(report.claimCreationAuthority)
        assertFalse(report.finalizationAuthority)
    }

    @Test
    fun missing_sentence_fails_validation() {
        val b = bundle()

        val report = FactualDraftValidator().validate(
            b.semantic,
            b.argument,
            b.sentences.dropLast(1),
            b.composition,
            b.coherent,
            b.citations,
        )

        assertFalse(report.passed)
        assertTrue(
            report.issues.any {
                it.kind == FactualDraftIssueKind.MISSING_SENTENCE
            }
        )
    }

    @Test
    fun citation_plan_from_other_draft_fails_validation() {
        val first = bundle("a")
        val second = bundle("b")

        val report = FactualDraftValidator().validate(
            first.semantic,
            first.argument,
            first.sentences,
            first.composition,
            first.coherent,
            second.citations,
        )

        assertFalse(report.passed)
        assertTrue(
            report.issues.any {
                it.kind == FactualDraftIssueKind.MISSING_CITATION_BINDING
            }
        )
    }

    @Test
    fun validation_is_deterministic() {
        val b = bundle()
        val validator = FactualDraftValidator()

        assertTrue(
            validator.validate(
                b.semantic,
                b.argument,
                b.sentences,
                b.composition,
                b.coherent,
                b.citations,
            ) ==
                validator.validate(
                    b.semantic,
                    b.argument,
                    b.sentences.reversed(),
                    b.composition,
                    b.coherent,
                    b.citations,
                )
        )
    }

    private fun bundle(seed: String = "p"): Bundle {
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..2).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId(seed + index),
                            index.toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = "Supported claim " + index,
                )
            },
            sourceWorldRevision = 12L,
        )
        val goal = DocumentGoal.create(
            semantic,
            DocumentPurpose.EXPLAIN,
            "reader",
            "en",
            DocumentOutputFormat.MARKDOWN,
            DocumentDepth.EXHAUSTIVE,
        )
        val structure = DocumentStructurePlanner().plan(goal, semantic)
        val argument = DocumentArgumentPlanner().plan(
            goal,
            structure,
            semantic,
        )
        val sentences = argument.steps.map {
            ClaimToSentenceRealizer().realize(
                goal,
                argument,
                semantic,
                it,
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
        return Bundle(
            semantic,
            argument,
            sentences,
            composition,
            coherent,
            citations,
        )
    }

    private data class Bundle(
        val semantic: SemanticArtifactPlan,
        val argument: app.lifeos.core.model.DocumentArgumentPlan,
        val sentences: List<ClaimSentence>,
        val composition: ParagraphComposition,
        val coherent: CoherentDocumentDraft,
        val citations: CitationBindingPlan,
    )
}
