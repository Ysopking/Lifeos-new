package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.runtime.world.WorldProjectionDescriptor
import app.lifeos.core.runtime.world.WorldProjectionRegistrySnapshot
import app.lifeos.core.runtime.world.WorldProjectionSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorldEquationPackTest {
    private val baselineEquation = CognitiveWorldEquationProfile().spec
    private val dimensions = baselineEquation.stableCoefficients()
        .flatMap { listOf(it.sourceDimension, it.targetDimension) }
        .toSet()
    private val nodes = setOf(WorldNodeKind.EVIDENCE)
    private val registry = WorldProjectionRegistrySnapshot.create(
        listOf(
            WorldProjectionDescriptor(
                providerId = "pack-test-provider",
                sourceKind = WorldProjectionSourceKind.FIELD,
                nodeKinds = nodes,
                signalDimensions = dimensions,
            )
        )
    )
    private val projection = WorldProjectionContractSnapshot.create(
        registry = registry,
        requiredProviderIds = setOf("pack-test-provider"),
    )
    private val interactions = WorldInteractionSchema(
        version = "pack-test-interactions-v1",
        entries = baselineEquation.stableCoefficients().map {
            WorldInteractionSchemaEntry(
                coefficientId = it.id,
                sourceNodeKinds = nodes,
                targetNodeKinds = nodes,
            )
        },
    )
    private val baselinePack = WorldEquationPack(
        version = "pack-v1",
        equation = baselineEquation,
        interactionSchema = interactions,
        projectionContract = projection,
        requiredDimensions = dimensions,
        requiredNodeKinds = nodes,
    )

    @Test
    fun parameterOnlyCandidateRemainsNonActivating() {
        val first = baselineEquation.stableCoefficients().first()
        val candidateEquation = baselineEquation.copy(
            version = baselineEquation.version + "-parameter-candidate",
            coefficients = baselineEquation.coefficients.map {
                if (it.id == first.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        }
                    )
                } else it
            },
        )
        val candidatePack = baselinePack.copy(
            version = "pack-v2",
            equation = candidateEquation,
        )
        val candidate = WorldEquationPackCandidate.create(
            baselinePack,
            candidatePack,
        )

        assertEquals(WorldEquationPackChangeKind.PARAMETER_ONLY, candidate.changeKind)
        assertFalse(candidate.directActivationAllowed)
        assertFalse(candidate.productiveWorldMutationAllowed)
    }

    @Test
    fun interactionSchemaChangeIsStructural() {
        val first = interactions.stableEntries().first()
        val structuralInteractions = interactions.copy(
            version = "pack-test-interactions-v2",
            entries = interactions.entries.map {
                if (it.coefficientId == first.coefficientId) {
                    it.copy(
                        sourceNodeKinds = setOf(
                            WorldNodeKind.EVIDENCE,
                            WorldNodeKind.HYPOTHESIS,
                        )
                    )
                } else it
            },
        )
        val candidate = WorldEquationPackCandidate.create(
            baselinePack,
            baselinePack.copy(
                version = "pack-v2",
                interactionSchema = structuralInteractions,
                requiredNodeKinds = setOf(
                    WorldNodeKind.EVIDENCE,
                    WorldNodeKind.HYPOTHESIS,
                ),
            ),
        )

        assertEquals(WorldEquationPackChangeKind.STRUCTURAL, candidate.changeKind)
        assertFalse(candidate.directActivationAllowed)
    }

    @Test
    fun missingEquationDimensionFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            baselinePack.copy(
                version = "invalid-pack",
                requiredDimensions = dimensions.drop(1).toSet(),
            )
        }
    }

    @Test
    fun interactionSchemaCannotOmitCoefficient() {
        assertFailsWith<IllegalArgumentException> {
            baselinePack.copy(
                version = "invalid-pack",
                interactionSchema = interactions.copy(
                    version = "invalid-interactions",
                    entries = interactions.entries.dropLast(1),
                ),
            )
        }
    }
}
