package app.lifeos.next.fileingest

import app.lifeos.next.AndroidFileClassification
import java.io.File
import java.io.InputStream

internal class PlainTextFileContentParser : FileContentParser {
    override val parserId: String = "plain-text"
    override val parserVersion: String = "plain-text/v1"

    override fun supports(
        file: File,
        classification: AndroidFileClassification,
    ): Boolean =
        file.extension.lowercase() in EXTENSIONS ||
            classification.mimeType.startsWith("text/") ||
            classification.mimeType in STRUCTURED_TEXT_MIME_TYPES

    override fun extract(
        file: File,
        classification: AndroidFileClassification,
        maxOutputBytes: Int,
    ): FileContentExtraction {
        require(maxOutputBytes > 0)
        if (!file.isFile || !file.canRead()) return failed()

        return runCatching {
            file.inputStream().buffered().use { input ->
                val bounded = input.readBounded(maxOutputBytes)
                val text = bounded.bytes
                    .toString(Charsets.UTF_8)
                    .replace("\u0000", "")
                    .trim()
                FileContentExtraction(
                    parserId = parserId,
                    parserVersion = parserVersion,
                    state = if (bounded.truncated) {
                        FileDecodeState.TRUNCATED
                    } else {
                        FileDecodeState.DECODED
                    },
                    text = text.takeIf(String::isNotBlank),
                    extractedChars = text.length,
                )
            }
        }.getOrElse { failed() }
    }

    private fun failed(): FileContentExtraction =
        FileContentExtraction(
            parserId = parserId,
            parserVersion = parserVersion,
            state = FileDecodeState.READ_FAILED,
            text = null,
            extractedChars = 0,
        )

    private companion object {
        val EXTENSIONS = setOf(
            "txt", "md", "csv", "json", "xml", "html", "htm",
            "log", "yaml", "yml", "ics", "vcf",
        )
        val STRUCTURED_TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/xhtml+xml",
        )
    }
}

internal data class BoundedBytes(
    val bytes: ByteArray,
    val truncated: Boolean,
)

internal fun InputStream.readBounded(maxBytes: Int): BoundedBytes {
    require(maxBytes > 0)
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val read = read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) {
            return BoundedBytes(output.toByteArray(), truncated = false)
        }
        if (read == 0) continue
        output.write(buffer, 0, read)
        remaining -= read
    }
    return BoundedBytes(
        bytes = output.toByteArray(),
        truncated = read() >= 0,
    )
}
