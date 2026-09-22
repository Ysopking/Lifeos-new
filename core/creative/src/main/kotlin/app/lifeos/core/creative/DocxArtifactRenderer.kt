package app.lifeos.core.creative

import app.lifeos.core.model.DocumentGoal
import app.lifeos.core.model.DocumentOutputFormat
import app.lifeos.core.model.DocumentStructurePlan
import app.lifeos.core.model.StableCognitiveIds
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class DocxTableAsset private constructor(
    val sectionKey: String,
    val ordinal: Int,
    val sourceArtifactFingerprint: String,
    val claimIds: List<String>,
    val evidenceStableKeys: List<String>,
    val rows: List<List<String>>,
    val fingerprint: String,
) {
    init {
        require(sectionKey.isNotBlank())
        require(ordinal >= 0)
        require(sourceArtifactFingerprint.matches(SHA_256_B444))
        require(claimIds.isNotEmpty())
        require(claimIds == claimIds.distinct().sorted())
        require(evidenceStableKeys.isNotEmpty())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(rows.isNotEmpty())
        require(rows.first().isNotEmpty())
        require(rows.size <= MAX_DOCX_TABLE_ROWS)
        require(rows.first().size <= MAX_DOCX_TABLE_COLUMNS)
        require(rows.all { it.size == rows.first().size })
        require(rows.flatten().all { it.length <= MAX_DOCX_CELL_CHARS })
        require(
            fingerprint == docxTableFingerprint(
                sectionKey,
                ordinal,
                sourceArtifactFingerprint,
                claimIds,
                evidenceStableKeys,
                rows,
            )
        )
    }

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false

    companion object {
        fun create(
            sectionKey: String,
            ordinal: Int,
            sourceArtifactFingerprint: String,
            claimIds: Collection<String>,
            evidenceStableKeys: Collection<String>,
            rows: Collection<Collection<String>>,
        ): DocxTableAsset {
            val canonicalClaims = claimIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            val canonicalEvidence = evidenceStableKeys
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            val canonicalRows = rows.map { row ->
                row.map { cell -> cell.trim() }
            }
            return DocxTableAsset(
                sectionKey = sectionKey.trim(),
                ordinal = ordinal,
                sourceArtifactFingerprint = sourceArtifactFingerprint,
                claimIds = canonicalClaims,
                evidenceStableKeys = canonicalEvidence,
                rows = canonicalRows,
                fingerprint = docxTableFingerprint(
                    sectionKey.trim(),
                    ordinal,
                    sourceArtifactFingerprint,
                    canonicalClaims,
                    canonicalEvidence,
                    canonicalRows,
                ),
            )
        }
    }
}

class DocxImageAsset private constructor(
    val sectionKey: String,
    val ordinal: Int,
    val sourceArtifactFingerprint: String,
    val claimIds: List<String>,
    val evidenceStableKeys: List<String>,
    val mediaType: String,
    val altText: String,
    val widthPx: Int,
    val heightPx: Int,
    payload: ByteArray,
    val payloadSha256: String,
    val fingerprint: String,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(sectionKey.isNotBlank())
        require(ordinal >= 0)
        require(sourceArtifactFingerprint.matches(SHA_256_B444))
        require(claimIds.isNotEmpty())
        require(claimIds == claimIds.distinct().sorted())
        require(evidenceStableKeys.isNotEmpty())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(mediaType == "image/png" || mediaType == "image/jpeg")
        require(altText.length <= MAX_DOCX_ALT_TEXT_CHARS)
        require(widthPx in 1..MAX_DOCX_IMAGE_DIMENSION_PX)
        require(heightPx in 1..MAX_DOCX_IMAGE_DIMENSION_PX)
        require(payloadBytes.isNotEmpty())
        require(payloadBytes.size <= MAX_DOCX_IMAGE_BYTES)
        require(payloadSha256 == sha256(payloadBytes))
        require(
            fingerprint == docxImageFingerprint(
                sectionKey,
                ordinal,
                sourceArtifactFingerprint,
                claimIds,
                evidenceStableKeys,
                mediaType,
                altText,
                widthPx,
                heightPx,
                payloadSha256,
            )
        )
    }

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false

    companion object {
        fun create(
            sectionKey: String,
            ordinal: Int,
            sourceArtifactFingerprint: String,
            claimIds: Collection<String>,
            evidenceStableKeys: Collection<String>,
            mediaType: String,
            altText: String,
            widthPx: Int,
            heightPx: Int,
            payload: ByteArray,
        ): DocxImageAsset {
            val canonicalClaims = claimIds
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            val canonicalEvidence = evidenceStableKeys
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            val payloadHash = sha256(payload)
            return DocxImageAsset(
                sectionKey = sectionKey.trim(),
                ordinal = ordinal,
                sourceArtifactFingerprint = sourceArtifactFingerprint,
                claimIds = canonicalClaims,
                evidenceStableKeys = canonicalEvidence,
                mediaType = mediaType,
                altText = altText.trim(),
                widthPx = widthPx,
                heightPx = heightPx,
                payload = payload,
                payloadSha256 = payloadHash,
                fingerprint = docxImageFingerprint(
                    sectionKey.trim(),
                    ordinal,
                    sourceArtifactFingerprint,
                    canonicalClaims,
                    canonicalEvidence,
                    mediaType,
                    altText.trim(),
                    widthPx,
                    heightPx,
                    payloadHash,
                ),
            )
        }
    }
}

class DocxArtifact internal constructor(
    val documentGoalFingerprint: String,
    val structureFingerprint: String,
    val draftFingerprint: String,
    val citationPlanFingerprint: String,
    val factualValidationFingerprint: String,
    val revisionReportFingerprint: String,
    val renderedClaimIds: List<String>,
    val citationEvidenceStableKeys: List<String>,
    val embeddedTableFingerprints: List<String>,
    val embeddedImageFingerprints: List<String>,
    payload: ByteArray,
    val contentSha256: String,
    val fingerprint: String,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(documentGoalFingerprint.matches(SHA_256_B444))
        require(structureFingerprint.matches(SHA_256_B444))
        require(draftFingerprint.matches(SHA_256_B444))
        require(citationPlanFingerprint.matches(SHA_256_B444))
        require(factualValidationFingerprint.matches(SHA_256_B444))
        require(revisionReportFingerprint.matches(SHA_256_B444))
        require(renderedClaimIds.isNotEmpty())
        require(renderedClaimIds == renderedClaimIds.distinct().sorted())
        require(citationEvidenceStableKeys.isNotEmpty())
        require(citationEvidenceStableKeys == citationEvidenceStableKeys.distinct())
        require(embeddedTableFingerprints == embeddedTableFingerprints.distinct().sorted())
        require(embeddedImageFingerprints == embeddedImageFingerprints.distinct().sorted())
        require(payloadBytes.isNotEmpty())
        require(contentSha256 == sha256(payloadBytes))
        require(
            fingerprint == docxArtifactFingerprint(
                documentGoalFingerprint,
                structureFingerprint,
                draftFingerprint,
                citationPlanFingerprint,
                factualValidationFingerprint,
                revisionReportFingerprint,
                renderedClaimIds,
                citationEvidenceStableKeys,
                embeddedTableFingerprints,
                embeddedImageFingerprints,
                contentSha256,
            )
        )
    }

    val mediaType: String
        get() = DOCX_MEDIA_TYPE

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val citationAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
}

/**
 * B444 renders an already-converged B431-B440 document as a real OOXML DOCX package.
 *
 * The renderer preserves exact draft/citation lineage. Optional tables and images are accepted
 * only when they are bound to existing section claims, existing B437 evidence keys and an exact
 * source-artifact fingerprint. Packaging adds presentation only; it cannot create claims, choose
 * evidence, finalize an artifact or publish it.
 */
class DocxArtifactRenderer {
    fun render(
        goal: DocumentGoal,
        structure: DocumentStructurePlan,
        paragraphs: ParagraphComposition,
        draft: CoherentDocumentDraft,
        citations: CitationBindingPlan,
        factualValidation: FactualDraftValidationReport,
        revisionReport: IterativeDraftRevisionReport,
        tables: Collection<DocxTableAsset> = emptyList(),
        images: Collection<DocxImageAsset> = emptyList(),
    ): DocxArtifact {
        require(goal.outputFormat == DocumentOutputFormat.DOCX) {
            "B444 accepts DOCX DocumentGoal only"
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
            "B444 refuses a draft that failed B438 factual closure"
        }
        require(revisionReport.state == DraftRevisionLoopState.CONVERGED) {
            "B444 requires B440 convergence"
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
            "B444 requires a clean final B439 critique"
        }
        require(
            draft.paragraphs.map { it.paragraphFingerprint } ==
                paragraphs.paragraphs.map { it.fingerprint }
        ) {
            "B444 draft/paragraph lineage mismatch"
        }
        require(citations.coveredClaimIds == paragraphs.claimIds.sorted()) {
            "B444 requires exact citation coverage for rendered claims"
        }

        val sectionByKey = structure.sections.associateBy { it.sectionKey }
        val paragraphByFingerprint = paragraphs.paragraphs.associateBy { it.fingerprint }
        val bindingsByParagraph = citations.bindings.groupBy { it.paragraphFingerprint }

        val citationKeysByParagraph = draft.paragraphs.associate { coherent ->
            val source = requireNotNull(paragraphByFingerprint[coherent.paragraphFingerprint]) {
                "B444 coherent paragraph missing exact B435 source"
            }
            val bindings = bindingsByParagraph[source.fingerprint].orEmpty()
            require(bindings.isNotEmpty()) {
                "B444 refuses an uncited rendered paragraph"
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

        val canonicalTables = tables
            .sortedWith(compareBy<DocxTableAsset>({ sectionByKey[it.sectionKey]?.ordinal ?: Int.MAX_VALUE }, { it.ordinal }, { it.fingerprint }))
        val canonicalImages = images
            .sortedWith(compareBy<DocxImageAsset>({ sectionByKey[it.sectionKey]?.ordinal ?: Int.MAX_VALUE }, { it.ordinal }, { it.fingerprint }))

        require(canonicalTables.map { it.sectionKey to it.ordinal }.distinct().size == canonicalTables.size) {
            "B444 table ordinals must be unique within each section"
        }
        require(canonicalImages.map { it.sectionKey to it.ordinal }.distinct().size == canonicalImages.size) {
            "B444 image ordinals must be unique within each section"
        }

        canonicalTables.forEach { table ->
            val section = requireNotNull(sectionByKey[table.sectionKey]) {
                "B444 table references unknown section"
            }
            require(section.claimIds.containsAll(table.claimIds)) {
                "B444 table claim lineage escapes its section"
            }
            require(citationKeys.containsAll(table.evidenceStableKeys)) {
                "B444 table evidence is not bound by B437 citations"
            }
        }
        canonicalImages.forEach { image ->
            val section = requireNotNull(sectionByKey[image.sectionKey]) {
                "B444 image references unknown section"
            }
            require(section.claimIds.containsAll(image.claimIds)) {
                "B444 image claim lineage escapes its section"
            }
            require(citationKeys.containsAll(image.evidenceStableKeys)) {
                "B444 image evidence is not bound by B437 citations"
            }
        }

        val packageBytes = buildDocx(
            goal = goal,
            structure = structure,
            paragraphs = paragraphs,
            draft = draft,
            citationKeysByParagraph = citationKeysByParagraph,
            citationIndex = citationIndex,
            citationKeys = citationKeys,
            tables = canonicalTables,
            images = canonicalImages,
        )
        val contentSha = sha256(packageBytes)
        val renderedClaims = citations.coveredClaimIds.distinct().sorted()
        val tableFingerprints = canonicalTables.map { it.fingerprint }.distinct().sorted()
        val imageFingerprints = canonicalImages.map { it.fingerprint }.distinct().sorted()

        return DocxArtifact(
            documentGoalFingerprint = goal.fingerprint,
            structureFingerprint = structure.fingerprint,
            draftFingerprint = draft.fingerprint,
            citationPlanFingerprint = citations.fingerprint,
            factualValidationFingerprint = factualValidation.fingerprint,
            revisionReportFingerprint = revisionReport.fingerprint,
            renderedClaimIds = renderedClaims,
            citationEvidenceStableKeys = citationKeys,
            embeddedTableFingerprints = tableFingerprints,
            embeddedImageFingerprints = imageFingerprints,
            payload = packageBytes,
            contentSha256 = contentSha,
            fingerprint = docxArtifactFingerprint(
                goal.fingerprint,
                structure.fingerprint,
                draft.fingerprint,
                citations.fingerprint,
                factualValidation.fingerprint,
                revisionReport.fingerprint,
                renderedClaims,
                citationKeys,
                tableFingerprints,
                imageFingerprints,
                contentSha,
            ),
        )
    }

    private fun buildDocx(
        goal: DocumentGoal,
        structure: DocumentStructurePlan,
        paragraphs: ParagraphComposition,
        draft: CoherentDocumentDraft,
        citationKeysByParagraph: Map<String, List<String>>,
        citationIndex: Map<String, Int>,
        citationKeys: List<String>,
        tables: List<DocxTableAsset>,
        images: List<DocxImageAsset>,
    ): ByteArray {
        val paragraphBySection = paragraphs.paragraphs.associateBy { it.sectionKey }
        val coherentByParagraphFingerprint = draft.paragraphs.associateBy { it.paragraphFingerprint }
        val tablesBySection = tables.groupBy { it.sectionKey }
        val imagesBySection = images.groupBy { it.sectionKey }
        val imageIndex = images.mapIndexed { index, image -> image.fingerprint to index + 1 }.toMap()

        val documentXml = buildString {
            append(XML_HEADER)
            append("<w:document")
            append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
            append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
            append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
            append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
            append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
            append("<w:body>")

            structure.sections.sortedBy { it.ordinal }.forEach { section ->
                append(headingParagraph(sectionHeading(goal, section.ordinal), 1))

                val sourceParagraph = requireNotNull(paragraphBySection[section.sectionKey])
                val coherent = requireNotNull(
                    coherentByParagraphFingerprint[sourceParagraph.fingerprint]
                )
                append("<w:p>")
                append(textRun(coherent.renderedText))
                citationKeysByParagraph
                    .getValue(coherent.paragraphFingerprint)
                    .forEach { key ->
                        append(superscriptRun("[" + citationIndex.getValue(key) + "]"))
                    }
                append("</w:p>")

                tablesBySection[section.sectionKey].orEmpty()
                    .sortedBy { it.ordinal }
                    .forEach { table ->
                        append(tableXml(table))
                    }

                imagesBySection[section.sectionKey].orEmpty()
                    .sortedBy { it.ordinal }
                    .forEach { image ->
                        val idx = imageIndex.getValue(image.fingerprint)
                        append(imageParagraphXml(image, idx))
                    }
            }

            append(headingParagraph(referencesHeading(goal), 1))
            citationKeys.forEach { key ->
                val index = citationIndex.getValue(key)
                append("<w:p>")
                append(textRun(index.toString() + ". " + key))
                append("</w:p>")
            }

            append(
                "<w:sectPr>" +
                    "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
                    "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" " +
                    "w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>" +
                    "</w:sectPr>"
            )
            append("</w:body></w:document>")
        }

        val rels = buildString {
            append(XML_HEADER)
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append(
                "<Relationship Id=\"rIdStyles\" " +
                    "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" " +
                    "Target=\"styles.xml\"/>"
            )
            images.forEachIndexed { index, image ->
                val extension = imageExtension(image.mediaType)
                append(
                    "<Relationship Id=\"rIdImage" + (index + 1) + "\" " +
                        "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" " +
                        "Target=\"media/image" + (index + 1) + "." + extension + "\"/>"
                )
            }
            append("</Relationships>")
        }

        val contentTypes = buildString {
            append(XML_HEADER)
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            if (images.any { it.mediaType == "image/png" }) {
                append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
            }
            if (images.any { it.mediaType == "image/jpeg" }) {
                append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
            }
            append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
            append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
            append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
            append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
            append("</Types>")
        }

        val rootRels = XML_HEADER +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>"

        val styles = XML_HEADER +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">" +
            "<w:name w:val=\"Normal\"/><w:qFormat/><w:rPr><w:sz w:val=\"22\"/></w:rPr>" +
            "</w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\">" +
            "<w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/>" +
            "<w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/></w:pPr>" +
            "<w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr>" +
            "</w:style>" +
            "</w:styles>"

        val core = XML_HEADER +
            "<cp:coreProperties " +
            "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>LIFEOS document</dc:title>" +
            "<dc:creator>LIFEOS</dc:creator>" +
            "<cp:lastModifiedBy>LIFEOS</cp:lastModifiedBy>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + FIXED_DOCX_TIMESTAMP + "</dcterms:created>" +
            "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + FIXED_DOCX_TIMESTAMP + "</dcterms:modified>" +
            "</cp:coreProperties>"

        val app = XML_HEADER +
            "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
            "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
            "<Application>LIFEOS</Application><AppVersion>1.0</AppVersion>" +
            "</Properties>"

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeZipEntry(zip, "[Content_Types].xml", contentTypes.toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "_rels/.rels", rootRels.toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "docProps/core.xml", core.toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "docProps/app.xml", app.toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "word/document.xml", documentXml.toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "word/styles.xml", styles.toByteArray(StandardCharsets.UTF_8))
            writeZipEntry(zip, "word/_rels/document.xml.rels", rels.toByteArray(StandardCharsets.UTF_8))
            images.forEachIndexed { index, image ->
                writeZipEntry(
                    zip,
                    "word/media/image" + (index + 1) + "." + imageExtension(image.mediaType),
                    image.payload,
                )
            }
        }
        return output.toByteArray()
    }
}

private fun sectionHeading(goal: DocumentGoal, ordinal: Int): String =
    if (goal.languageTag.startsWith("de")) {
        "Abschnitt " + (ordinal + 1)
    } else {
        "Section " + (ordinal + 1)
    }

private fun referencesHeading(goal: DocumentGoal): String =
    if (goal.languageTag.startsWith("de")) "Quellen" else "References"

private fun headingParagraph(text: String, level: Int): String =
    "<w:p><w:pPr><w:pStyle w:val=\"Heading" + level + "\"/></w:pPr>" +
        textRun(text) + "</w:p>"

private fun textRun(text: String): String =
    "<w:r><w:t xml:space=\"preserve\">" + escapeXml(text) + "</w:t></w:r>"

private fun superscriptRun(text: String): String =
    "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>" +
        "<w:t>" + escapeXml(text) + "</w:t></w:r>"

private fun tableXml(table: DocxTableAsset): String = buildString {
    append("<w:tbl>")
    append("<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>")
    append("<w:tblBorders>")
    append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
    append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
    append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
    append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
    append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
    append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
    append("</w:tblBorders></w:tblPr>")
    table.rows.forEachIndexed { rowIndex, row ->
        append("<w:tr>")
        if (rowIndex == 0) {
            append("<w:trPr><w:tblHeader/></w:trPr>")
        }
        row.forEach { cell ->
            append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
            append("<w:p>")
            if (rowIndex == 0) {
                append("<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">")
                append(escapeXml(cell))
                append("</w:t></w:r>")
            } else {
                append(textRun(cell))
            }
            append("</w:p></w:tc>")
        }
        append("</w:tr>")
    }
    append("</w:tbl>")
}

private fun imageParagraphXml(image: DocxImageAsset, index: Int): String {
    val cx = image.widthPx.toLong() * EMU_PER_PIXEL
    val cy = image.heightPx.toLong() * EMU_PER_PIXEL
    val relId = "rIdImage" + index
    val docPrId = index + 10
    return buildString {
        append("<w:p><w:r><w:drawing>")
        append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        append("<wp:extent cx=\"")
        append(cx)
        append("\" cy=\"")
        append(cy)
        append("\"/>")
        append("<wp:docPr id=\"")
        append(docPrId)
        append("\" name=\"Image ")
        append(index)
        append("\" descr=\"")
        append(escapeXml(image.altText))
        append("\"/>")
        append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        append("<pic:pic>")
        append("<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"Image ")
        append(index)
        append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
        append("<pic:blipFill><a:blip r:embed=\"")
        append(relId)
        append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
        append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
        append(cx)
        append("\" cy=\"")
        append(cy)
        append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
        append("</pic:pic></a:graphicData></a:graphic>")
        append("</wp:inline></w:drawing></w:r></w:p>")
    }
}

private fun imageExtension(mediaType: String): String =
    when (mediaType) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        else -> error("unsupported B444 image type")
    }

private fun writeZipEntry(
    zip: ZipOutputStream,
    name: String,
    bytes: ByteArray,
) {
    val entry = ZipEntry(name)
    entry.time = 0L
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
}

private fun escapeXml(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (char.code >= 0x20 || char == '\n' || char == '\t') append(char)
        }
    }
}

private fun docxTableFingerprint(
    sectionKey: String,
    ordinal: Int,
    sourceArtifactFingerprint: String,
    claimIds: List<String>,
    evidenceStableKeys: List<String>,
    rows: List<List<String>>,
): String = StableCognitiveIds.fingerprint(
    "docx-table-asset/v1",
    sectionKey,
    ordinal.toString(),
    sourceArtifactFingerprint,
    claimIds.joinToString("\u001f"),
    evidenceStableKeys.joinToString("\u001f"),
    rows.joinToString("\u001e") { row -> row.joinToString("\u001f") },
)

private fun docxImageFingerprint(
    sectionKey: String,
    ordinal: Int,
    sourceArtifactFingerprint: String,
    claimIds: List<String>,
    evidenceStableKeys: List<String>,
    mediaType: String,
    altText: String,
    widthPx: Int,
    heightPx: Int,
    payloadSha256: String,
): String = StableCognitiveIds.fingerprint(
    "docx-image-asset/v1",
    sectionKey,
    ordinal.toString(),
    sourceArtifactFingerprint,
    claimIds.joinToString("\u001f"),
    evidenceStableKeys.joinToString("\u001f"),
    mediaType,
    altText,
    widthPx.toString(),
    heightPx.toString(),
    payloadSha256,
)

private fun docxArtifactFingerprint(
    documentGoalFingerprint: String,
    structureFingerprint: String,
    draftFingerprint: String,
    citationPlanFingerprint: String,
    factualValidationFingerprint: String,
    revisionReportFingerprint: String,
    renderedClaimIds: List<String>,
    citationEvidenceStableKeys: List<String>,
    embeddedTableFingerprints: List<String>,
    embeddedImageFingerprints: List<String>,
    contentSha256: String,
): String = StableCognitiveIds.fingerprint(
    "docx-artifact/v1",
    documentGoalFingerprint,
    structureFingerprint,
    draftFingerprint,
    citationPlanFingerprint,
    factualValidationFingerprint,
    revisionReportFingerprint,
    renderedClaimIds.joinToString("\u001f"),
    citationEvidenceStableKeys.joinToString("\u001f"),
    embeddedTableFingerprints.joinToString("\u001f"),
    embeddedImageFingerprints.joinToString("\u001f"),
    contentSha256,
)

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val DOCX_MEDIA_TYPE =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val XML_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val FIXED_DOCX_TIMESTAMP = "2000-01-01T00:00:00Z"
private const val EMU_PER_PIXEL = 9525L
private const val MAX_DOCX_TABLE_ROWS = 512
private const val MAX_DOCX_TABLE_COLUMNS = 64
private const val MAX_DOCX_CELL_CHARS = 16_384
private const val MAX_DOCX_ALT_TEXT_CHARS = 1024
private const val MAX_DOCX_IMAGE_DIMENSION_PX = 8192
private const val MAX_DOCX_IMAGE_BYTES = 8 * 1024 * 1024
private val SHA_256_B444 = Regex("[0-9a-f]{64}")
