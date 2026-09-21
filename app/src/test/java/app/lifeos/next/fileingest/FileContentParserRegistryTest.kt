package app.lifeos.next.fileingest

import app.lifeos.next.AndroidFileCategory
import app.lifeos.next.AndroidFileClassification
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileContentParserRegistryTest {
    @Test
    fun `plain text extraction reads actual local content`() {
        val root = Files.createTempDirectory("lifeos-file-parser").toFile()
        try {
            val file = root.resolve("notes.txt")
            file.writeText("hello LIFEOS\nsecond line")

            val extraction = FileContentParserRegistry().extract(
                file = file,
                classification = document("text/plain"),
                maxOutputBytes = 16 * 1024,
            )

            assertEquals(FileDecodeState.DECODED, extraction.state)
            assertEquals("plain-text", extraction.parserId)
            assertTrue(extraction.text.orEmpty().contains("hello LIFEOS"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `text extraction is bounded instead of reading entire file`() {
        val root = Files.createTempDirectory("lifeos-file-parser-bound").toFile()
        try {
            val file = root.resolve("large.txt")
            file.writeText("x".repeat(32 * 1024))

            val extraction = FileContentParserRegistry().extract(
                file = file,
                classification = document("text/plain"),
                maxOutputBytes = 1024,
            )

            assertEquals(FileDecodeState.TRUNCATED, extraction.state)
            assertTrue(extraction.text.orEmpty().length <= 1024)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `encrypted files remain opaque even when extension looks textual`() {
        val root = Files.createTempDirectory("lifeos-file-parser-encrypted").toFile()
        try {
            val file = root.resolve("secret.txt")
            file.writeText("must not decode")

            val extraction = FileContentParserRegistry().extract(
                file = file,
                classification = document(
                    mimeType = "text/plain",
                    encrypted = true,
                ),
                maxOutputBytes = 16 * 1024,
            )

            assertEquals(FileDecodeState.ENCRYPTED_OPAQUE, extraction.state)
            assertNull(extraction.text)
            assertNull(extraction.parserId)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `docx xml text is extracted locally without archive expansion`() {
        val root = Files.createTempDirectory("lifeos-file-parser-docx").toFile()
        try {
            val file = root.resolve("sample.docx")
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(
                    "<w:document><w:body><w:p><w:t>Hello Office</w:t></w:p></w:body></w:document>"
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
            }

            val extraction = FileContentParserRegistry().extract(
                file = file,
                classification = document(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                ),
                maxOutputBytes = 16 * 1024,
            )

            assertEquals(FileDecodeState.DECODED, extraction.state)
            assertEquals("zip-xml", extraction.parserId)
            assertTrue(extraction.text.orEmpty().contains("Hello Office"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun document(
        mimeType: String,
        encrypted: Boolean = false,
    ): AndroidFileClassification =
        AndroidFileClassification(
            category = AndroidFileCategory.DOCUMENT,
            mimeType = mimeType,
            whatsapp = false,
            suspectedEncrypted = encrypted,
        )
}
