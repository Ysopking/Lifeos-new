package app.lifeos.next.fileingest

import app.lifeos.next.AndroidFileClassification
import java.io.BufferedInputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

internal class ZipXmlFileContentParser : FileContentParser {
    override val parserId: String = "zip-xml"
    override val parserVersion: String = "zip-xml/v1"

    override fun supports(
        file: File,
        classification: AndroidFileClassification,
    ): Boolean =
        file.extension.lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS

    override fun extract(
        file: File,
        classification: AndroidFileClassification,
        maxOutputBytes: Int,
    ): FileContentExtraction {
        require(maxOutputBytes > 0)
        if (!file.isFile || !file.canRead()) return failed()

        return runCatching {
            val output = StringBuilder()
            var supportedEntries = 0
            var visitedEntries = 0
            var truncated = false
            ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && output.toString().toByteArray(Charsets.UTF_8).size < maxOutputBytes) {
                    visitedEntries += 1
                    if (visitedEntries > MAX_ARCHIVE_ENTRIES) {
                        truncated = true
                        break
                    }
                    if (!entry.isDirectory && supportedEntry(file.extension, entry.name)) {
                        supportedEntries += 1
                        val budget = (maxOutputBytes - output.toString().toByteArray(Charsets.UTF_8).size)
                            .coerceAtLeast(1)
                        val raw = zip.readBounded(minOf(MAX_ENTRY_BYTES, budget * RAW_TO_TEXT_MULTIPLIER))
                        val visible = xmlVisibleText(raw.bytes.toString(Charsets.UTF_8))
                        if (visible.isNotBlank()) {
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(visible)
                        }
                        if (raw.truncated) {
                            truncated = true
                            break
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (supportedEntries == 0) {
                return@runCatching FileContentExtraction(
                    parserId = parserId,
                    parserVersion = parserVersion,
                    state = FileDecodeState.UNSUPPORTED,
                    text = null,
                    extractedChars = 0,
                )
            }

            val boundedText = truncateUtf8(output.toString().trim(), maxOutputBytes)
            FileContentExtraction(
                parserId = parserId,
                parserVersion = parserVersion,
                state = if (truncated || boundedText.second) {
                    FileDecodeState.TRUNCATED
                } else {
                    FileDecodeState.DECODED
                },
                text = boundedText.first.takeIf(String::isNotBlank),
                extractedChars = boundedText.first.length,
            )
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

    private fun supportedEntry(extension: String, name: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT)
        val normalized = name.replace('\\', '/').lowercase(Locale.ROOT)
        return when (ext) {
            "docx" -> normalized == "word/document.xml" ||
                normalized.startsWith("word/header") ||
                normalized.startsWith("word/footer")
            "xlsx" -> normalized == "xl/sharedstrings.xml" ||
                normalized.startsWith("xl/worksheets/sheet")
            "pptx" -> normalized.startsWith("ppt/slides/slide") &&
                normalized.endsWith(".xml")
            "odt", "ods", "odp" -> normalized == "content.xml"
            "epub" -> normalized.endsWith(".xhtml") ||
                normalized.endsWith(".html") ||
                normalized.endsWith(".htm")
            else -> false
        }
    }

    private fun xmlVisibleText(xml: String): String {
        val out = StringBuilder(xml.length.coerceAtMost(128 * 1024))
        var insideTag = false
        var previousSpace = false
        for (char in xml) {
            when {
                char == '<' -> insideTag = true
                char == '>' -> {
                    insideTag = false
                    if (!previousSpace && out.isNotEmpty()) {
                        out.append(' ')
                        previousSpace = true
                    }
                }
                insideTag -> Unit
                char.isWhitespace() -> {
                    if (!previousSpace && out.isNotEmpty()) {
                        out.append(' ')
                        previousSpace = true
                    }
                }
                else -> {
                    out.append(char)
                    previousSpace = false
                }
            }
        }
        return out.toString()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .trim()
    }

    private fun truncateUtf8(value: String, maxBytes: Int): Pair<String, Boolean> {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value to false
        var low = 0
        var high = value.length
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (value.substring(0, mid).toByteArray(Charsets.UTF_8).size <= maxBytes) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return value.substring(0, low).trimEnd() to true
    }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub")
        const val MAX_ARCHIVE_ENTRIES = 512
        const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
        const val RAW_TO_TEXT_MULTIPLIER = 4
    }
}
