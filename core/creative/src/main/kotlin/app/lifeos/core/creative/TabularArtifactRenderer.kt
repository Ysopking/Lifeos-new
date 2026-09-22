package app.lifeos.core.creative

import app.lifeos.core.model.SemanticArtifactPlan
import app.lifeos.core.model.StableCognitiveIds
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class TabularOutputFormat {
    CSV,
    XLSX,
}

enum class TabularCellKind {
    HEADER,
    TEXT,
    DECIMAL,
    FORMULA,
    BLANK,
}

data class TabularCell private constructor(
    val row: Int,
    val column: Int,
    val kind: TabularCellKind,
    val value: String,
    val claimId: String?,
    val evidenceStableKeys: List<String>,
    val fingerprint: String,
) {
    init {
        require(row in 1..MAX_TABULAR_ROWS_B446)
        require(column in 1..MAX_TABULAR_COLUMNS_B446)
        require(value.length <= MAX_TABULAR_CELL_CHARS_B446)
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        when (kind) {
            TabularCellKind.HEADER -> {
                require(value.isNotBlank())
                require(claimId == null)
                require(evidenceStableKeys.isEmpty())
            }
            TabularCellKind.TEXT -> {
                require(claimId?.isNotBlank() == true)
                require(evidenceStableKeys.isNotEmpty())
            }
            TabularCellKind.DECIMAL -> {
                require(claimId?.isNotBlank() == true)
                require(evidenceStableKeys.isNotEmpty())
                require(value.matches(DECIMAL_REGEX_B446))
            }
            TabularCellKind.FORMULA -> {
                require(claimId == null)
                require(evidenceStableKeys.isEmpty())
                require(isSafeFormulaB446(value))
            }
            TabularCellKind.BLANK -> {
                require(value.isEmpty())
                require(claimId == null)
                require(evidenceStableKeys.isEmpty())
            }
        }
        require(
            fingerprint == tabularCellFingerprint(
                row,
                column,
                kind,
                value,
                claimId,
                evidenceStableKeys,
            )
        )
    }

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val formulaExecutionAuthority: Boolean get() = false

    companion object {
        fun header(
            row: Int,
            column: Int,
            value: String,
        ): TabularCell =
            create(row, column, TabularCellKind.HEADER, value.trim(), null, emptyList())

        fun text(
            row: Int,
            column: Int,
            value: String,
            claimId: String,
            evidenceStableKeys: Collection<String>,
        ): TabularCell =
            create(
                row,
                column,
                TabularCellKind.TEXT,
                value,
                claimId.trim(),
                evidenceStableKeys,
            )

        fun decimal(
            row: Int,
            column: Int,
            value: String,
            claimId: String,
            evidenceStableKeys: Collection<String>,
        ): TabularCell =
            create(
                row,
                column,
                TabularCellKind.DECIMAL,
                canonicalDecimalB446(value),
                claimId.trim(),
                evidenceStableKeys,
            )

        fun formula(
            row: Int,
            column: Int,
            expression: String,
        ): TabularCell =
            create(
                row,
                column,
                TabularCellKind.FORMULA,
                expression.trim().uppercase(Locale.ROOT),
                null,
                emptyList(),
            )

        fun blank(
            row: Int,
            column: Int,
        ): TabularCell =
            create(row, column, TabularCellKind.BLANK, "", null, emptyList())

        private fun create(
            row: Int,
            column: Int,
            kind: TabularCellKind,
            value: String,
            claimId: String?,
            evidenceStableKeys: Collection<String>,
        ): TabularCell {
            val evidence = evidenceStableKeys
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .sorted()
            return TabularCell(
                row = row,
                column = column,
                kind = kind,
                value = value,
                claimId = claimId,
                evidenceStableKeys = evidence,
                fingerprint = tabularCellFingerprint(
                    row,
                    column,
                    kind,
                    value,
                    claimId,
                    evidence,
                ),
            )
        }
    }
}

data class TabularSheetPlan private constructor(
    val name: String,
    val cells: List<TabularCell>,
    val fingerprint: String,
) {
    init {
        require(isValidSheetNameB446(name))
        require(cells.isNotEmpty())
        require(cells == cells.sortedWith(compareBy<TabularCell>({ it.row }, { it.column })))
        require(cells.map { it.row to it.column }.distinct().size == cells.size)
        require(
            fingerprint == tabularSheetFingerprint(
                name,
                cells,
            )
        )
    }

    val maxRow: Int
        get() = cells.maxOf { it.row }

    val maxColumn: Int
        get() = cells.maxOf { it.column }

    companion object {
        fun create(
            name: String,
            cells: Collection<TabularCell>,
        ): TabularSheetPlan {
            val canonical = cells
                .distinctBy { it.row to it.column }
                .sortedWith(compareBy<TabularCell>({ it.row }, { it.column }))
            require(canonical.size == cells.size) {
                "B446 sheet cell coordinates must be unique"
            }
            val normalizedName = name.trim()
            return TabularSheetPlan(
                name = normalizedName,
                cells = canonical,
                fingerprint = tabularSheetFingerprint(
                    normalizedName,
                    canonical,
                ),
            )
        }
    }
}

data class TabularArtifactPlan private constructor(
    val semanticPlanFingerprint: String,
    val sourceWorldRevision: Long,
    val sheets: List<TabularSheetPlan>,
    val fingerprint: String,
) {
    init {
        require(semanticPlanFingerprint.matches(SHA_256_B446))
        require(sourceWorldRevision >= 0L)
        require(sheets.isNotEmpty())
        require(sheets == sheets.sortedBy { it.name })
        require(sheets.map { it.name.lowercase() }.distinct().size == sheets.size)
        require(
            fingerprint == tabularPlanFingerprint(
                semanticPlanFingerprint,
                sourceWorldRevision,
                sheets,
            )
        )
    }

    val truthAuthority: Boolean get() = false
    val formulaExecutionAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false

    companion object {
        fun create(
            semanticPlan: SemanticArtifactPlan,
            sheets: Collection<TabularSheetPlan>,
        ): TabularArtifactPlan {
            val canonicalSheets = sheets.sortedBy { it.name }
            return TabularArtifactPlan(
                semanticPlanFingerprint = semanticPlan.fingerprint,
                sourceWorldRevision = semanticPlan.sourceWorldRevision,
                sheets = canonicalSheets,
                fingerprint = tabularPlanFingerprint(
                    semanticPlan.fingerprint,
                    semanticPlan.sourceWorldRevision,
                    canonicalSheets,
                ),
            )
        }
    }
}

class TabularArtifact internal constructor(
    val semanticPlanFingerprint: String,
    val tabularPlanFingerprint: String,
    val outputFormat: TabularOutputFormat,
    val renderedClaimIds: List<String>,
    val evidenceStableKeys: List<String>,
    val sheetFingerprints: List<String>,
    payload: ByteArray,
    val contentSha256: String,
    val fingerprint: String,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(semanticPlanFingerprint.matches(SHA_256_B446))
        require(tabularPlanFingerprint.matches(SHA_256_B446))
        require(renderedClaimIds == renderedClaimIds.distinct().sorted())
        require(evidenceStableKeys == evidenceStableKeys.distinct().sorted())
        require(sheetFingerprints.isNotEmpty())
        require(sheetFingerprints == sheetFingerprints.distinct().sorted())
        require(payloadBytes.isNotEmpty())
        require(contentSha256 == sha256B446(payloadBytes))
        require(
            fingerprint == tabularArtifactFingerprint(
                semanticPlanFingerprint,
                tabularPlanFingerprint,
                outputFormat,
                renderedClaimIds,
                evidenceStableKeys,
                sheetFingerprints,
                contentSha256,
            )
        )
    }

    val mediaType: String
        get() = when (outputFormat) {
            TabularOutputFormat.CSV -> "text/csv; charset=utf-8"
            TabularOutputFormat.XLSX ->
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        }

    val payload: ByteArray
        get() = payloadBytes.copyOf()

    val factualAuthority: Boolean get() = false
    val claimCreationAuthority: Boolean get() = false
    val formulaExecutionAuthority: Boolean get() = false
    val finalizationAuthority: Boolean get() = false
    val publicationAuthority: Boolean get() = false
}

/**
 * B446 produces deterministic CSV or XLSX artifacts from an explicit evidence-bound table plan.
 *
 * Literal data cells must be exact canonical content from one resolved SemanticArtifactClaim with
 * the exact claim evidence keys. Formula cells are restricted to local arithmetic over same-sheet
 * numeric/formula cells; external links, functions, ranges and cross-sheet references are rejected.
 */
class TabularArtifactRenderer {
    fun render(
        semanticPlan: SemanticArtifactPlan,
        plan: TabularArtifactPlan,
        format: TabularOutputFormat,
    ): TabularArtifact {
        require(plan.semanticPlanFingerprint == semanticPlan.fingerprint) {
            "B446 tabular plan does not match semantic plan"
        }
        require(plan.sourceWorldRevision == semanticPlan.sourceWorldRevision)

        validateEvidenceClosureB446(semanticPlan, plan)
        validateFormulaGraphsB446(plan)

        if (format == TabularOutputFormat.CSV) {
            require(plan.sheets.size == 1) {
                "B446 CSV output requires exactly one sheet"
            }
            require(plan.sheets.single().cells.none { it.kind == TabularCellKind.FORMULA }) {
                "B446 CSV refuses executable formula cells"
            }
            require(
                plan.sheets.single().cells.none { cell ->
                    (cell.kind == TabularCellKind.HEADER || cell.kind == TabularCellKind.TEXT) &&
                        cell.value.firstOrNull() in CSV_FORMULA_TRIGGER_CHARS_B446
                }
            ) {
                "B446 CSV refuses formula-injection-shaped literal cells"
            }
        }

        val payload = when (format) {
            TabularOutputFormat.CSV -> renderCsvB446(plan.sheets.single())
            TabularOutputFormat.XLSX -> renderXlsxB446(plan)
        }
        val contentSha = sha256B446(payload)
        val claims = plan.sheets
            .flatMap { sheet -> sheet.cells.mapNotNull { it.claimId } }
            .distinct()
            .sorted()
        val evidence = plan.sheets
            .flatMap { sheet -> sheet.cells.flatMap { it.evidenceStableKeys } }
            .distinct()
            .sorted()
        val sheetFingerprints = plan.sheets.map { it.fingerprint }.distinct().sorted()

        return TabularArtifact(
            semanticPlanFingerprint = semanticPlan.fingerprint,
            tabularPlanFingerprint = plan.fingerprint,
            outputFormat = format,
            renderedClaimIds = claims,
            evidenceStableKeys = evidence,
            sheetFingerprints = sheetFingerprints,
            payload = payload,
            contentSha256 = contentSha,
            fingerprint = tabularArtifactFingerprint(
                semanticPlan.fingerprint,
                plan.fingerprint,
                format,
                claims,
                evidence,
                sheetFingerprints,
                contentSha,
            ),
        )
    }
}

private fun validateEvidenceClosureB446(
    semanticPlan: SemanticArtifactPlan,
    plan: TabularArtifactPlan,
) {
    val claimsById = semanticPlan.claims.associateBy { it.claimId }
    plan.sheets.forEach { sheet ->
        sheet.cells.forEach { cell ->
            when (cell.kind) {
                TabularCellKind.TEXT,
                TabularCellKind.DECIMAL -> {
                    val claim = requireNotNull(claimsById[cell.claimId]) {
                        "B446 data cell references unknown claim"
                    }
                    require(claim.claimId !in semanticPlan.unresolvedClaimIds) {
                        "B446 data cell references unresolved claim"
                    }
                    require(cell.value == claim.canonicalContent) {
                        "B446 literal cell must equal exact semantic claim content"
                    }
                    val exactEvidence = claim.evidence
                        .map { it.stableKey }
                        .distinct()
                        .sorted()
                    require(cell.evidenceStableKeys == exactEvidence) {
                        "B446 literal cell evidence differs from exact claim evidence"
                    }
                }
                TabularCellKind.HEADER,
                TabularCellKind.FORMULA,
                TabularCellKind.BLANK -> Unit
            }
        }
    }
}

private fun validateFormulaGraphsB446(plan: TabularArtifactPlan) {
    plan.sheets.forEach { sheet ->
        val cellsByAddress = sheet.cells.associateBy { cellAddressB446(it.row, it.column) }
        val formulaDependencies = sheet.cells
            .filter { it.kind == TabularCellKind.FORMULA }
            .associate { cell ->
                val address = cellAddressB446(cell.row, cell.column)
                val refs = formulaReferencesB446(cell.value)
                require(refs.isNotEmpty()) {
                    "B446 formula must reference at least one same-sheet numeric cell"
                }
                require(address !in refs) {
                    "B446 formula cannot directly reference itself"
                }
                refs.forEach { ref ->
                    val source = requireNotNull(cellsByAddress[ref]) {
                        "B446 formula references missing cell"
                    }
                    require(
                        source.kind == TabularCellKind.DECIMAL ||
                            source.kind == TabularCellKind.FORMULA
                    ) {
                        "B446 formula may reference numeric/formula cells only"
                    }
                }
                address to refs.filter { ref ->
                    cellsByAddress.getValue(ref).kind == TabularCellKind.FORMULA
                }.toSet()
            }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(address: String) {
            if (address in visited) return
            require(address !in visiting) {
                "B446 formula dependency cycle detected"
            }
            visiting += address
            formulaDependencies[address].orEmpty().forEach(::visit)
            visiting -= address
            visited += address
        }
        formulaDependencies.keys.sorted().forEach(::visit)
    }
}

private fun renderCsvB446(sheet: TabularSheetPlan): ByteArray {
    val byCoordinate = sheet.cells.associateBy { it.row to it.column }
    val text = buildString {
        for (row in 1..sheet.maxRow) {
            for (column in 1..sheet.maxColumn) {
                if (column > 1) append(',')
                val cell = byCoordinate[row to column]
                val value = when (cell?.kind) {
                    null,
                    TabularCellKind.BLANK -> ""
                    TabularCellKind.HEADER,
                    TabularCellKind.TEXT,
                    TabularCellKind.DECIMAL -> cell.value
                    TabularCellKind.FORMULA -> error("formula already rejected for CSV")
                }
                append(csvEscapeB446(value))
            }
            append("\r\n")
        }
    }
    return text.toByteArray(StandardCharsets.UTF_8)
}

private fun renderXlsxB446(plan: TabularArtifactPlan): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        writeZipEntryB446(
            zip,
            "[Content_Types].xml",
            xlsxContentTypesB446(plan.sheets.size).toByteArray(StandardCharsets.UTF_8),
        )
        writeZipEntryB446(
            zip,
            "_rels/.rels",
            xlsxRootRelsB446().toByteArray(StandardCharsets.UTF_8),
        )
        writeZipEntryB446(
            zip,
            "docProps/core.xml",
            xlsxCorePropertiesB446().toByteArray(StandardCharsets.UTF_8),
        )
        writeZipEntryB446(
            zip,
            "docProps/app.xml",
            xlsxAppPropertiesB446(plan).toByteArray(StandardCharsets.UTF_8),
        )
        writeZipEntryB446(
            zip,
            "xl/workbook.xml",
            xlsxWorkbookB446(plan).toByteArray(StandardCharsets.UTF_8),
        )
        writeZipEntryB446(
            zip,
            "xl/_rels/workbook.xml.rels",
            xlsxWorkbookRelsB446(plan.sheets.size).toByteArray(StandardCharsets.UTF_8),
        )
        writeZipEntryB446(
            zip,
            "xl/styles.xml",
            xlsxStylesB446().toByteArray(StandardCharsets.UTF_8),
        )
        plan.sheets.forEachIndexed { index, sheet ->
            writeZipEntryB446(
                zip,
                "xl/worksheets/sheet" + (index + 1) + ".xml",
                xlsxSheetB446(sheet).toByteArray(StandardCharsets.UTF_8),
            )
        }
    }
    return output.toByteArray()
}

private fun xlsxSheetB446(sheet: TabularSheetPlan): String {
    val cellsByRow = sheet.cells.groupBy { it.row }
    return buildString {
        append(XML_HEADER_B446)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<sheetData>")
        for (row in 1..sheet.maxRow) {
            val rowCells = cellsByRow[row].orEmpty().sortedBy { it.column }
            if (rowCells.isEmpty()) continue
            append("<row r=\"")
            append(row)
            append("\">")
            rowCells.forEach { cell ->
                val address = cellAddressB446(cell.row, cell.column)
                when (cell.kind) {
                    TabularCellKind.HEADER -> {
                        append("<c r=\"")
                        append(address)
                        append("\" t=\"inlineStr\" s=\"1\"><is><t>")
                        append(escapeXmlB446(cell.value))
                        append("</t></is></c>")
                    }
                    TabularCellKind.TEXT -> {
                        append("<c r=\"")
                        append(address)
                        append("\" t=\"inlineStr\"><is><t>")
                        append(escapeXmlB446(cell.value))
                        append("</t></is></c>")
                    }
                    TabularCellKind.DECIMAL -> {
                        append("<c r=\"")
                        append(address)
                        append("\"><v>")
                        append(cell.value)
                        append("</v></c>")
                    }
                    TabularCellKind.FORMULA -> {
                        append("<c r=\"")
                        append(address)
                        append("\"><f>")
                        append(escapeXmlB446(cell.value.removePrefix("=")))
                        append("</f></c>")
                    }
                    TabularCellKind.BLANK -> {
                        append("<c r=\"")
                        append(address)
                        append("\"/>")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        append("</worksheet>")
    }
}

private fun xlsxContentTypesB446(sheetCount: Int): String = buildString {
    append(XML_HEADER_B446)
    append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
    append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
    append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
    append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
    append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
    for (index in 1..sheetCount) {
        append("<Override PartName=\"/xl/worksheets/sheet")
        append(index)
        append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
    }
    append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
    append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
    append("</Types>")
}

private fun xlsxRootRelsB446(): String =
    XML_HEADER_B446 +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

private fun xlsxWorkbookB446(plan: TabularArtifactPlan): String = buildString {
    append(XML_HEADER_B446)
    append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
    append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
    append("<sheets>")
    plan.sheets.forEachIndexed { index, sheet ->
        append("<sheet name=\"")
        append(escapeXmlB446(sheet.name))
        append("\" sheetId=\"")
        append(index + 1)
        append("\" r:id=\"rIdSheet")
        append(index + 1)
        append("\"/>")
    }
    append("</sheets>")
    append("<calcPr calcId=\"0\" fullCalcOnLoad=\"1\" forceFullCalc=\"1\"/>")
    append("</workbook>")
}

private fun xlsxWorkbookRelsB446(sheetCount: Int): String = buildString {
    append(XML_HEADER_B446)
    append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
    append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
    for (index in 1..sheetCount) {
        append("<Relationship Id=\"rIdSheet")
        append(index)
        append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
        append(index)
        append(".xml\"/>")
    }
    append("</Relationships>")
}

private fun xlsxStylesB446(): String =
    XML_HEADER_B446 +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"2\">" +
        "<font><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>" +
        "</fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

private fun xlsxCorePropertiesB446(): String =
    XML_HEADER_B446 +
        "<cp:coreProperties " +
        "xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
        "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" " +
        "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
        "<dc:title>LIFEOS workbook</dc:title><dc:creator>LIFEOS</dc:creator>" +
        "<cp:lastModifiedBy>LIFEOS</cp:lastModifiedBy>" +
        "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + FIXED_TIMESTAMP_B446 + "</dcterms:created>" +
        "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + FIXED_TIMESTAMP_B446 + "</dcterms:modified>" +
        "</cp:coreProperties>"

private fun xlsxAppPropertiesB446(plan: TabularArtifactPlan): String = buildString {
    append(XML_HEADER_B446)
    append("<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" ")
    append("xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">")
    append("<Application>LIFEOS</Application><AppVersion>1.0</AppVersion>")
    append("<TitlesOfParts><vt:vector size=\"")
    append(plan.sheets.size)
    append("\" baseType=\"lpstr\">")
    plan.sheets.forEach { sheet ->
        append("<vt:lpstr>")
        append(escapeXmlB446(sheet.name))
        append("</vt:lpstr>")
    }
    append("</vt:vector></TitlesOfParts>")
    append("</Properties>")
}

private fun writeZipEntryB446(
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

private fun isSafeFormulaB446(expression: String): Boolean {
    if (!expression.startsWith("=")) return false
    if (expression.length > MAX_FORMULA_CHARS_B446) return false
    if (expression.any { it == '\r' || it == '\n' || it == '\u0000' }) return false
    if (expression.any { it in FORBIDDEN_FORMULA_CHARS_B446 }) return false

    val canonical = expression.uppercase(Locale.ROOT)
    val refsRemoved = CELL_REF_REGEX_B446.replace(canonical, "0")
    if (refsRemoved.any { it.isLetter() }) return false
    if (!refsRemoved.matches(SAFE_FORMULA_REMAINDER_REGEX_B446)) return false
    return formulaReferencesB446(canonical).isNotEmpty()
}

private fun formulaReferencesB446(expression: String): Set<String> =
    CELL_REF_REGEX_B446.findAll(expression.uppercase(Locale.ROOT))
        .map { it.value }
        .toSortedSet()

private fun canonicalDecimalB446(value: String): String {
    val trimmed = value.trim()
    require(trimmed.matches(DECIMAL_REGEX_B446)) {
        "B446 decimal cell requires canonical plain decimal notation"
    }
    return trimmed
}

private fun cellAddressB446(
    row: Int,
    column: Int,
): String = columnNameB446(column) + row

private fun columnNameB446(column: Int): String {
    require(column > 0)
    var value = column
    val chars = StringBuilder()
    while (value > 0) {
        val remainder = (value - 1) % 26
        chars.append(('A'.code + remainder).toChar())
        value = (value - 1) / 26
    }
    return chars.reverse().toString()
}

private fun isValidSheetNameB446(name: String): Boolean =
    name.isNotBlank() &&
        name.length <= 31 &&
        name.none { it in INVALID_SHEET_NAME_CHARS_B446 } &&
        !name.startsWith("'") &&
        !name.endsWith("'")

private fun csvEscapeB446(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
    val escaped = value.replace("\"", "\"\"")
    return if (needsQuotes) "\"" + escaped + "\"" else escaped
}

private fun escapeXmlB446(value: String): String = buildString(value.length) {
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

private fun tabularCellFingerprint(
    row: Int,
    column: Int,
    kind: TabularCellKind,
    value: String,
    claimId: String?,
    evidenceStableKeys: List<String>,
): String = StableCognitiveIds.fingerprint(
    "tabular-cell/v1",
    row.toString(),
    column.toString(),
    kind.name,
    value,
    claimId.orEmpty(),
    evidenceStableKeys.joinToString("\u001f"),
)

private fun tabularSheetFingerprint(
    name: String,
    cells: List<TabularCell>,
): String = StableCognitiveIds.fingerprint(
    "tabular-sheet-plan/v1",
    name,
    *cells.map { it.fingerprint }.toTypedArray(),
)

private fun tabularPlanFingerprint(
    semanticPlanFingerprint: String,
    sourceWorldRevision: Long,
    sheets: List<TabularSheetPlan>,
): String = StableCognitiveIds.fingerprint(
    "tabular-artifact-plan/v1",
    semanticPlanFingerprint,
    sourceWorldRevision.toString(),
    *sheets.map { it.fingerprint }.toTypedArray(),
)

private fun tabularArtifactFingerprint(
    semanticPlanFingerprint: String,
    tabularPlanFingerprint: String,
    outputFormat: TabularOutputFormat,
    renderedClaimIds: List<String>,
    evidenceStableKeys: List<String>,
    sheetFingerprints: List<String>,
    contentSha256: String,
): String = StableCognitiveIds.fingerprint(
    "tabular-artifact/v1",
    semanticPlanFingerprint,
    tabularPlanFingerprint,
    outputFormat.name,
    renderedClaimIds.joinToString("\u001f"),
    evidenceStableKeys.joinToString("\u001f"),
    sheetFingerprints.joinToString("\u001f"),
    contentSha256,
)

private fun sha256B446(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val XML_HEADER_B446 =
    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
private const val FIXED_TIMESTAMP_B446 = "2000-01-01T00:00:00Z"
private const val MAX_TABULAR_ROWS_B446 = 100_000
private const val MAX_TABULAR_COLUMNS_B446 = 512
private const val MAX_TABULAR_CELL_CHARS_B446 = 32_767
private const val MAX_FORMULA_CHARS_B446 = 512
private val CSV_FORMULA_TRIGGER_CHARS_B446 = setOf('=', '+', '-', '@')
private val INVALID_SHEET_NAME_CHARS_B446 = setOf('[', ']', ':', '*', '?', '/', '\\')
private val FORBIDDEN_FORMULA_CHARS_B446 = setOf('!', '[', ']', '"', '\'', ':', ',', ';', '$', '@', '{', '}')
private val DECIMAL_REGEX_B446 = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
private val CELL_REF_REGEX_B446 = Regex("(?<![A-Z0-9_])[A-Z]{1,3}[1-9][0-9]{0,5}(?![A-Z0-9_])")
private val SAFE_FORMULA_REMAINDER_REGEX_B446 = Regex("=[0-9+\\-*/(). ]+")
private val SHA_256_B446 = Regex("[0-9a-f]{64}")
