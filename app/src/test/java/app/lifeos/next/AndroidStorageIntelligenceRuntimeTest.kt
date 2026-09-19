package app.lifeos.next

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidStorageIntelligenceRuntimeTest {
    @Test
    fun `all-files pager includes media and resumes deterministically`() {
        val root = Files.createTempDirectory("lifeos-storage-intel").toFile()
        try {
            root.resolve("a.txt").writeText("a")
            root.resolve("b.jpg").writeBytes(byteArrayOf(1, 2, 3))
            root.resolve("c.mp4").writeBytes(byteArrayOf(4, 5, 6))
            root.resolve("sub").mkdirs()
            root.resolve("sub/d.bin").writeBytes(byteArrayOf(7))

            val first = StorageTreePager.page(root, null, 2)
            assertEquals(listOf("a.txt", "b.jpg"), first.files.map { it.relativePath })
            assertFalse(first.complete)
            assertEquals("b.jpg", first.nextPosition)

            val second = StorageTreePager.page(root, first.nextPosition, 10)
            assertEquals(listOf("c.mp4", "sub/d.bin"), second.files.map { it.relativePath })
            assertTrue(second.complete)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `lifeos trash is excluded from the all-files inventory`() {
        val root = Files.createTempDirectory("lifeos-storage-trash").toFile()
        try {
            root.resolve("keep.txt").writeText("keep")
            root.resolve(StorageTreePager.TRASH_ROOT).mkdirs()
            root.resolve(StorageTreePager.TRASH_ROOT + "/old.txt").writeText("trash")

            val page = StorageTreePager.page(root, null, 10)

            assertEquals(listOf("keep.txt"), page.files.map { it.relativePath })
            assertTrue(page.complete)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `planner keeps canonical copy and only offers duplicate for trash`() {
        val files = listOf(
            indexed("Download/photo-copy.jpg", 100, AndroidFileCategory.IMAGE, "a".repeat(64)),
            indexed("Pictures/photo.jpg", 100, AndroidFileCategory.IMAGE, "a".repeat(64)),
        )

        val candidates = StorageCleanupPlanner.plan(
            duplicateGroups = listOf(files),
            reviewEntries = emptyList(),
        )
        val duplicate = candidates.single { it.kind == StorageCleanupKind.EXACT_DUPLICATE }

        assertEquals("Download/photo-copy.jpg", duplicate.relativePath)
        assertEquals(100, duplicate.reclaimableBytes)
        assertTrue(duplicate.safeToTrashAfterOwnerApproval)
        assertTrue("Pictures/photo.jpg" in duplicate.reason)
    }

    @Test
    fun `protected backups never become automatic duplicate trash candidates`() {
        val files = listOf(
            indexed("Backups/one.db", 100, AndroidFileCategory.BACKUP, "b".repeat(64)),
            indexed("Backups/two.db", 100, AndroidFileCategory.BACKUP, "b".repeat(64)),
        )

        val duplicate = StorageCleanupPlanner.plan(
            duplicateGroups = listOf(files),
            reviewEntries = emptyList(),
        ).single { it.kind == StorageCleanupKind.EXACT_DUPLICATE }

        assertFalse(duplicate.safeToTrashAfterOwnerApproval)
    }

    @Test
    fun `temporary files are candidates but large unique files remain review only`() {
        val files = listOf(
            indexed("Download/cache.tmp", 10, AndroidFileCategory.UNKNOWN, null),
            indexed("Movies/archive.mkv", 3L * 1024L * 1024L * 1024L, AndroidFileCategory.VIDEO, null),
        )

        val candidates = StorageCleanupPlanner.plan(
            duplicateGroups = emptyList(),
            reviewEntries = files,
        )
        assertTrue(candidates.any {
            it.relativePath == "Download/cache.tmp" &&
                it.kind == StorageCleanupKind.TEMPORARY_FILE &&
                it.safeToTrashAfterOwnerApproval
        })
        assertTrue(candidates.any {
            it.relativePath == "Movies/archive.mkv" &&
                it.kind == StorageCleanupKind.LARGE_REVIEW &&
                !it.safeToTrashAfterOwnerApproval
        })
    }

    @Test
    fun `normal documents receive organization suggestions but not deletion authority`() {
        val file = indexed(
            "Download/report.pdf",
            100,
            AndroidFileCategory.DOCUMENT,
            null,
        )

        val candidate = StorageCleanupPlanner.plan(
            duplicateGroups = emptyList(),
            reviewEntries = listOf(file),
        ).single { it.kind == StorageCleanupKind.REORGANIZE }

        assertEquals("Documents/LIFEOS", candidate.suggestedDirectory)
        assertEquals(0L, candidate.reclaimableBytes)
        assertFalse(candidate.safeToTrashAfterOwnerApproval)
    }

    private fun indexed(
        path: String,
        size: Long,
        category: AndroidFileCategory,
        fingerprint: String?,
    ) = StorageIndexedFile(
        volumeId = "primary",
        relativePath = path,
        absolutePath = "/storage/emulated/0/" + path,
        sizeBytes = size,
        modifiedAtMillis = 1L,
        category = category,
        suspectedEncrypted = false,
        contentFingerprint = fingerprint,
    )
}
