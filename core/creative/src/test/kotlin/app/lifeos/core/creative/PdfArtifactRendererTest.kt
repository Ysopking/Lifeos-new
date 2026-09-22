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
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfArtifactRendererTest {
    @Test
    fun renderer_emits_deterministic_valid_pdf_with_metadata_and_citations() {
        val b = bundle(DocumentOutputFormat.PDF)
        val renderer = PdfArtifactRenderer()

        val first = renderer.render(
            b.goal,
            b.structure,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
            b.revision,
        )
        val second = renderer.render(
            b.goal,
            b.structure,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
            b.revision,
        )

        assertEquals("application/pdf", first.mediaType)
        assertContentEquals(first.payload, second.payload)
        assertEquals(first.contentSha256, second.contentSha256)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(b.citations.coveredClaimIds, first.renderedClaimIds)
        assertEquals(1, first.pageCount)

        val text = first.payload.toString(Charset.forName("windows-1252"))
        assertTrue(text.startsWith("%PDF-1.7"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(text.contains("Section 1"))
        assertTrue(text.contains(b.coherent.paragraphs.first().renderedText))
        assertTrue(text.contains("[1]"))
        assertTrue(text.contains("References"))
        assertTrue(text.contains(first.citationEvidenceStableKeys.first()))
        assertTrue(text.contains("/CreationDate (D:20000101000000Z)"))
        assertPdfXref(first.payload)

        assertFalse(first.factualAuthority)
        assertFalse(first.claimCreationAuthority)
        assertFalse(first.citationAuthority)
        assertFalse(first.finalizationAuthority)
        assertFalse(first.publicationAuthority)
    }

    @Test
    fun renderer_paginates_long_documents_without_changing_claim_lineage() {
        val b = bundle(
            format = DocumentOutputFormat.PDF,
            claimCount = 50,
            claimContent = { index ->
                "Supported claim " + index + " contains enough descriptive words to require stable page layout."
            },
        )

        val artifact = PdfArtifactRenderer().render(
            b.goal,
            b.structure,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
            b.revision,
        )

        assertTrue(artifact.pageCount > 1)
        assertEquals(b.citations.coveredClaimIds, artifact.renderedClaimIds)
        val text = artifact.payload.toString(Charset.forName("windows-1252"))
        assertTrue(text.contains("/Count " + artifact.pageCount))
        assertPdfXref(artifact.payload)
    }

    @Test
    fun renderer_fails_closed_on_unrepresentable_glyphs() {
        val b = bundle(
            format = DocumentOutputFormat.PDF,
            claimCount = 1,
            claimContent = { "Unsupported emoji \uD83D\uDE80" },
        )

        assertFailsWith<IllegalArgumentException> {
            PdfArtifactRenderer().render(
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

    @Test
    fun renderer_refuses_non_pdf_goal() {
        val b = bundle(DocumentOutputFormat.DOCX)

        assertFailsWith<IllegalArgumentException> {
            PdfArtifactRenderer().render(
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

    @Test
    fun renderer_refuses_non_converged_revision_chain() {
        val b = bundle(DocumentOutputFormat.PDF)
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
            PdfArtifactRenderer().render(
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

    private fun bundle(
        format: DocumentOutputFormat,
        claimCount: Int = 2,
        claimContent: (Int) -> String = { index -> "Supported claim " + index },
    ): Bundle {
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..claimCount).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(3, '0'),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId("b445-photon-" + index),
                            index.toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = claimContent(index),
                )
            },
            sourceWorldRevision = 46L,
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
            critiqueFingerprint = fp("clean-critique-" + format.name + "-" + claimCount),
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

    private fun assertPdfXref(bytes: ByteArray) {
        val text = bytes.toString(Charset.forName("windows-1252"))
        val startMarker = "startxref\n"
        val startIndex = text.lastIndexOf(startMarker)
        assertTrue(startIndex > 0)
        val xrefOffset = text
            .substring(startIndex + startMarker.length)
            .substringBefore('\n')
            .toInt()
        assertTrue(text.startsWith("xref\n", xrefOffset))

        val xrefTail = text.substring(xrefOffset)
        val lines = xrefTail.lines()
        assertEquals("xref", lines[0])
        val count = lines[1].substringAfter(' ').toInt()
        assertTrue(count > 1)
        for (id in 1 until count) {
            val offset = lines[id + 2].substring(0, 10).toInt()
            assertTrue(text.startsWith(id.toString() + " 0 obj\n", offset))
        }
    }

    private fun fp(value: String): String =
        StableCognitiveIds.fingerprint("b445-test/v1", value)

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
