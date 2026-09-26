package app.lifeos.next

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidSharedFilesInitialDataSourceTest {
    @Test
    fun `encrypted WhatsApp backup stays opaque and explicitly classified`() {
        val classification = AndroidFileMetadataClassifier.classify(
            relativePath = "Android/media/com.whatsapp/WhatsApp/Databases/msgstore.db.crypt15",
            displayName = "msgstore.db.crypt15",
            mimeType = null,
        )

        assertEquals(AndroidFileCategory.WHATSAPP, classification.category)
        assertTrue(classification.whatsapp)
        assertTrue(classification.suspectedEncrypted)
        assertEquals("application/octet-stream", classification.mimeType)
    }

    @Test
    fun `WhatsApp document keeps its real file category while retaining provenance hint`() {
        val classification = AndroidFileMetadataClassifier.classify(
            relativePath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/report.pdf",
            displayName = "report.pdf",
            mimeType = "application/pdf",
        )

        assertEquals(AndroidFileCategory.DOCUMENT, classification.category)
        assertTrue(classification.whatsapp)
        assertFalse(classification.suspectedEncrypted)
    }

    @Test
    fun `unknown file type is retained instead of silently dropped`() {
        val classification = AndroidFileMetadataClassifier.classify(
            relativePath = "Download/device-state.weirdfmt",
            displayName = "device-state.weirdfmt",
            mimeType = null,
        )

        assertEquals(AndroidFileCategory.UNKNOWN, classification.category)
        assertEquals("application/octet-stream", classification.mimeType)
    }

    @Test
    fun `filesystem page resumes deterministically and leaves media to MediaStore adapters`() {
        val root = Files.createTempDirectory("lifeos-files").toFile()
        try {
            root.resolve("a.txt").writeText("alpha")
            root.resolve("b.bin").writeBytes(byteArrayOf(1, 2, 3))
            root.resolve("c.zip").writeBytes(byteArrayOf(4, 5))
            root.resolve("d.jpg").writeBytes(byteArrayOf(6, 7, 8))

            val mime: (String) -> String? = { name ->
                when (name.substringAfterLast('.')) {
                    "txt" -> "text/plain"
                    "zip" -> "application/zip"
                    "jpg" -> "image/jpeg"
                    else -> null
                }
            }

            val first = AndroidFilesystemMetadataPager.page(root, null, 2, mime)
            assertEquals(2, first.records.size)
            assertFalse(first.complete)
            assertEquals("b.bin", first.nextPosition)
            assertEquals(listOf("a.txt", "b.bin"), first.records.map(::relativePath))
            val textRecord = first.records.single { relativePath(it) == "a.txt" }
            val binaryRecord = first.records.single { relativePath(it) == "b.bin" }
            assertEquals("DECODED", field(textRecord.payload, "decode_state"))
            assertEquals("UNSUPPORTED", field(binaryRecord.payload, "decode_state"))
            assertTrue(field(textRecord.payload, "content_excerpt").contains("alpha"))
            assertTrue(textRecord.tags.any { it.startsWith("document:file-") })
            assertTrue(textRecord.tags.any { it == "file-parser:plain-text" })

            val replay = AndroidFilesystemMetadataPager.page(root, null, 2, mime)
            assertEquals(first.records.map { it.recordId }, replay.records.map { it.recordId })

            val second = AndroidFilesystemMetadataPager.page(root, first.nextPosition, 2, mime)
            assertTrue(second.complete)
            assertEquals(null, second.nextPosition)
            assertEquals(listOf("c.zip"), second.records.map(::relativePath))
            assertTrue(second.records.none { relativePath(it) == "d.jpg" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `nested files emit stable containment evidence for LifeGraph`() {
        val root = Files.createTempDirectory("lifeos-file-relations").toFile()
        try {
            val folder = root.resolve("ProjectA").apply { mkdirs() }
            folder.resolve("notes.md").writeText("project memory")

            val record = AndroidFilesystemMetadataPager.page(root, null, 10) { "text/markdown" }
                .records
                .single()

            assertTrue(record.tags.any { it.startsWith("document:file-") })
            assertTrue(record.tags.any { it.startsWith("document:folder-") })
            assertTrue(record.tags.any {
                it.startsWith("entity-relationship:CONTAINED_IN|DOCUMENT:file-")
            })
            assertEquals("DECODED", field(record.payload, "decode_state"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `metadata state changes without changing the stable file identity`() {
        val root = Files.createTempDirectory("lifeos-file-state").toFile()
        try {
            val file = root.resolve("note.txt")
            file.writeText("one")
            val first = AndroidFilesystemMetadataPager.page(root, null, 10) { "text/plain" }.records.single()
            val firstIdentity = field(first.payload, "file_identity")
            val firstState = field(first.payload, "state_fingerprint")

            file.writeText("a different length")
            val second = AndroidFilesystemMetadataPager.page(root, null, 10) { "text/plain" }.records.single()

            assertEquals(firstIdentity, field(second.payload, "file_identity"))
            assertNotEquals(firstState, field(second.payload, "state_fingerprint"))
            assertNotEquals(first.recordId, second.recordId)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun relativePath(record: app.lifeos.core.runtime.life.LifeSourceRecord): String =
        field(record.payload, "relative_path")

    private fun field(payload: String, key: String): String = payload.lineSequence()
        .first { it.startsWith("$key=") }
        .substringAfter('=')
}
