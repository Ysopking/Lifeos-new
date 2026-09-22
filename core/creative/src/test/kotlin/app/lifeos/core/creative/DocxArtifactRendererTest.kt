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
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocxArtifactRendererTest {
    @Test
    fun renderer_emits_deterministic_real_docx_with_headings_and_citations() {
        val b = bundle(DocumentOutputFormat.DOCX)
        val renderer = DocxArtifactRenderer()

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

        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            first.mediaType,
        )
        assertContentEquals(first.payload, second.payload)
        assertEquals(first.contentSha256, second.contentSha256)
        assertEquals(first.fingerprint, second.fingerprint)

        val parts = unzip(first.payload)
        assertTrue("[Content_Types].xml" in parts)
        assertTrue("_rels/.rels" in parts)
        assertTrue("word/document.xml" in parts)
        assertTrue("word/styles.xml" in parts)
        assertTrue("word/_rels/document.xml.rels" in parts)
        assertTrue("docProps/core.xml" in parts)
        assertTrue("docProps/app.xml" in parts)

        val documentXml = parts.getValue("word/document.xml").decodeToString()
        assertTrue(documentXml.contains("Section 1"))
        assertTrue(documentXml.contains(b.coherent.paragraphs.first().renderedText))
        assertTrue(documentXml.contains("[1]"))
        assertTrue(documentXml.contains("References"))
        assertTrue(documentXml.contains(first.citationEvidenceStableKeys.first()))

        assertEquals(b.citations.coveredClaimIds, first.renderedClaimIds)
        assertFalse(first.factualAuthority)
        assertFalse(first.claimCreationAuthority)
        assertFalse(first.citationAuthority)
        assertFalse(first.finalizationAuthority)
        assertFalse(first.publicationAuthority)
    }

    @Test
    fun renderer_embeds_evidence_bound_table_and_image_parts() {
        val b = bundle(DocumentOutputFormat.DOCX)
        val evidenceKeys = b.citations.bindings
            .flatMap { it.evidenceStableKeys }
            .distinct()
            .sorted()
        val section = b.structure.sections.first()
        val table = DocxTableAsset.create(
            sectionKey = section.sectionKey,
            ordinal = 0,
            sourceArtifactFingerprint = fp("table-source"),
            claimIds = listOf(section.claimIds.first()),
            evidenceStableKeys = listOf(evidenceKeys.first()),
            rows = listOf(
                listOf("Metric", "Value"),
                listOf("A", "1"),
            ),
        )
        val image = DocxImageAsset.create(
            sectionKey = section.sectionKey,
            ordinal = 0,
            sourceArtifactFingerprint = fp("image-source"),
            claimIds = listOf(section.claimIds.first()),
            evidenceStableKeys = listOf(evidenceKeys.first()),
            mediaType = "image/png",
            altText = "Evidence-bound image",
            widthPx = 1,
            heightPx = 1,
            payload = Base64.getDecoder().decode(ONE_PIXEL_PNG_BASE64),
        )

        val artifact = DocxArtifactRenderer().render(
            b.goal,
            b.structure,
            b.composition,
            b.coherent,
            b.citations,
            b.validation,
            b.revision,
            tables = listOf(table),
            images = listOf(image),
        )

        val parts = unzip(artifact.payload)
        val documentXml = parts.getValue("word/document.xml").decodeToString()
        val rels = parts.getValue("word/_rels/document.xml.rels").decodeToString()
        val types = parts.getValue("[Content_Types].xml").decodeToString()

        assertTrue(documentXml.contains("<w:tbl>"))
        assertTrue(documentXml.contains("Metric"))
        assertTrue(documentXml.contains("<w:drawing>"))
        assertTrue(documentXml.contains("Evidence-bound image"))
        assertNotNull(parts["word/media/image1.png"])
        assertTrue(rels.contains("rIdImage1"))
        assertTrue(types.contains("ContentType=\"image/png\""))
        assertEquals(listOf(table.fingerprint), artifact.embeddedTableFingerprints)
        assertEquals(listOf(image.fingerprint), artifact.embeddedImageFingerprints)
    }

    @Test
    fun renderer_refuses_non_docx_goal() {
        val b = bundle(DocumentOutputFormat.MARKDOWN)

        assertFailsWith<IllegalArgumentException> {
            DocxArtifactRenderer().render(
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
    fun renderer_rejects_supplement_with_foreign_claim_or_evidence() {
        val b = bundle(DocumentOutputFormat.DOCX)
        val section = b.structure.sections.first()
        val foreignClaimTable = DocxTableAsset.create(
            sectionKey = section.sectionKey,
            ordinal = 0,
            sourceArtifactFingerprint = fp("table-source-foreign"),
            claimIds = listOf("foreign-claim"),
            evidenceStableKeys = listOf(
                b.citations.bindings.first().evidenceStableKeys.first()
            ),
            rows = listOf(listOf("A")),
        )

        assertFailsWith<IllegalArgumentException> {
            DocxArtifactRenderer().render(
                b.goal,
                b.structure,
                b.composition,
                b.coherent,
                b.citations,
                b.validation,
                b.revision,
                tables = listOf(foreignClaimTable),
            )
        }

        val foreignEvidenceTable = DocxTableAsset.create(
            sectionKey = section.sectionKey,
            ordinal = 1,
            sourceArtifactFingerprint = fp("table-source-evidence"),
            claimIds = listOf(section.claimIds.first()),
            evidenceStableKeys = listOf("foreign-evidence"),
            rows = listOf(listOf("B")),
        )

        assertFailsWith<IllegalArgumentException> {
            DocxArtifactRenderer().render(
                b.goal,
                b.structure,
                b.composition,
                b.coherent,
                b.citations,
                b.validation,
                b.revision,
                tables = listOf(foreignEvidenceTable),
            )
        }
    }

    @Test
    fun renderer_refuses_non_converged_revision_chain() {
        val b = bundle(DocumentOutputFormat.DOCX)
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
            DocxArtifactRenderer().render(
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

    private fun bundle(format: DocumentOutputFormat): Bundle {
        val semantic = SemanticArtifactPlan(
            kind = SemanticArtifactKind.TEXT,
            claims = (1..2).map { index ->
                SemanticArtifactClaim(
                    claimId = "claim-" + index.toString().padStart(2, '0'),
                    evidence = setOf(
                        PhotonRevisionRef(
                            PhotonId("b444-photon-" + index),
                            index.toLong(),
                        )
                    ),
                    confidenceMicros = 900_000L,
                    canonicalContent = "Supported claim " + index,
                )
            },
            sourceWorldRevision = 45L,
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

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    result[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun fp(value: String): String =
        StableCognitiveIds.fingerprint("b444-test/v1", value)

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

    private companion object {
        const val ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl+7AAAAABJRU5ErkJggg=="
    }
}
