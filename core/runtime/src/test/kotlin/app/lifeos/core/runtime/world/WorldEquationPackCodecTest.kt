package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class WorldEquationPackCodecTest {
    @Test
    fun codecRoundTripPreservesStructuralIdentity() {
        val pack = pack("codec-pack-v1")

        val decoded = WorldEquationPackCodec.decode(
            WorldEquationPackCodec.encode(pack)
        )

        assertEquals(pack, decoded)
        assertEquals(pack.fingerprint(), decoded.fingerprint())
        assertEquals(pack.structuralFingerprint(), decoded.structuralFingerprint())
    }

    @Test
    fun repositoryKeepsPackVersionsImmutable() = runTest {
        val repository = InMemoryWorldEquationPackRepository()
        val first = pack("repo-pack-v1")
        repository.putIfAbsent(first)
        repository.putIfAbsent(first)

        val different = first.copy(
            equation = first.equation.copy(
                version = first.equation.version + "-other",
            )
        )
        assertFailsWith<IllegalArgumentException> {
            repository.putIfAbsent(different)
        }
        assertEquals(first, repository.load(first.version))
    }

    private fun pack(version: String): WorldEquationPack {
        val equation = CognitiveWorldEquationProfile().spec
        val dimensions = equation.stableCoefficients()
            .flatMap { listOf(it.sourceDimension, it.targetDimension) }
            .toSet()
        val nodes = setOf(WorldNodeKind.EVIDENCE)
        val registry = WorldProjectionRegistrySnapshot.create(
            listOf(
                WorldProjectionDescriptor(
                    providerId = "codec-provider",
                    sourceKind = WorldProjectionSourceKind.FIELD,
                    nodeKinds = nodes,
                    signalDimensions = dimensions,
                )
            )
        )
        return WorldEquationPack(
            version = version,
            equation = equation,
            interactionSchema = WorldInteractionSchema(
                version = "codec-interactions-v1",
                entries = equation.stableCoefficients().map {
                    WorldInteractionSchemaEntry(
                        coefficientId = it.id,
                        sourceNodeKinds = nodes,
                        targetNodeKinds = nodes,
                    )
                },
            ),
            projectionContract = WorldProjectionContractSnapshot.create(
                registry,
                setOf("codec-provider"),
            ),
            requiredDimensions = dimensions,
            requiredNodeKinds = nodes,
        )
    }
}
