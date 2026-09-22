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
import app.lifeos.core.model.StableCognitiveIds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StructuredTextArtifactRendererTest {
    @Test
    fun markdown_renderer_emits_exact_validated_prose_and_bound_citations() {
        val b = bundle(DocumentOutputFormat.MARKDOWN)

        val artifact = StructuredTextArtifactRenderer().render(
            b.goal,
            b.structure,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
            b.revision,
        )

        assertEquals("text/markdown; charset=utf-8", artifact.mediaType)
        assertEquals(b.citations.coveredClaimIds, artifact.renderedClaimIds)
        assertEquals(2, artifact.citationEvidenceStableKeys.size)
        assertTrue(artifact.content.contains(b.coherent.paragraphs.first().renderedText))
        assertTrue(artifact.content.contains("[^1]"))
        assertTrue(
            artifact.content.contains(
                "[^1]: " + artifact.citationEvidenceStableKeys.first()
            )
        )
        assertContentEquals(
            artifact.content.toByteArray(Charsets.UTF_8),
            artifact.payload,
        )
        assertFalse(artifact.factualAuthority)
        assertFalse(artifact.citationAuthority)
        assertFalse(artifact.finalizationAuthority)
        assertFalse(artifact.publicationAuthority)
    }

    @Test
    fun html_renderer_emits_structured_sections_and_citation_list() {
        val b = bundle(DocumentOutputFormat.HTML)

        val artifact = StructuredTextArtifactRenderer().render(
            b.goal,
            b.structure,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
            b.revision,
        )

        assertEquals("text/html; charset=utf-8", artifact.mediaType)
        assertTrue(artifact.content.startsWith("<article"))
        assertTrue(artifact.content.contains("<section data-section-key=\"section-1\">"))
        assertTrue(artifact.content.contains("<ol class=\"citations\">"))
        assertTrue(artifact.content.contains("id=\"cite-1\""))
    }

    @Test
    fun renderer_refuses_non_converged_revision_chain() {
        val b = bundle(DocumentOutputFormat.MARKDOWN)
        val warningEvidence = DraftRevisionEvidence.fromSignals(
            documentGoalFingerprint = b.goal.fingerprint,
            draftFingerprint = b.coherent.fingerprint,
            factualValidationFingerprint = b.validation.fingerprint,
            factualPassed = true,
            critiqueFingerprint = fp("warning-critique"),
            critiqueStatus = DocumentCritiqueStatus.REVISION_RECOMMENDED,
            errorCount = 0,
            warningCount = 1,
            findingFingerprints = listOf(fp("warning-finding")),
        )
        val stalled = IterativeDraftRevisionEngine().converge(
            warningEvidence,
            emptyList(),
        )

        assertEquals(DraftRevisionLoopState.STALLED, stalled.state)
        assertFailsWith<IllegalArgumentException> {
            StructuredTextArtifactRenderer().render(
                b.goal,
                b.structure,
                b.composition,
                b.coherent,
                b.citations,
                b.validation,
                stalled,
            )
        }
    }

    @Test
    fun renderer_refuses_plain_text_goal() {
        val b = bundle(DocumentOutputFormat.PLAIN_TEXT)

        assertFailsWith<IllegalArgumentException> {
            StructuredTextArtifactRenderer().render(
                b.goal,
                b.structure,
                b.composition,
                b.coherent,
                b.citations,
                b.validation,
                b.revision,
            )
        }
    }

    private fun bundle(format: DocumentOutputFormat): Bundle {
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..2).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId("b443-photon-" + index),
                            index.toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = "Supported claim " + index,
                )
            },
            sourceWorldRevision = 44L,
        )
        val goal = DocumentGoal.create(
            semantic,
            DocumentPurpose.EXPLAIN,
            "reader",
            "en",
            format,
            DocumentDepth.EXHAUSTIVE,
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
        assertTrue(validation.passed)
        val cleanEvidence = DraftRevisionEvidence.fromSignals(
            documentGoalFingerprint = goal.fingerprint,
            draftFingerprint = coherent.fingerprint,
            factualValidationFingerprint = validation.fingerprint,
            factualPassed = true,
            critiqueFingerprint = fp("clean-critique-" + format.name),
            critiqueStatus = DocumentCritiqueStatus.PASSED,
            errorCount = 0,
            warningCount = 0,
            findingFingerprints = emptyList(),
        )
        val revision = IterativeDraftRevisionEngine().converge(
            cleanEvidence,
            emptyList(),
        )
        assertEquals(DraftRevisionLoopState.CONVERGED, revision.state)

        return Bundle(
            goal,
            structure,
            argument,
            composition,
            coherent,
            citations,
            validation,
            revision,
        )
    }

    private fun fp(value: String): String =
        StableCognitiveIds.fingerprint("b443-test/v1", value)

    private data class Bundle(
        val goal: DocumentGoal,
        val structure: DocumentStructurePlan,
        val argument: DocumentArgumentPlan,
        val composition: ParagraphComposition,
        val coherent: CoherentDocumentDraft,
        val citations: CitationBindingPlan,
        val validation: FactualDraftValidationReport,
        val revision: IterativeDraftRevisionReport,
    )
}
