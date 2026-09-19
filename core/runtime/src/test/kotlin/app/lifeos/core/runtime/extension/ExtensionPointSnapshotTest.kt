package app.lifeos.core.runtime.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionPointSnapshotTest {
    @Test
    fun freezesAllWorldExtensionPointsDeterministically() {
        val registry = registry()
        val byKind = registry.entries.associateBy { it.manifest.kind }

        val registrations = listOf(
            registration(
                byKind.getValue(ExtensionKind.WORLD_PROJECTION_PACK),
                ExtensionPointKind.WORLD_MODEL_PROJECTION,
            ),
            registration(
                byKind.getValue(ExtensionKind.WORLD_EQUATION_PACK),
                ExtensionPointKind.WORLD_EQUATION_CANDIDATE,
            ),
            registration(
                byKind.getValue(ExtensionKind.WORLD_SIGNAL_PACK),
                ExtensionPointKind.WORLD_SIGNAL_PROJECTOR,
            ),
            registration(
                byKind.getValue(ExtensionKind.WORLD_TOPOLOGY_PACK),
                ExtensionPointKind.WORLD_TOPOLOGY,
            ),
        )

        val first = ExtensionPointSnapshot.create(registry, registrations)
        val second = ExtensionPointSnapshot.create(registry, registrations.reversed())

        assertEquals(first.id, second.id)
        assertEquals(
            ExtensionPointKind.entries.toSet(),
            first.registrations.mapTo(linkedSetOf()) { it.point },
        )
        assertFalse(first.directRegistryMutationAllowed)
    }

    @Test
    fun rejectsProviderBoundToWrongExtensionKind() {
        val registry = registry()
        val signal = registry.entries.first {
            it.manifest.kind == ExtensionKind.WORLD_SIGNAL_PACK
        }

        assertFailsWith<IllegalArgumentException> {
            ExtensionPointSnapshot.create(
                registry,
                listOf(
                    registration(
                        signal,
                        ExtensionPointKind.WORLD_EQUATION_CANDIDATE,
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsRegistrationWithMismatchedFrozenContractFingerprint() {
        val registry = registry()
        val projection = registry.entries.first {
            it.manifest.kind == ExtensionKind.WORLD_PROJECTION_PACK
        }
        val valid = registration(
            projection,
            ExtensionPointKind.WORLD_MODEL_PROJECTION,
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionPointSnapshot.create(
                registry,
                listOf(valid.copy(contractFingerprint = "wrong-contract")),
            )
        }
    }

    @Test
    fun worldEquationPointRemainsCandidateOnly() {
        val registry = registry()
        val equation = registry.entries.first {
            it.manifest.kind == ExtensionKind.WORLD_EQUATION_PACK
        }
        val snapshot = ExtensionPointSnapshot.create(
            registry,
            listOf(
                registration(
                    equation,
                    ExtensionPointKind.WORLD_EQUATION_CANDIDATE,
                ),
            ),
        )

        assertEquals(
            ExtensionRegistrationMode.EVOLUTION_CANDIDATE_ONLY,
            equation.manifest.registrationMode,
        )
        assertFalse(snapshot.directRegistryMutationAllowed)
    }

    private fun registry(): ExtensionRegistrySnapshot {
        val entries = listOf(
            entry("signal", ExtensionKind.WORLD_SIGNAL_PACK),
            entry("topology", ExtensionKind.WORLD_TOPOLOGY_PACK),
            entry("equation", ExtensionKind.WORLD_EQUATION_PACK),
            entry("projection", ExtensionKind.WORLD_PROJECTION_PACK),
        )
        return ExtensionRegistrySnapshot.create(entries)
    }

    private fun registration(
        entry: ExtensionRegistryEntry,
        point: ExtensionPointKind,
    ) = ExtensionPointRegistration(
        extensionRef = entry.ref,
        providerId = "provider.${entry.manifest.extensionId.value}",
        point = point,
        contractFingerprint = when (point) {
            ExtensionPointKind.WORLD_EQUATION_CANDIDATE ->
                entry.worldContract.coefficientSchemaFingerprint.value
            ExtensionPointKind.WORLD_SIGNAL_PROJECTOR,
            ExtensionPointKind.WORLD_TOPOLOGY,
            ExtensionPointKind.WORLD_MODEL_PROJECTION ->
                entry.worldContract.projectionContractFingerprint.value
        },
    )

    private fun entry(
        name: String,
        kind: ExtensionKind,
    ): ExtensionRegistryEntry = ExtensionRegistryEntry(
        manifest = ExtensionManifest(
            extensionId = ExtensionId("extension.$name"),
            version = ExtensionVersion("1.0.0"),
            kind = kind,
            providerId = "lifeos.test",
            entrypoints = setOf(
                ExtensionEntrypoint(
                    contract = "contract.$name",
                    implementationId = "implementation.$name",
                ),
            ),
        ),
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
    )
}
