package app.lifeos.core.document

import java.security.MessageDigest

enum class DocumentFormat {
    TEXT,
    MARKDOWN,
    CSV,
    TSV,
    JSON,
    XML,
    HTML,
    DOCX,
    XLSX,
    PDF,
    ZIP,
    UNKNOWN,
}

enum class DocumentBlockKind {
    PARAGRAPH,
    HEADING,
    LIST_ITEM,
    TABLE_ROW,
    TABLE_CELL,
    SHEET,
    PAGE,
    METADATA,
    ARCHIVE_ENTRY,
    RAW_TEXT,
}

data class DocumentSourceLocator(
    val blockIndex: Int,
    val page: Int? = null,
    val sheet: String? = null,
    val row: Int? = null,
    val column: Int? = null,
    val cell: String? = null,
    val archivePath: String? = null,
) {
    init {
        require(blockIndex >= 0)
        require(page == null || page >= 1)
        require(row == null || row >= 1)
        require(column == null || column >= 1)
        require(cell == null || cell.isNotBlank())
        require(archivePath == null || archivePath.isNotBlank())
    }

    fun stableKey(): String = buildString {
        append("block=").append(blockIndex)
        page?.let { append("|page=").append(it) }
        sheet?.let { append("|sheet=").append(it) }
        row?.let { append("|row=").append(it) }
        column?.let { append("|column=").append(it) }
        cell?.let { append("|cell=").append(it) }
        archivePath?.let { append("|archive=").append(it) }
    }
}

data class DocumentBlock(
    val kind: DocumentBlockKind,
    val text: String,
    val locator: DocumentSourceLocator,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(text.isNotBlank())
        require(attributes.keys.none { it.isBlank() })
    }
}

data class DocumentEnvelope(
    val sourceId: String,
    val fileName: String,
    val declaredMimeType: String?,
    val detectedFormat: DocumentFormat,
    val decoderId: String,
    val decoderVersion: String,
    val contentSha256: String,
    val blocks: List<DocumentBlock>,
    val warnings: List<String> = emptyList(),
    val archivePath: String? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(fileName.isNotBlank())
        require(decoderId.isNotBlank())
        require(decoderVersion.isNotBlank())
        require(contentSha256.matches(Regex("[0-9a-f]{64}")))
        require(blocks.map { it.locator.stableKey() }.distinct().size == blocks.size)
    }
}

data class DocumentInput(
    val sourceId: String,
    val fileName: String,
    val declaredMimeType: String? = null,
    val bytes: ByteArray,
    val archivePath: String? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(fileName.isNotBlank())
    }

    val contentSha256: String by lazy(LazyThreadSafetyMode.NONE) { sha256(bytes) }
}

data class DocumentDecodeLimits(
    val maxInputBytes: Int = 24 * 1024 * 1024,
    val maxTextChars: Int = 4_000_000,
    val maxBlocks: Int = 8_000,
    val maxArchiveDepth: Int = 3,
    val maxArchiveEntries: Int = 512,
    val maxArchiveEntryBytes: Int = 12 * 1024 * 1024,
    val maxArchiveExpandedBytes: Long = 64L * 1024L * 1024L,
    val maxCompressionRatio: Double = 120.0,
) {
    init {
        require(maxInputBytes > 0)
        require(maxTextChars > 0)
        require(maxBlocks > 0)
        require(maxArchiveDepth in 0..16)
        require(maxArchiveEntries > 0)
        require(maxArchiveEntryBytes > 0)
        require(maxArchiveExpandedBytes > 0)
        require(maxCompressionRatio >= 1.0)
    }
}

sealed interface DocumentDecodeResult {
    data class Decoded(val envelope: DocumentEnvelope) : DocumentDecodeResult
    data class Unsupported(
        val input: DocumentInput,
        val detectedFormat: DocumentFormat,
        val reason: String,
    ) : DocumentDecodeResult
    data class Rejected(
        val input: DocumentInput,
        val detectedFormat: DocumentFormat,
        val reason: String,
    ) : DocumentDecodeResult
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun normalizeArchivePath(parent: String?, child: String): String? {
    val normalizedChild = child.replace('\\', '/').trimStart('/')
    if (normalizedChild.isBlank()) return null
    val parts = normalizedChild.split('/').filter { it.isNotBlank() && it != "." }
    if (parts.any { it == ".." }) return null
    val safeChild = parts.joinToString("/")
    if (safeChild.isBlank()) return null
    return parent?.takeIf { it.isNotBlank() }?.let { "$it!/$safeChild" } ?: safeChild
}
