package app.lifeos.core.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

internal object OpenXmlDocumentDecoder {
    fun decodeDocx(input: DocumentInput, limits: DocumentDecodeLimits): DocumentDecodeResult {
        val archive = readOpenXmlArchive(input, limits, DocumentFormat.DOCX)
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.DOCX, "DOCX archive exceeds safe limits")
        val documentBytes = archive.entries["word/document.xml"]
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.DOCX, "word/document.xml is missing")
        val xml = parseXml(documentBytes)
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.DOCX, "word/document.xml is malformed")
        val body = xml.getElementsByTagNameNS("*", "body").item(0)
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.DOCX, "document body is missing")
        val blocks = mutableListOf<DocumentBlock>()
        val warnings = archive.warnings.toMutableList()

        body.childNodes.asSequence().forEach { child ->
            if (blocks.size >= limits.maxBlocks) return@forEach
            when (child.localNameOrNodeName()) {
                "p" -> addWordParagraph(child, blocks, input.archivePath)
                "tbl" -> addWordTable(child, blocks, input.archivePath, limits.maxBlocks)
            }
        }
        if (blocks.size >= limits.maxBlocks) warnings += "block limit reached"
        if (blocks.isEmpty()) {
            return DocumentDecodeResult.Unsupported(input, DocumentFormat.DOCX, "DOCX contains no readable text")
        }
        return DocumentDecodeResult.Decoded(
            DocumentEnvelope(
                sourceId = input.sourceId,
                fileName = input.fileName,
                declaredMimeType = input.declaredMimeType,
                detectedFormat = DocumentFormat.DOCX,
                decoderId = "openxml-docx",
                decoderVersion = "openxml-docx/v1",
                contentSha256 = input.contentSha256,
                blocks = blocks,
                warnings = warnings.distinct(),
                archivePath = input.archivePath,
            )
        )
    }

    fun decodeXlsx(input: DocumentInput, limits: DocumentDecodeLimits): DocumentDecodeResult {
        val archive = readOpenXmlArchive(input, limits, DocumentFormat.XLSX)
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.XLSX, "XLSX archive exceeds safe limits")
        val workbookBytes = archive.entries["xl/workbook.xml"]
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.XLSX, "xl/workbook.xml is missing")
        val workbook = parseXml(workbookBytes)
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.XLSX, "workbook.xml is malformed")
        val sharedStrings = archive.entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        val relTargets = archive.entries["xl/_rels/workbook.xml.rels"]?.let(::parseWorkbookRelations).orEmpty()
        val sheetSpecs = workbook.getElementsByTagNameNS("*", "sheet").asSequence().mapNotNull { node ->
            val element = node as? Element ?: return@mapNotNull null
            val name = element.getAttribute("name").ifBlank { "Sheet" }
            val relationId = element.getAttributeNS(
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                "id",
            ).ifBlank { element.getAttribute("r:id") }
            val target = relTargets[relationId] ?: return@mapNotNull null
            val path = when {
                target.startsWith("/") -> target.removePrefix("/")
                target.startsWith("xl/") -> target
                else -> "xl/${target.removePrefix("../")}".replace("//", "/")
            }
            Triple(name, relationId, path)
        }.toList()

        val blocks = mutableListOf<DocumentBlock>()
        val warnings = archive.warnings.toMutableList()
        val effectiveSheets = if (sheetSpecs.isNotEmpty()) sheetSpecs else {
            archive.entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
                .sorted()
                .mapIndexed { index, path -> Triple("Sheet${index + 1}", "fallback-${index + 1}", path) }
        }
        effectiveSheets.forEach { (sheetName, _, path) ->
            if (blocks.size >= limits.maxBlocks) return@forEach
            val bytes = archive.entries[path]
            if (bytes == null) {
                warnings += "worksheet missing: $path"
                return@forEach
            }
            val sheet = parseXml(bytes)
            if (sheet == null) {
                warnings += "worksheet malformed: $path"
                return@forEach
            }
            blocks += DocumentBlock(
                kind = DocumentBlockKind.SHEET,
                text = sheetName,
                locator = DocumentSourceLocator(
                    blockIndex = blocks.size,
                    sheet = sheetName,
                    archivePath = input.archivePath,
                ),
                attributes = mapOf("worksheetPath" to path),
            )
            sheet.getElementsByTagNameNS("*", "c").asSequence().forEach { node ->
                if (blocks.size >= limits.maxBlocks) return@forEach
                val cell = node as? Element ?: return@forEach
                val reference = cell.getAttribute("r").ifBlank { null }
                val type = cell.getAttribute("t")
                val rawValue = firstDescendantText(cell, "v")
                val inlineValue = firstDescendantText(cell, "t")
                val formula = firstDescendantText(cell, "f")
                val value = when (type) {
                    "s" -> rawValue?.toIntOrNull()?.let(sharedStrings::getOrNull)
                    "inlineStr" -> inlineValue
                    "b" -> when (rawValue) { "1" -> "true"; "0" -> "false"; else -> rawValue }
                    "str" -> rawValue
                    else -> rawValue ?: inlineValue
                }?.trim().orEmpty()
                if (value.isBlank() && formula.isNullOrBlank()) return@forEach
                val row = reference?.dropWhile { !it.isDigit() }?.toIntOrNull()
                val column = reference?.takeWhile { it.isLetter() }?.let(::spreadsheetColumnNumber)
                val display = when {
                    formula.isNullOrBlank() -> value
                    value.isBlank() -> "=$formula"
                    else -> "$value"
                }
                blocks += DocumentBlock(
                    kind = DocumentBlockKind.TABLE_CELL,
                    text = display,
                    locator = DocumentSourceLocator(
                        blockIndex = blocks.size,
                        sheet = sheetName,
                        row = row,
                        column = column,
                        cell = reference,
                        archivePath = input.archivePath,
                    ),
                    attributes = buildMap {
                        if (!formula.isNullOrBlank()) put("formula", formula)
                        if (type.isNotBlank()) put("cellType", type)
                    },
                )
            }
        }
        if (blocks.size >= limits.maxBlocks) warnings += "block limit reached"
        if (blocks.none { it.kind == DocumentBlockKind.TABLE_CELL }) {
            return DocumentDecodeResult.Unsupported(input, DocumentFormat.XLSX, "XLSX contains no readable cells")
        }
        return DocumentDecodeResult.Decoded(
            DocumentEnvelope(
                sourceId = input.sourceId,
                fileName = input.fileName,
                declaredMimeType = input.declaredMimeType,
                detectedFormat = DocumentFormat.XLSX,
                decoderId = "openxml-xlsx",
                decoderVersion = "openxml-xlsx/v1",
                contentSha256 = input.contentSha256,
                blocks = blocks,
                warnings = warnings.distinct(),
                archivePath = input.archivePath,
            )
        )
    }

    private fun addWordParagraph(node: Node, blocks: MutableList<DocumentBlock>, archivePath: String?) {
        val text = descendantText(node, "t").joinToString("").trim()
        if (text.isBlank()) return
        val style = firstDescendantElement(node, "pStyle")?.let { element ->
            element.getAttributeNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                "val",
            ).ifBlank { element.getAttribute("w:val") }.ifBlank { element.getAttribute("val") }
        }.orEmpty()
        val isList = firstDescendantElement(node, "numPr") != null
        val headingLevel = headingLevel(style)
        val kind = when {
            headingLevel != null -> DocumentBlockKind.HEADING
            isList -> DocumentBlockKind.LIST_ITEM
            else -> DocumentBlockKind.PARAGRAPH
        }
        blocks += DocumentBlock(
            kind = kind,
            text = text,
            locator = DocumentSourceLocator(blocks.size, archivePath = archivePath),
            attributes = buildMap {
                if (style.isNotBlank()) put("style", style)
                headingLevel?.let { put("level", it.toString()) }
            },
        )
    }

    private fun addWordTable(
        table: Node,
        blocks: MutableList<DocumentBlock>,
        archivePath: String?,
        maxBlocks: Int,
    ) {
        table.childNodes.asSequence().filter { it.localNameOrNodeName() == "tr" }.forEachIndexed { rowIndex, row ->
            if (blocks.size >= maxBlocks) return@forEachIndexed
            val cells = row.childNodes.asSequence().filter { it.localNameOrNodeName() == "tc" }
                .map { cell -> descendantText(cell, "t").joinToString("").trim() }
                .toList()
            val nonEmpty = cells.mapIndexedNotNull { columnIndex, text ->
                text.takeIf { it.isNotBlank() }?.let { columnIndex to it }
            }
            if (nonEmpty.isNotEmpty()) {
                blocks += DocumentBlock(
                    DocumentBlockKind.TABLE_ROW,
                    nonEmpty.joinToString(" | ") { it.second },
                    DocumentSourceLocator(blocks.size, row = rowIndex + 1, archivePath = archivePath),
                )
            }
            nonEmpty.forEach { (columnIndex, text) ->
                if (blocks.size >= maxBlocks) return@forEach
                blocks += DocumentBlock(
                    DocumentBlockKind.TABLE_CELL,
                    text,
                    DocumentSourceLocator(
                        blockIndex = blocks.size,
                        row = rowIndex + 1,
                        column = columnIndex + 1,
                        archivePath = archivePath,
                    ),
                )
            }
        }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val xml = parseXml(bytes) ?: return emptyList()
        return xml.getElementsByTagNameNS("*", "si").asSequence().map { item ->
            descendantText(item, "t").joinToString("").trim()
        }.toList()
    }

    private fun parseWorkbookRelations(bytes: ByteArray): Map<String, String> {
        val xml = parseXml(bytes) ?: return emptyMap()
        return buildMap {
            xml.getElementsByTagNameNS("*", "Relationship").asSequence().forEach { node ->
                val element = node as? Element ?: return@forEach
                val id = element.getAttribute("Id")
                val target = element.getAttribute("Target")
                if (id.isNotBlank() && target.isNotBlank()) put(id, target)
            }
        }
    }

    private fun readOpenXmlArchive(
        input: DocumentInput,
        limits: DocumentDecodeLimits,
        format: DocumentFormat,
    ): OpenXmlArchive? {
        val entries = linkedMapOf<String, ByteArray>()
        val warnings = mutableListOf<String>()
        var count = 0
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(input.bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                count++
                if (count > limits.maxArchiveEntries) return null
                val safe = normalizeArchivePath(null, entry.name) ?: return null
                val bytes = readEntryBounded(zip, limits.maxArchiveEntryBytes) ?: return null
                expanded += bytes.size
                if (expanded > limits.maxArchiveExpandedBytes) return null
                if (entry.compressedSize > 0L) {
                    val ratio = bytes.size.toDouble() / entry.compressedSize.toDouble()
                    if (ratio > limits.maxCompressionRatio) return null
                }
                if (safe.endsWith(".xml") || safe.endsWith(".rels")) entries[safe] = bytes
            }
        }
        if (entries.isEmpty()) warnings += "$format contains no XML parts"
        return OpenXmlArchive(entries, warnings)
    }

    private fun readEntryBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            setExpandEntityReferences(false)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.getOrNull()

    private fun descendantText(node: Node, localName: String): List<String> = buildList {
        fun visit(current: Node) {
            if (current.localNameOrNodeName() == localName) {
                current.textContent?.takeIf { it.isNotEmpty() }?.let(::add)
                return
            }
            current.childNodes.asSequence().forEach(::visit)
        }
        visit(node)
    }

    private fun firstDescendantText(node: Node, localName: String): String? =
        descendantText(node, localName).firstOrNull()

    private fun firstDescendantElement(node: Node, localName: String): Element? {
        if (node.localNameOrNodeName() == localName) return node as? Element
        node.childNodes.asSequence().forEach { child ->
            firstDescendantElement(child, localName)?.let { return it }
        }
        return null
    }

    private fun headingLevel(style: String): Int? {
        val normalized = style.lowercase()
        val match = Regex("(?:heading|überschrift|ueberschrift)[ _-]?(\\d)").find(normalized) ?: return null
        return match.groupValues[1].toIntOrNull()?.coerceIn(1, 6)
    }

    private fun Node.localNameOrNodeName(): String = localName ?: nodeName.substringAfter(':')

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }

    private fun spreadsheetColumnNumber(value: String): Int {
        var result = 0
        value.uppercase().forEach { char ->
            if (char in 'A'..'Z') result = result * 26 + (char - 'A' + 1)
        }
        return result.takeIf { it > 0 } ?: 1
    }

    private data class OpenXmlArchive(
        val entries: Map<String, ByteArray>,
        val warnings: List<String>,
    )
}
