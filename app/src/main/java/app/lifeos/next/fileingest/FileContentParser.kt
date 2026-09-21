package app.lifeos.next.fileingest

import app.lifeos.next.AndroidFileClassification
import java.io.File

internal enum class FileDecodeState {
    DECODED,
    TRUNCATED,
    UNSUPPORTED,
    ENCRYPTED_OPAQUE,
    READ_FAILED,
}

internal data class FileContentExtraction(
    val parserId: String?,
    val parserVersion: String?,
    val state: FileDecodeState,
    val text: String?,
    val extractedChars: Int,
) {
    init {
        require(extractedChars >= 0)
        require(text == null || text.length == extractedChars)
        require(parserId == null || parserId.isNotBlank())
        require(parserVersion == null || parserVersion.isNotBlank())
    }
}

internal interface FileContentParser {
    val parserId: String
    val parserVersion: String

    fun supports(
        file: File,
        classification: AndroidFileClassification,
    ): Boolean

    fun extract(
        file: File,
        classification: AndroidFileClassification,
        maxOutputBytes: Int,
    ): FileContentExtraction
}
