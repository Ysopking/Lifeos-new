package app.lifeos.core.data.security

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurableSchemaGenerationStoreTest {
    @Test
    fun `new store initializes current schema generation`() {
        val root = tempRoot()
        val descriptor = descriptor(version = 2, contract = "contract-v2")
        val store = DurableSchemaGenerationStore(root, descriptor)

        val prepared = store.prepare(
            legacyExists = { false },
            migrate = { _, _ -> error("no migration expected") },
            validate = { target -> assertTrue(target.isDirectory) },
        )

        assertEquals(1L, prepared.activeGeneration.generation)
        assertEquals(2, prepared.activeGeneration.schemaVersion)
        assertFalse(prepared.migrated)
        assertTrue(prepared.activeRoot.isDirectory)
    }

    @Test
    fun `legacy migration copies into generation and retains legacy source`() {
        val root = tempRoot()
        val legacy = root.resolve("head.bin")
        root.mkdirs()
        legacy.writeText("legacy")
        val store = DurableSchemaGenerationStore(
            root,
            descriptor(version = 2, contract = "contract-v2"),
        )

        val prepared = store.prepare(
            legacyExists = { legacy.isFile },
            migrate = { source, target ->
                assertTrue(source is DurableSchemaSource.Legacy)
                target.resolve("head.bin").writeText(source.root.resolve("head.bin").readText())
            },
            validate = { target ->
                assertEquals("legacy", target.resolve("head.bin").readText())
            },
        )

        assertTrue(prepared.migrated)
        assertTrue(legacy.isFile)
        assertEquals("legacy", prepared.activeRoot.resolve("head.bin").readText())
        assertEquals(root, prepared.previousRoot)
    }

    @Test
    fun `schema upgrade creates next generation and retains previous generation`() {
        val root = tempRoot()
        val v1 = DurableSchemaGenerationStore(
            root,
            descriptor(version = 1, contract = "contract-v1"),
        ).prepare(
            legacyExists = { false },
            migrate = { _, _ -> error("no source") },
            validate = {},
        )
        v1.activeRoot.resolve("payload").writeText("v1")

        val v2 = DurableSchemaGenerationStore(
            root,
            descriptor(version = 2, contract = "contract-v2"),
        ).prepare(
            legacyExists = { false },
            migrate = { source, target ->
                assertTrue(source is DurableSchemaSource.Generation)
                target.resolve("payload").writeText(
                    source.root.resolve("payload").readText() + "-migrated"
                )
            },
            validate = { target ->
                assertTrue(target.resolve("payload").readText().endsWith("migrated"))
            },
        )

        assertEquals(2L, v2.activeGeneration.generation)
        assertEquals(2, v2.activeGeneration.schemaVersion)
        assertEquals(v1.activeRoot, v2.previousRoot)
        assertTrue(v1.activeRoot.isDirectory)
        assertEquals("v1-migrated", v2.activeRoot.resolve("payload").readText())
    }

    @Test
    fun `same version fingerprint change fails closed`() {
        val root = tempRoot()
        DurableSchemaGenerationStore(
            root,
            descriptor(version = 2, contract = "contract-a"),
        ).prepare(
            legacyExists = { false },
            migrate = { _, _ -> error("no migration") },
            validate = {},
        )

        assertFailsWith<IllegalArgumentException> {
            DurableSchemaGenerationStore(
                root,
                descriptor(version = 2, contract = "contract-b"),
            ).prepare(
                legacyExists = { false },
                migrate = { _, _ -> error("no migration") },
                validate = {},
            )
        }
    }

    private fun descriptor(
        version: Int,
        contract: String,
    ) = DurableSchemaDescriptor(
        storeId = "test.store",
        currentVersion = version,
        minimumReadableVersion = 1,
        schemaFingerprint = DurableSchemaDescriptor.fingerprintOf(contract),
    )

    private fun tempRoot(): File =
        createTempDirectory("lifeos-durable-schema-").toFile().also {
            it.deleteOnExit()
        }
}
