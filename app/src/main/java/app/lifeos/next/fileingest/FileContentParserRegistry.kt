package app.lifeos.next.fileingest

import app.lifeos.next.AndroidFileClassification
import java.io.File

internal class FileContentParserRegistry(
    private val parsers: List<FileContentParser> = listOf(
        PlainTextFileContentParser(),
        ZipXmlFileContentParser(),
    ),
) {
    init {
        require(parsers.map { it.parserId }.distinct().size == parsers.size) {
            "File content parser ids must be unique"
        }
    }

    fun extract(
        file: File,
        classification: AndroidFileClassification,
        maxOutputBytes: Int,
    ): FileContentExtraction {
        require(maxOutputBytes > 0)
        if (classification.suspectedEncrypted) {
            return FileContentExtraction(
                parserId = null,
                parserVersion = null,
                state = FileDecodeState.ENCRYPTED_OPAQUE,
                text = null,
                extractedChars = 0,
            )
        }

        val parser = parsers.firstOrNull { it.supports(file, classification) }
            ?: return FileContentExtraction(
                parserId = null,
                parserVersion = null,
                state = FileDecodeState.UNSUPPORTED,
                text = null,
                extractedChars = 0,
            )

        return parser.extract(file, classification, maxOutputBytes)
    }
}
