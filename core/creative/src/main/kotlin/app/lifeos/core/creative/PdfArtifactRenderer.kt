package app.lifeos.core.creative

import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentStructurePlan
import app.lifeos.core.model.StableCognitiveIds
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

class PdfArtifact internal constructor(
    val documentGoalFingerprint: String,
    val structureFingerprint: String,
    val draftFingerprint: String,
    val citationPlanFingerprint: String,
    val factualValidationFingerprint: String,
    val revisionReportFingerprint: String,
    val pageCount: Int,
    val renderedClaimIds: List<String>,
    val citationEvidenceStableKeys: List<String>,
    payload: ByteArray,
    val contentSha256: String,
    val fingerprint: String,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(documentGoalFingerprint.matches(SHA_256_B445))
        require(structureFingerprint.matches(SHA_256_B445))
        require(draftFingerprint.matches(SHA_256_B445))
        require(citationPlanFingerprint.matches(SHA_256_B445))
        require(factualValidationFingerprint.matches(SHA_256_B445))
        require(revisionReportFingerprint.matches(SHA_256_B445))
        require(pageCount > 0)
        require(renderedClaimIds.isNotEmpty())
        require(renderedClaimIds == renderedClaimIds.distinct().sorted())
        require(citationEvidenceStableKeys.isNotEmpty())
        require(citationEvidenceStableKeys == citationEvidenceStableKeys.distinct())
        require(payloadBytes.isNotEmpty())
        require(contentSha256 == sha256B445(payloadBytes))
        require(
            fingerprint == pdfArtifactFingerprint(
                documentGoalFingerprint,
                structureFingerprint,
                draftFingerprint,
                citationPlanFingerprint,
                factualValidationFingerprint,
                revisionReportFingerprint,
                pageCount,
                renderedClaimIds,
                citationEvidenceStableKeys,
                contentSha256,
            )
        )
    }

    val mediaType: String
        get() = "application/pdf"

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
}

/**
 * B445 renders the exact converged B431-B440 document lineage as a deterministic PDF 1.7 file.
 *
 * The first productive renderer intentionally uses the PDF standard Helvetica/Helvetica-Bold
 * fonts with WinAnsi encoding. It fails closed when source text is not representable rather than
 * silently replacing glyphs. PDF layout is presentation-only and adds no claims or evidence.
 */
class PdfArtifactRenderer {
    fun render(
        goal: DocumentGoal,
        structure: DocumentStructurePlan,
        paragraphs: ParagraphComposition,
        draft: CoherentDocumentDraft,
        citations: CitationBindingPlan,
        factualValidation: FactualDraftValidationReport,
        revisionReport: IterativeDraftRevisionReport,
    ): PdfArtifact {
        require(goal.outputFormat == DocumentOutputFormat.PDF) {
            "B445 accepts PDF DocumentGoal only"
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
            "B445 refuses a draft that failed B438 factual closure"
        }
        require(revisionReport.state == DraftRevisionLoopState.CONVERGED) {
            "B445 requires B440 convergence"
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
            "B445 requires a clean final B439 critique"
        }
        require(
            draft.paragraphs.map { it.paragraphFingerprint } ==
                paragraphs.paragraphs.map { it.fingerprint }
        ) {
            "B445 draft/paragraph lineage mismatch"
        }
        require(citations.coveredClaimIds == paragraphs.claimIds.sorted()) {
            "B445 requires exact citation coverage for rendered claims"
        }

        val sourceParagraphByFingerprint = paragraphs.paragraphs.associateBy { it.fingerprint }
        val bindingsByParagraph = citations.bindings.groupBy { it.paragraphFingerprint }

        val citationKeysByParagraph = draft.paragraphs.associate { coherent ->
            val source = requireNotNull(
                sourceParagraphByFingerprint[coherent.paragraphFingerprint]
            ) {
                "B445 coherent paragraph missing exact B435 source"
            }
            val bindings = bindingsByParagraph[source.fingerprint].orEmpty()
            require(bindings.isNotEmpty()) {
                "B445 refuses an uncited rendered paragraph"
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

        ensureWinAnsi(goal.audience, "B445 audience")
        ensureWinAnsi(goal.languageTag, "B445 language tag")
        draft.paragraphs.forEach { coherent ->
            ensureWinAnsi(coherent.renderedText, "B445 draft text")
        }
        citationKeys.forEach { key ->
            ensureWinAnsi(key, "B445 citation key")
        }

        val layout = PdfLayoutBuilder(goal)
        structure.sections.sortedBy { it.ordinal }.forEach { section ->
            layout.addHeading(sectionHeadingB445(goal, section.ordinal))
            val paragraph = requireNotNull(
                paragraphs.paragraphs.firstOrNull { it.sectionKey == section.sectionKey }
            )
            val coherent = requireNotNull(
                draft.paragraphs.firstOrNull {
                    it.paragraphFingerprint == paragraph.fingerprint
                }
            )
            val refs = citationKeysByParagraph
                .getValue(coherent.paragraphFingerprint)
                .joinToString(separator = "") { key ->
                    "[" + citationIndex.getValue(key) + "]"
                }
            layout.addParagraph(coherent.renderedText + refs)
        }

        layout.addHeading(referencesHeadingB445(goal))
        citationKeys.forEach { key ->
            layout.addReference(citationIndex.getValue(key), key)
        }

        val pageStreams = layout.renderPageStreams()
        val pdfBytes = buildPdfB445(
            pageStreams = pageStreams,
            goal = goal,
        )
        val contentSha = sha256B445(pdfBytes)
        val renderedClaims = citations.coveredClaimIds.distinct().sorted()

        return PdfArtifact(
            documentGoalFingerprint = goal.fingerprint,
            structureFingerprint = structure.fingerprint,
            draftFingerprint = draft.fingerprint,
            citationPlanFingerprint = citations.fingerprint,
            factualValidationFingerprint = factualValidation.fingerprint,
            revisionReportFingerprint = revisionReport.fingerprint,
            pageCount = pageStreams.size,
            renderedClaimIds = renderedClaims,
            citationEvidenceStableKeys = citationKeys,
            payload = pdfBytes,
            contentSha256 = contentSha,
            fingerprint = pdfArtifactFingerprint(
                goal.fingerprint,
                structure.fingerprint,
                draft.fingerprint,
                citations.fingerprint,
                factualValidation.fingerprint,
                revisionReport.fingerprint,
                pageStreams.size,
                renderedClaims,
                citationKeys,
                contentSha,
            ),
        )
    }
}

private data class PdfTextLineB445(
    val fontResource: String,
    val fontSize: Int,
    val x: Int,
    val y: Int,
    val text: String,
)

private class PdfLayoutBuilder(
    private val goal: DocumentGoal,
) {
    private val pages = mutableListOf(mutableListOf<PdfTextLineB445>())
    private var y = PAGE_TOP_B445

    fun addHeading(text: String) {
        ensureWinAnsi(text, "B445 heading")
        ensureSpace(HEADING_LINE_HEIGHT_B445 + PARAGRAPH_SPACING_B445)
        pages.last() += PdfTextLineB445(
            fontResource = "F2",
            fontSize = HEADING_FONT_SIZE_B445,
            x = PAGE_LEFT_B445,
            y = y,
            text = text,
        )
        y -= HEADING_LINE_HEIGHT_B445
    }

    fun addParagraph(text: String) {
        addWrapped(
            text = text,
            fontResource = "F1",
            fontSize = BODY_FONT_SIZE_B445,
            maxChars = BODY_MAX_CHARS_B445,
            lineHeight = BODY_LINE_HEIGHT_B445,
        )
        y -= PARAGRAPH_SPACING_B445
    }

    fun addReference(index: Int, key: String) {
        addWrapped(
            text = index.toString() + ". " + key,
            fontResource = "F1",
            fontSize = REFERENCE_FONT_SIZE_B445,
            maxChars = REFERENCE_MAX_CHARS_B445,
            lineHeight = REFERENCE_LINE_HEIGHT_B445,
        )
        y -= REFERENCE_SPACING_B445
    }

    private fun addWrapped(
        text: String,
        fontResource: String,
        fontSize: Int,
        maxChars: Int,
        lineHeight: Int,
    ) {
        ensureWinAnsi(text, "B445 rendered line")
        val lines = wrapTextB445(text, maxChars)
        lines.forEach { line ->
            ensureSpace(lineHeight)
            pages.last() += PdfTextLineB445(
                fontResource = fontResource,
                fontSize = fontSize,
                x = PAGE_LEFT_B445,
                y = y,
                text = line,
            )
            y -= lineHeight
        }
    }

    private fun ensureSpace(required: Int) {
        if (y - required < PAGE_BOTTOM_B445) {
            pages.add(mutableListOf())
            y = PAGE_TOP_B445
        }
    }

    fun renderPageStreams(): List<ByteArray> =
        pages.map { lines ->
            buildString {
                lines.forEach { line ->
                    append("BT /")
                    append(line.fontResource)
                    append(' ')
                    append(line.fontSize)
                    append(" Tf 1 0 0 1 ")
                    append(line.x)
                    append(' ')
                    append(line.y)
                    append(" Tm (")
                    append(escapePdfLiteralB445(line.text))
                    append(") Tj ET\n")
                }
            }.toByteArray(WIN_ANSI_B445)
        }
}

private fun wrapTextB445(
    text: String,
    maxChars: Int,
): List<String> {
    require(maxChars > 8)
    require('\r' !in text && '\n' !in text) {
        "B445 expects paragraph carriers without embedded line breaks"
    }
    if (text.length <= maxChars) return listOf(text)

    val result = mutableListOf<String>()
    var remaining = text
    while (remaining.length > maxChars) {
        var cut = remaining.lastIndexOf(' ', maxChars)
        if (cut <= 0) cut = maxChars
        val line = remaining.substring(0, cut).trimEnd()
        require(line.isNotEmpty())
        result += line
        remaining = remaining.substring(cut).trimStart()
    }
    if (remaining.isNotEmpty()) result += remaining
    return result
}

private fun buildPdfB445(
    pageStreams: List<ByteArray>,
    goal: DocumentGoal,
): ByteArray {
    require(pageStreams.isNotEmpty())

    val pageObjectIds = pageStreams.indices.map { index -> 6 + index * 2 }
    val contentObjectIds = pageStreams.indices.map { index -> 7 + index * 2 }
    val maxObjectId = contentObjectIds.last()

    val objects = linkedMapOf<Int, ByteArray>()
    objects[1] = asciiB445("<< /Type /Catalog /Pages 2 0 R >>")
    objects[2] = asciiB445(
        "<< /Type /Pages /Kids [" +
            pageObjectIds.joinToString(" ") { id -> id.toString() + " 0 R" } +
            "] /Count " + pageStreams.size + " >>"
    )
    objects[3] = asciiB445(
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
    )
    objects[4] = asciiB445(
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"
    )

    val subject = "purpose=" + goal.purpose.name +
        "; audience=" + goal.audience +
        "; language=" + goal.languageTag
    ensureWinAnsi(subject, "B445 PDF metadata")
    objects[5] = (
        "<< /Title (LIFEOS PDF) " +
            "/Author (LIFEOS) " +
            "/Producer (LIFEOS B445) " +
            "/Subject (" + escapePdfLiteralB445(subject) + ") " +
            "/Keywords (documentGoal=" + goal.fingerprint + ") " +
            "/CreationDate (D:20000101000000Z) " +
            "/ModDate (D:20000101000000Z) >>"
        ).toByteArray(WIN_ANSI_B445)

    pageStreams.forEachIndexed { index, stream ->
        val pageId = pageObjectIds[index]
        val contentId = contentObjectIds[index]
        objects[pageId] = asciiB445(
            "<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 595 842] " +
                "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> " +
                "/Contents " + contentId + " 0 R >>"
        )
        val header = asciiB445("<< /Length " + stream.size + " >>\nstream\n")
        val footer = asciiB445("\nendstream")
        objects[contentId] = header + stream + footer
    }

    val out = ByteArrayOutputStream()
    out.write(asciiB445("%PDF-1.7\n"))
    out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

    val offsets = IntArray(maxObjectId + 1)
    for (id in 1..maxObjectId) {
        val body = requireNotNull(objects[id])
        offsets[id] = out.size()
        out.write(asciiB445(id.toString() + " 0 obj\n"))
        out.write(body)
        out.write(asciiB445("\nendobj\n"))
    }

    val xrefOffset = out.size()
    out.write(asciiB445("xref\n0 " + (maxObjectId + 1) + "\n"))
    out.write(asciiB445("0000000000 65535 f \n"))
    for (id in 1..maxObjectId) {
        out.write(
            asciiB445(
                String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id])
            )
        )
    }
    out.write(
        asciiB445(
            "trailer\n<< /Size " + (maxObjectId + 1) +
                " /Root 1 0 R /Info 5 0 R >>\n" +
                "startxref\n" + xrefOffset + "\n%%EOF\n"
        )
    )
    return out.toByteArray()
}

private fun sectionHeadingB445(
    goal: DocumentGoal,
    ordinal: Int,
): String =
    if (goal.languageTag.startsWith("de")) {
        "Abschnitt " + (ordinal + 1)
    } else {
        "Section " + (ordinal + 1)
    }

private fun referencesHeadingB445(goal: DocumentGoal): String =
    if (goal.languageTag.startsWith("de")) "Quellen" else "References"

private fun ensureWinAnsi(
    value: String,
    label: String,
) {
    require(WIN_ANSI_B445.newEncoder().canEncode(value)) {
        label + " contains glyphs not representable by the deterministic B445 WinAnsi font profile"
    }
}

private fun escapePdfLiteralB445(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '(' -> append("\\(")
            ')' -> append("\\)")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
}

private fun asciiB445(value: String): ByteArray =
    value.toByteArray(Charsets.US_ASCII)

private fun pdfArtifactFingerprint(
    documentGoalFingerprint: String,
    structureFingerprint: String,
    draftFingerprint: String,
    citationPlanFingerprint: String,
    factualValidationFingerprint: String,
    revisionReportFingerprint: String,
    pageCount: Int,
    renderedClaimIds: List<String>,
    citationEvidenceStableKeys: List<String>,
    contentSha256: String,
): String = StableCognitiveIds.fingerprint(
    "pdf-artifact/v1",
    documentGoalFingerprint,
    structureFingerprint,
    draftFingerprint,
    citationPlanFingerprint,
    factualValidationFingerprint,
    revisionReportFingerprint,
    pageCount.toString(),
    renderedClaimIds.joinToString("\u001f"),
    citationEvidenceStableKeys.joinToString("\u001f"),
    contentSha256,
)

private fun sha256B445(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private val WIN_ANSI_B445: Charset = Charset.forName("windows-1252")
private val SHA_256_B445 = Regex("[0-9a-f]{64}")
private const val PAGE_LEFT_B445 = 56
private const val PAGE_TOP_B445 = 786
private const val PAGE_BOTTOM_B445 = 56
private const val HEADING_FONT_SIZE_B445 = 16
private const val BODY_FONT_SIZE_B445 = 11
private const val REFERENCE_FONT_SIZE_B445 = 9
private const val HEADING_LINE_HEIGHT_B445 = 24
private const val BODY_LINE_HEIGHT_B445 = 15
private const val REFERENCE_LINE_HEIGHT_B445 = 12
private const val PARAGRAPH_SPACING_B445 = 10
private const val REFERENCE_SPACING_B445 = 4
private const val BODY_MAX_CHARS_B445 = 82
private const val REFERENCE_MAX_CHARS_B445 = 92
