package app.lifeos.core.runtime.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ExtensionAbiTest {
    @Test
    fun exposesAllB146WorldExtensionKindsWithoutDirectRegistryAuthority() {
        val manifests = ExtensionKind.entries.map { kind ->
            manifest(kind = kind)
        }

        assertEquals(
            setOf(
                ExtensionKind.WORLD_SIGNAL_PACK,
                ExtensionKind.WORLD_TOPOLOGY_PACK,
                ExtensionKind.WORLD_EQUATION_PACK,
                ExtensionKind.WORLD_PROJECTION_PACK,
            ),
            manifests.mapTo(linkedSetOf()) { it.kind },
        )
        manifests.forEach { extension ->
            assertFalse(extension.directRegistryMutationAllowed)
        }
    }

    @Test
    fun worldEquationPacksAreCandidateOnly() {
        val equationPack = manifest(kind = ExtensionKind.WORLD_EQUATION_PACK)

        assertEquals(
            ExtensionRegistrationMode.EVOLUTION_CANDIDATE_ONLY,
            equationPack.registrationMode,
        )

        assertFailsWith<IllegalArgumentException> {
            manifest(
                kind = ExtensionKind.WORLD_EQUATION_PACK,
                registrationMode = ExtensionRegistrationMode.EXTENSION_POINT_CANDIDATE,
            )
        }
    }

    @Test
    fun nonEquationWorldPacksUseExtensionPointCandidateMode() {
        val kinds = listOf(
            ExtensionKind.WORLD_SIGNAL_PACK,
            ExtensionKind.WORLD_TOPOLOGY_PACK,
            ExtensionKind.WORLD_PROJECTION_PACK,
        )

        kinds.forEach { kind ->
            assertEquals(
                ExtensionRegistrationMode.EXTENSION_POINT_CANDIDATE,
                manifest(kind).registrationMode,
            )
        }
    }

    @Test
    fun manifestFingerprintIsDeterministicAcrossEntrypointOrder() {
        val a = ExtensionEntrypoint("world.signal.projector", "projector.alpha")
        val b = ExtensionEntrypoint("world.signal.schema", "schema.alpha")

        val first = manifest(
            kind = ExtensionKind.WORLD_SIGNAL_PACK,
            entrypoints = linkedSetOf(a, b),
        )
        val second = manifest(
            kind = ExtensionKind.WORLD_SIGNAL_PACK,
            entrypoints = linkedSetOf(b, a),
        )

        assertEquals(first.fingerprint(), second.fingerprint())
    }

    @Test
    fun duplicateContractsFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            manifest(
                kind = ExtensionKind.WORLD_PROJECTION_PACK,
                entrypoints = setOf(
                    ExtensionEntrypoint("world.projection", "projection.one"),
                    ExtensionEntrypoint("world.projection", "projection.two"),
                ),
            )
        }
    }

    private fun manifest(
        kind: ExtensionKind,
        registrationMode: ExtensionRegistrationMode = kind.requiredRegistrationMode(),
        entrypoints: Set<ExtensionEntrypoint> = setOf(
            ExtensionEntrypoint(
                contract = "contract.${kind.name.lowercase()}",
                implementationId = "implementation.${kind.name.lowercase()}",
            ),
        ),
    ) = ExtensionManifest(
        extensionId = ExtensionId("extension.${kind.name.lowercase()}"),
        version = ExtensionVersion("1.0.0"),
        kind = kind,
        providerId = "lifeos.test",
        entrypoints = entrypoints,
        registrationMode = registrationMode,
    )
}
