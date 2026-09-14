package app.lifeos.core.document

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream

interface ExternalDocumentDecoder {
    val decoderId: String
    val decoderVersion: String
    fun supports(format: DocumentFormat): Boolean
    fun decode(input: DocumentInput, limits: DocumentDecodeLimits): DocumentDecodeResult
}

class DocumentFormatDetector {
    fun detect(input: DocumentInput): DocumentFormat {
        val name = input.fileName.lowercase(Locale.ROOT)
        val mime = input.declaredMimeType?.lowercase(Locale.ROOT).orEmpty()
        val bytes = input.bytes

        return when {
            name.endsWith(".docx") || mime == DOCX_MIME -> DocumentFormat.DOCX
            name.endsWith(".xlsx") || mime == XLSX_MIME -> DocumentFormat.XLSX
            name.endsWith(".pdf") || mime == "application/pdf" || bytes.startsWithAscii("%PDF-") -> DocumentFormat.PDF
            name.endsWith(".zip") || mime in ZIP_MIMES || bytes.hasZipMagic() -> DocumentFormat.ZIP
            name.endsWith(".csv") || mime in CSV_MIMES -> DocumentFormat.CSV
            name.endsWith(".tsv") || mime == "text/tab-separated-values" -> DocumentFormat.TSV
            name.endsWith(".md") || name.endsWith(".markdown") || mime == "text/markdown" -> DocumentFormat.MARKDOWN
            name.endsWith(".json") || mime == "application/json" || mime.endsWith("+json") -> DocumentFormat.JSON
            name.endsWith(".html") || name.endsWith(".htm") || mime == "text/html" -> DocumentFormat.HTML
            name.endsWith(".xml") || mime in XML_MIMES || mime.endsWith("+xml") -> DocumentFormat.XML
            name.endsWith(".txt") || mime.startsWith("text/") -> DocumentFormat.TEXT
            looksLikeUtf8Text(bytes) -> inferTextFormat(bytes)
            else -> DocumentFormat.UNKNOWN
        }
    }

    private fun inferTextFormat(bytes: ByteArray): DocumentFormat {
        val prefix = decodeUtf8Strict(bytes.take(4096).toByteArray())?.trimStart().orEmpty()
        return when {
            prefix.startsWith("{") || prefix.startsWith("[") -> DocumentFormat.JSON
            prefix.startsWith("<!doctype html", ignoreCase = true) || prefix.startsWith("<html", ignoreCase = true) -> DocumentFormat.HTML
            prefix.startsWith("<?xml", ignoreCase = true) -> DocumentFormat.XML
            else -> DocumentFormat.TEXT
        }
    }

    companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val ZIP_MIMES = setOf("application/zip", "application/x-zip-compressed", "multipart/x-zip")
        val CSV_MIMES = setOf("text/csv", "application/csv", "text/x-csv")
        val XML_MIMES = setOf("application/xml", "text/xml")
    }
}

class DocumentDecoderRegistry(
    val limits: DocumentDecodeLimits = DocumentDecodeLimits(),
    private val externalDecoders: List<ExternalDocumentDecoder> = emptyList(),
    private val detector: DocumentFormatDetector = DocumentFormatDetector(),
) {
    fun decode(input: DocumentInput): DocumentDecodeResult = decodeInternal(input, depth = 0)

    internal fun decodeInternal(input: DocumentInput, depth: Int): DocumentDecodeResult {
        val format = detector.detect(input)
        if (input.bytes.size > limits.maxInputBytes) {
            return DocumentDecodeResult.Rejected(
                input,
                format,
                "input exceeds ${limits.maxInputBytes} byte decoder limit",
            )
        }
        externalDecoders.firstOrNull { it.supports(format) }?.let { decoder ->
            return runCatching { decoder.decode(input, limits) }
                .getOrElse { error ->
                    DocumentDecodeResult.Rejected(input, format, "${decoder.decoderId}: ${safeMessage(error)}")
                }
        }
        return try {
            when (format) {
                DocumentFormat.TEXT,
                DocumentFormat.JSON,
                DocumentFormat.XML,
                DocumentFormat.HTML -> decodeText(input, format)
                DocumentFormat.MARKDOWN -> decodeMarkdown(input)
                DocumentFormat.CSV -> decodeDelimited(input, ',', DocumentFormat.CSV)
                DocumentFormat.TSV -> decodeDelimited(input, '\t', DocumentFormat.TSV)
                DocumentFormat.DOCX -> OpenXmlDocumentDecoder.decodeDocx(input, limits)
                DocumentFormat.XLSX -> OpenXmlDocumentDecoder.decodeXlsx(input, limits)
                DocumentFormat.ZIP -> decodeZip(input, depth)
                DocumentFormat.PDF -> DocumentDecodeResult.Unsupported(
                    input,
                    format,
                    "PDF requires a platform PDF text decoder",
                )
                DocumentFormat.UNKNOWN -> DocumentDecodeResult.Unsupported(
                    input,
                    format,
                    "no safe decoder registered for this binary format",
                )
            }
        } catch (error: Throwable) {
            DocumentDecodeResult.Rejected(input, format, safeMessage(error))
        }
    }

    private fun decodeText(input: DocumentInput, format: DocumentFormat): DocumentDecodeResult {
        val text = decodeUtf8Strict(input.bytes)
            ?: return DocumentDecodeResult.Rejected(input, format, "content is not valid UTF-8 text")
        if (text.length > limits.maxTextChars) {
            return DocumentDecodeResult.Rejected(input, format, "decoded text exceeds ${limits.maxTextChars} characters")
        }
        val paragraphs = paragraphBlocks(text, input.archivePath, limits.maxBlocks)
        return decodedEnvelope(
            input = input,
            format = format,
            decoderId = "utf8-text",
            decoderVersion = "utf8-text/v1",
            blocks = paragraphs.ifEmpty {
                listOf(DocumentBlock(DocumentBlockKind.RAW_TEXT, text.trim(), DocumentSourceLocator(0, archivePath = input.archivePath)))
                    .filter { it.text.isNotBlank() }
            },
        )
    }

    private fun decodeMarkdown(input: DocumentInput): DocumentDecodeResult {
        val text = decodeUtf8Strict(input.bytes)
            ?: return DocumentDecodeResult.Rejected(input, DocumentFormat.MARKDOWN, "content is not valid UTF-8 text")
        if (text.length > limits.maxTextChars) {
            return DocumentDecodeResult.Rejected(input, DocumentFormat.MARKDOWN, "decoded text exceeds ${limits.maxTextChars} characters")
        }
        val blocks = buildList {
            var paragraph = mutableListOf<String>()
            fun flushParagraph() {
                val value = paragraph.joinToString(" ").trim()
                if (value.isNotBlank() && size < limits.maxBlocks) {
                    add(DocumentBlock(DocumentBlockKind.PARAGRAPH, value, DocumentSourceLocator(size, archivePath = input.archivePath)))
                }
                paragraph = mutableListOf()
            }
            text.lineSequence().forEach { raw ->
                if (size >= limits.maxBlocks) return@forEach
                val line = raw.trim()
                when {
                    line.isBlank() -> flushParagraph()
                    HEADING.matches(line) -> {
                        flushParagraph()
                        val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                        val value = line.drop(level).trim()
                        if (value.isNotBlank()) add(
                            DocumentBlock(
                                DocumentBlockKind.HEADING,
                                value,
                                DocumentSourceLocator(size, archivePath = input.archivePath),
                                mapOf("level" to level.toString()),
                            )
                        )
                    }
                    LIST_ITEM.matches(line) -> {
                        flushParagraph()
                        val value = line.replaceFirst(LIST_PREFIX, "").trim()
                        if (value.isNotBlank()) add(
                            DocumentBlock(DocumentBlockKind.LIST_ITEM, value, DocumentSourceLocator(size, archivePath = input.archivePath))
                        )
                    }
                    else -> paragraph += line
                }
            }
            flushParagraph()
        }
        return decodedEnvelope(input, DocumentFormat.MARKDOWN, "markdown", "markdown/v1", blocks)
    }

    private fun decodeDelimited(
        input: DocumentInput,
        delimiter: Char,
        format: DocumentFormat,
    ): DocumentDecodeResult {
        val text = decodeUtf8Strict(input.bytes)
            ?: return DocumentDecodeResult.Rejected(input, format, "content is not valid UTF-8 text")
        if (text.length > limits.maxTextChars) {
            return DocumentDecodeResult.Rejected(input, format, "decoded text exceeds ${limits.maxTextChars} characters")
        }
        val rows = parseDelimited(text, delimiter)
        val blocks = buildList {
            rows.forEachIndexed { rowIndex, row ->
                if (size >= limits.maxBlocks) return@forEachIndexed
                val nonEmpty = row.mapIndexedNotNull { columnIndex, value ->
                    value.trim().takeIf { it.isNotBlank() }?.let { columnIndex to it }
                }
                if (nonEmpty.isNotEmpty()) {
                    add(
                        DocumentBlock(
                            DocumentBlockKind.TABLE_ROW,
                            nonEmpty.joinToString(" | ") { it.second },
                            DocumentSourceLocator(size, row = rowIndex + 1, archivePath = input.archivePath),
                        )
                    )
                }
                nonEmpty.forEach { (columnIndex, value) ->
                    if (size >= limits.maxBlocks) return@forEach
                    add(
                        DocumentBlock(
                            DocumentBlockKind.TABLE_CELL,
                            value,
                            DocumentSourceLocator(
                                blockIndex = size,
                                row = rowIndex + 1,
                                column = columnIndex + 1,
                                cell = spreadsheetColumn(columnIndex + 1) + (rowIndex + 1),
                                archivePath = input.archivePath,
                            ),
                        )
                    )
                }
            }
        }
        val warnings = if (blocks.size >= limits.maxBlocks) listOf("block limit reached") else emptyList()
        return decodedEnvelope(input, format, "delimited-text", "delimited-text/v1", blocks, warnings)
    }

    private fun decodeZip(input: DocumentInput, depth: Int): DocumentDecodeResult {
        if (depth >= limits.maxArchiveDepth) {
            return DocumentDecodeResult.Rejected(input, DocumentFormat.ZIP, "archive nesting exceeds ${limits.maxArchiveDepth}")
        }
        val blocks = mutableListOf<DocumentBlock>()
        val warnings = mutableListOf<String>()
        var entries = 0
        var expandedBytes = 0L
        ZipInputStream(ByteArrayInputStream(input.bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                entries++
                if (entries > limits.maxArchiveEntries) {
                    warnings += "archive entry limit reached"
                    break
                }
                val archivePath = normalizeArchivePath(input.archivePath, entry.name)
                if (archivePath == null) {
                    warnings += "rejected unsafe archive path: ${entry.name.take(160)}"
                    continue
                }
                val entryBytes = readBounded(zip, limits.maxArchiveEntryBytes)
                if (entryBytes == null) {
                    warnings += "entry exceeds byte limit: $archivePath"
                    continue
                }
                expandedBytes += entryBytes.size
                if (expandedBytes > limits.maxArchiveExpandedBytes) {
                    warnings += "archive expanded-byte limit reached"
                    break
                }
                if (entry.compressedSize > 0L) {
                    val ratio = entryBytes.size.toDouble() / entry.compressedSize.toDouble()
                    if (ratio > limits.maxCompressionRatio) {
                        warnings += "rejected suspicious compression ratio: $archivePath"
                        continue
                    }
                }
                val child = DocumentInput(
                    sourceId = input.sourceId,
                    fileName = entry.name.substringAfterLast('/').ifBlank { entry.name },
                    declaredMimeType = null,
                    bytes = entryBytes,
                    archivePath = archivePath,
                )
                when (val decoded = decodeInternal(child, depth + 1)) {
                    is DocumentDecodeResult.Decoded -> {
                        decoded.envelope.blocks.forEach { block ->
                            if (blocks.size < limits.maxBlocks) {
                                blocks += block.copy(locator = block.locator.copy(blockIndex = blocks.size))
                            }
                        }
                        warnings += decoded.envelope.warnings.map { "$archivePath: $it" }
                    }
                    is DocumentDecodeResult.Unsupported -> warnings += "$archivePath: ${decoded.reason}"
                    is DocumentDecodeResult.Rejected -> warnings += "$archivePath: ${decoded.reason}"
                }
                if (blocks.size >= limits.maxBlocks) {
                    warnings += "block limit reached"
                    break
                }
            }
        }
        if (blocks.isEmpty()) {
            return DocumentDecodeResult.Unsupported(input, DocumentFormat.ZIP, warnings.firstOrNull() ?: "archive contains no decodable text")
        }
        return decodedEnvelope(
            input,
            DocumentFormat.ZIP,
            "recursive-zip",
            "recursive-zip/v1",
            blocks,
            warnings.distinct(),
        )
    }

    private fun decodedEnvelope(
        input: DocumentInput,
        format: DocumentFormat,
        decoderId: String,
        decoderVersion: String,
        blocks: List<DocumentBlock>,
        warnings: List<String> = emptyList(),
    ): DocumentDecodeResult.Decoded = DocumentDecodeResult.Decoded(
        DocumentEnvelope(
            sourceId = input.sourceId,
            fileName = input.fileName,
            declaredMimeType = input.declaredMimeType,
            detectedFormat = format,
            decoderId = decoderId,
            decoderVersion = decoderVersion,
            contentSha256 = input.contentSha256,
            blocks = blocks.take(limits.maxBlocks),
            warnings = warnings,
            archivePath = input.archivePath,
        )
    )

    private companion object {
        val HEADING = Regex("^#{1,6}\\s+.+")
        val LIST_ITEM = Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+).+")
        val LIST_PREFIX = Regex("^(?:[-*+]\\s+|\\d+[.)]\\s+)")
    }
}

internal fun paragraphBlocks(text: String, archivePath: String?, maxBlocks: Int): List<DocumentBlock> {
    val result = mutableListOf<DocumentBlock>()
    text.replace("\r\n", "\n").replace('\r', '\n')
        .split(Regex("\\n\\s*\\n+"))
        .asSequence()
        .map { it.lineSequence().joinToString(" ") { line -> line.trim() }.trim() }
        .filter { it.isNotBlank() }
        .take(maxBlocks)
        .forEach { value ->
            result += DocumentBlock(
                DocumentBlockKind.PARAGRAPH,
                value,
                DocumentSourceLocator(result.size, archivePath = archivePath),
            )
        }
    return result
}

internal fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    val sample = bytes.take(8192).toByteArray()
    val decoded = decodeUtf8Strict(sample) ?: return false
    val controls = decoded.count { it.code < 0x20 && it !in "\n\r\t" }
    return controls <= maxOf(1, decoded.length / 100)
}

private fun ByteArray.startsWithAscii(value: String): Boolean {
    val expected = value.toByteArray(StandardCharsets.US_ASCII)
    if (size < expected.size) return false
    return expected.indices.all { this[it] == expected[it] }
}

private fun ByteArray.hasZipMagic(): Boolean = size >= 4 &&
    this[0] == 0x50.toByte() && this[1] == 0x4b.toByte() &&
    (this[2] == 0x03.toByte() || this[2] == 0x05.toByte() || this[2] == 0x07.toByte()) &&
    (this[3] == 0x04.toByte() || this[3] == 0x06.toByte() || this[3] == 0x08.toByte())

private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
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

private fun parseDelimited(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<MutableList<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < text.length) {
        val c = text[index]
        when {
            quoted && c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                field.append('"')
                index++
            }
            c == '"' -> quoted = !quoted
            !quoted && c == delimiter -> {
                row += field.toString()
                field.clear()
            }
            !quoted && (c == '\n' || c == '\r') -> {
                row += field.toString()
                field.clear()
                rows += row
                row = mutableListOf()
                if (c == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
            }
            else -> field.append(c)
        }
        index++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) {
        row += field.toString()
        rows += row
    }
    return rows
}

private fun spreadsheetColumn(index: Int): String {
    var value = index
    val result = StringBuilder()
    while (value > 0) {
        value--
        result.append(('A'.code + value % 26).toChar())
        value /= 26
    }
    return result.reverse().toString()
}

private fun safeMessage(error: Throwable): String =
    (error.message ?: error::class.simpleName ?: "document decode failed").take(240)
