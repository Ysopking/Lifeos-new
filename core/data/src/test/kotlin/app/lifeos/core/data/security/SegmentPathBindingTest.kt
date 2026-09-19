package app.lifeos.core.data.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SegmentPathBindingTest {
    @Test
    fun exactKeyAndRevisionOwnTheOnlyValidPhysicalPath() {
        val root = Files.createTempDirectory("lifeos-segment-path").toFile()
        try {
            val segments = root.resolve("segments")
            assertTrue(segments.mkdirs())
            val binding = SegmentPathBinding<String>(
                ledgerDomain = "test-ledger/v1",
                segmentsDirectory = segments,
                keyFingerprint = ::segmentKeySha256,
                segmentPrefix = "event-",
                segmentSuffix = ".test",
            )

            val exact = binding.segmentFile("alpha", 7L)
            binding.validatePath(exact, "alpha", 7L)

            val wrongKey = segments.resolve(segmentKeySha256("beta")).resolve(exact.name)
            assertFailsWith<IllegalArgumentException> {
                binding.validatePath(wrongKey, "alpha", 7L)
            }
            val wrongRevision = binding.segmentFile("alpha", 8L)
            assertFailsWith<IllegalArgumentException> {
                binding.validatePath(wrongRevision, "alpha", 7L)
            }

            assertNotEquals(
                binding.associatedData(exact).decodeToString(),
                binding.associatedData(wrongKey).decodeToString(),
            )
            assertContentEquals(
                binding.associatedData(exact),
                binding.associatedData(binding.segmentFile("alpha", 7L)),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicBackupIsResolvedAsTheLogicalBaseSegment() {
        val root = Files.createTempDirectory("lifeos-segment-backup").toFile()
        try {
            val segments = root.resolve("segments")
            assertTrue(segments.mkdirs())
            val binding = SegmentPathBinding<String>(
                ledgerDomain = "test-ledger/v1",
                segmentsDirectory = segments,
                keyFingerprint = ::segmentKeySha256,
                segmentPrefix = "event-",
                segmentSuffix = ".test",
            )
            val base = binding.segmentFile("alpha", 1L)
            assertTrue(base.parentFile.mkdirs())
            assertTrue(java.io.File(base.path + ".bak").writeText("backup").let { true })

            val logical = binding.logicalFiles()
            assertTrue(logical.single().path == base.path)
        } finally {
            root.deleteRecursively()
        }
    }
}
