package app.lifeos.next

import app.lifeos.core.runtime.android.AndroidFileRef
import app.lifeos.core.runtime.android.AndroidFileRevision
import app.lifeos.core.runtime.android.FileCopyRequest
import app.lifeos.core.runtime.android.FileMoveRequest
import app.lifeos.core.runtime.android.FilePayload
import app.lifeos.core.runtime.android.FileReadRequest
import app.lifeos.core.runtime.android.FileSearchQuery
import app.lifeos.core.runtime.android.FileWriteRequest
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileActionHostTest {
    @Test
    fun exact_bounded_read_uses_shared_root_and_revision() = runTest {
        fixture().use { fixture ->
            val file = fixture.file("Documents/report.txt", "hello")
            val ref = fixture.ref("Documents/report.txt")
            val revision = fixture.revision(ref, file)
            val result = fixture.host.read(
                FileReadRequest(ref, expectedRevision = revision, maxBytes = 5)
            )

            assertEquals(revision, result.revision)
            assertContentEquals("hello".encodeToByteArray(), result.payload.copyBytes())
        }
    }

    @Test
    fun write_requires_explicit_expected_revision_before_replace() = runTest {
        fixture().use { fixture ->
            val existing = fixture.file("Documents/report.txt", "old")
            val ref = fixture.ref("Documents/report.txt")

            assertFailsWith<IllegalArgumentException> {
                fixture.host.write(
                    FileWriteRequest(
                        destination = ref,
                        payload = FilePayload.create("new".encodeToByteArray()),
                    )
                )
            }
            assertEquals("old", existing.readText())

            val revision = fixture.revision(ref, existing)
            fixture.host.write(
                FileWriteRequest(
                    destination = ref,
                    payload = FilePayload.create("new".encodeToByteArray()),
                    expectedDestinationRevision = revision,
                )
            )

            assertEquals("new", existing.readText())
            assertEquals(1, fixture.mutations)
        }
    }

    @Test
    fun copy_preserves_source_and_move_removes_source_only_after_publish() = runTest {
        fixture().use { fixture ->
            val source = fixture.file("Download/source.txt", "payload")
            val sourceRef = fixture.ref("Download/source.txt")
            val sourceRevision = fixture.revision(sourceRef, source)

            val copyRef = fixture.ref("Documents/copy.txt")
            fixture.host.copy(
                FileCopyRequest(
                    source = sourceRef,
                    expectedSourceRevision = sourceRevision,
                    destination = copyRef,
                )
            )
            assertTrue(source.exists())
            assertEquals("payload", fixture.resolve("Documents/copy.txt").readText())

            val moveSource = fixture.file("Download/move.txt", "move")
            val moveSourceRef = fixture.ref("Download/move.txt")
            val moveRevision = fixture.revision(moveSourceRef, moveSource)
            val movedRef = fixture.ref("Documents/moved.txt")
            fixture.host.move(
                FileMoveRequest(
                    source = moveSourceRef,
                    expectedSourceRevision = moveRevision,
                    destination = movedRef,
                )
            )

            assertFalse(moveSource.exists())
            assertEquals("move", fixture.resolve("Documents/moved.txt").readText())
            assertEquals(2, fixture.mutations)
        }
    }

    @Test
    fun symbolic_link_escape_is_rejected() = runTest {
        fixture().use { fixture ->
            val outside = createTempDirectory("lifeos-b406-outside").toFile()
            try {
                val secret = File(outside, "secret.txt").apply { writeText("secret") }
                val link = fixture.resolve("link")
                Files.createSymbolicLink(link.toPath(), outside.toPath())

                assertFailsWith<IllegalArgumentException> {
                    fixture.host.read(
                        FileReadRequest(
                            ref = fixture.ref("link/" + secret.name),
                            maxBytes = 64,
                        )
                    )
                }
            } finally {
                outside.deleteRecursively()
            }
        }
    }

    @Test
    fun inventory_search_is_bounded_by_query_contract() = runTest {
        fixture().use { fixture ->
            fixture.inventory.results += indexed("vol", "Documents/a.txt", 1)
            fixture.inventory.results += indexed("vol", "Documents/b.txt", 2)

            val result = fixture.host.search(FileSearchQuery("Documents", maxResults = 1))

            assertEquals(1, result.size)
            assertEquals("Documents/a.txt", result.single().ref.relativePath)
            assertEquals(1, fixture.inventory.lastLimit)
        }
    }

    private fun fixture(): Fixture = Fixture()

    private fun indexed(
        volume: String,
        path: String,
        modified: Long,
    ): StorageIndexedFile = StorageIndexedFile(
        volumeId = volume,
        relativePath = path,
        absolutePath = "/unused/" + path,
        sizeBytes = 1L,
        modifiedAtMillis = modified,
        category = AndroidFileCategory.DOCUMENT,
        suspectedEncrypted = false,
        contentFingerprint = null,
    )

    private class Fixture : AutoCloseable {
        private val root = createTempDirectory("lifeos-b406-root").toFile()
        val inventory = FakeInventory()
        var mutations = 0
        val host = AndroidFileActionHost(
            inventory = inventory,
            roots = { listOf(SharedStorageRoot("vol", root)) },
            onMutation = { mutations += 1 },
        )

        fun ref(path: String): AndroidFileRef =
            AndroidFileRef.create("vol", path)

        fun resolve(path: String): File =
            File(root, path)

        fun file(path: String, text: String): File =
            resolve(path).apply {
                parentFile?.mkdirs()
                writeText(text)
            }

        fun revision(
            ref: AndroidFileRef,
            file: File,
        ): AndroidFileRevision = AndroidFileRevision(
            ref = ref,
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified(),
        )

        override fun close() {
            root.deleteRecursively()
        }
    }

    private class FakeInventory : AndroidFileInventoryView {
        val results = mutableListOf<StorageIndexedFile>()
        var lastLimit: Int? = null

        override fun search(query: String, limit: Int): List<StorageIndexedFile> {
            lastLimit = limit
            return results.filter {
                query.isBlank() || it.relativePath.contains(query, ignoreCase = true)
            }.take(limit)
        }

        override fun load(
            volumeId: String,
            relativePath: String,
        ): StorageInventoryEntry? = null
    }
}
