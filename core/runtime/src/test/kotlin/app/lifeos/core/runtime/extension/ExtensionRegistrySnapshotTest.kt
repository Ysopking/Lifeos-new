package app.lifeos.core.runtime.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionRegistrySnapshotTest {
    @Test
    fun createsDeterministicTopologicalOrder() {
        val signal = entry("signal", ExtensionKind.WORLD_SIGNAL_PACK)
        val projection = entry(
            "projection",
            ExtensionKind.WORLD_PROJECTION_PACK,
            dependencies = setOf(signal.ref),
        )
        val topology = entry(
            "topology",
            ExtensionKind.WORLD_TOPOLOGY_PACK,
            dependencies = setOf(signal.ref),
        )

        val first = ExtensionRegistrySnapshot.create(
            listOf(projection, signal, topology),
        )
        val second = ExtensionRegistrySnapshot.create(
            listOf(topology, projection, signal),
        )

        assertEquals(first.id, second.id)
        assertEquals(
            listOf(signal.ref, projection.ref, topology.ref),
            first.topologicalOrder,
        )
        assertFalse(first.directActivationAllowed)
    }

    @Test
    fun rejectsMissingDependency() {
        val missing = ExtensionRevisionRef(
            ExtensionId("extension.missing"),
            ExtensionVersion("1.0.0"),
        )
        val projection = entry(
            "projection",
            ExtensionKind.WORLD_PROJECTION_PACK,
            dependencies = setOf(missing),
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionRegistrySnapshot.create(listOf(projection))
        }
    }

    @Test
    fun rejectsDependencyCycles() {
        val aRef = ExtensionRevisionRef(ExtensionId("extension.a"), ExtensionVersion("1.0.0"))
        val bRef = ExtensionRevisionRef(ExtensionId("extension.b"), ExtensionVersion("1.0.0"))

        val a = entry(
            name = "a",
            kind = ExtensionKind.WORLD_SIGNAL_PACK,
            dependencies = setOf(bRef),
        )
        val b = entry(
            name = "b",
            kind = ExtensionKind.WORLD_PROJECTION_PACK,
            dependencies = setOf(aRef),
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionRegistrySnapshot.create(listOf(a, b))
        }
    }

    @Test
    fun rejectsMultipleSelectedVersionsOfSameExtension() {
        val first = entry("signal", ExtensionKind.WORLD_SIGNAL_PACK, version = "1.0.0")
        val second = entry("signal", ExtensionKind.WORLD_SIGNAL_PACK, version = "1.1.0")

        assertFailsWith<IllegalArgumentException> {
            ExtensionRegistrySnapshot.create(listOf(first, second))
        }
    }

    private fun entry(
        name: String,
        kind: ExtensionKind,
        version: String = "1.0.0",
        dependencies: Set<ExtensionRevisionRef> = emptySet(),
    ): ExtensionRegistryEntry {
        val manifest = ExtensionManifest(
            extensionId = ExtensionId("extension.$name"),
            version = ExtensionVersion(version),
            kind = kind,
            providerId = "lifeos.test",
            entrypoints = setOf(
                ExtensionEntrypoint(
                    contract = "contract.$name",
                    implementationId = "implementation.$name",
                ),
            ),
        )
        return ExtensionRegistryEntry(
            manifest = manifest,
            worldContract = ExtensionWorldContract(
                worldSignalSchemaVersion = WorldSignalSchemaVersion(1, 0),
                worldNodeSchemaVersion = WorldNodeSchemaVersion(1, 0),
                worldEquationVersion = WorldEquationVersion(
                    id = "lifeos-world-informational-v1",
                    major = 1,
                    minor = 0,
                ),
                coefficientSchemaFingerprint = CoefficientSchemaFingerprint("coeff-v1"),
                projectionContractFingerprint = ProjectionContractFingerprint("projection-v1"),
            ),
            dependencies = dependencies,
        )
    }
}
