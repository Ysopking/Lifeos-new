package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldNodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldEquationPackStructuralEvaluatorTest {
    @Test
    fun compatibleStructuralChangeIsOnlyShadowEligible() {
        val fixture = fixture()
        val candidatePack = fixture.baseline.copy(
            version = "pack-v2",
            interactionSchema = fixture.baseline.interactionSchema.copy(
                version = "interaction-v2",
                entries = fixture.baseline.interactionSchema.entries.mapIndexed { index, entry ->
                    if (index == 0) {
                        entry.copy(
                            sourceNodeKinds = setOf(
                                WorldNodeKind.EVIDENCE,
                                WorldNodeKind.HYPOTHESIS,
                            )
                        )
                    } else entry
                },
            ),
            requiredNodeKinds = setOf(
                WorldNodeKind.EVIDENCE,
                WorldNodeKind.HYPOTHESIS,
            ),
        )
        val candidate = WorldEquationPackCandidate.create(
            fixture.baseline,
            candidatePack,
        )

        val evidence = WorldEquationPackStructuralEvaluator().evaluate(
            fixture.baseline,
            candidate,
            fixture.registry,
        )

        assertEquals(WorldEquationPackChangeKind.STRUCTURAL, candidate.changeKind)
        assertEquals(WorldEquationPackStructuralStatus.SHADOW_ELIGIBLE, evidence.status)
        assertTrue(evidence.issues.isEmpty())
        assertFalse(evidence.productiveActivationAllowed)
        assertFalse(evidence.productiveWorldMutationAllowed)
    }

    @Test
    fun frozenRegistryMismatchBlocksStructuralCandidate() {
        val fixture = fixture()
        val candidateRegistry = WorldProjectionRegistrySnapshot.create(
            listOf(
                WorldProjectionDescriptor(
                    providerId = "other-provider",
                    sourceKind = WorldProjectionSourceKind.FIELD,
                    nodeKinds = setOf(WorldNodeKind.EVIDENCE),
                    signalDimensions = fixture.dimensions,
                )
            )
        )
        val candidatePack = fixture.baseline.copy(
            version = "pack-v2",
            projectionContract = WorldProjectionContractSnapshot.create(
                candidateRegistry,
                setOf("other-provider"),
            ),
        )
        val evidence = WorldEquationPackStructuralEvaluator().evaluate(
            fixture.baseline,
            WorldEquationPackCandidate.create(fixture.baseline, candidatePack),
            fixture.registry,
        )

        assertEquals(WorldEquationPackStructuralStatus.BLOCKED, evidence.status)
        assertTrue(
            WorldEquationPackStructuralIssue.REGISTRY_SNAPSHOT_MISMATCH in evidence.issues
        )
    }

    @Test
    fun parameterOnlyCandidateCannotMasqueradeAsStructuralEvidence() {
        val fixture = fixture()
        val first = fixture.baseline.equation.stableCoefficients().first()
        val candidateEquation = fixture.baseline.equation.copy(
            version = fixture.baseline.equation.version + "-candidate",
            coefficients = fixture.baseline.equation.coefficients.map {
                if (it.id == first.id) it.copy(multiplier = it.multiplier * 0.9) else it
            },
        )
        val candidatePack = fixture.baseline.copy(
            version = "pack-v2",
            equation = candidateEquation,
        )
        val evidence = WorldEquationPackStructuralEvaluator().evaluate(
            fixture.baseline,
            WorldEquationPackCandidate.create(fixture.baseline, candidatePack),
            fixture.registry,
        )

        assertEquals(WorldEquationPackStructuralStatus.BLOCKED, evidence.status)
        assertTrue(
            WorldEquationPackStructuralIssue.PARAMETER_ONLY_NOT_STRUCTURAL in evidence.issues
        )
    }

    private fun fixture(): Fixture {
        val equation = CognitiveWorldEquationProfile().spec
        val dimensions = equation.stableCoefficients()
            .flatMap { listOf(it.sourceDimension, it.targetDimension) }
            .toSet()
        val nodes = setOf(
            WorldNodeKind.EVIDENCE,
            WorldNodeKind.HYPOTHESIS,
        )
        val registry = WorldProjectionRegistrySnapshot.create(
            listOf(
                WorldProjectionDescriptor(
                    providerId = "structural-provider",
                    sourceKind = WorldProjectionSourceKind.FIELD,
                    nodeKinds = nodes,
                    signalDimensions = dimensions,
                )
            )
        )
        val baseline = WorldEquationPack(
            version = "pack-v1",
            equation = equation,
            interactionSchema = WorldInteractionSchema(
                version = "interaction-v1",
                entries = equation.stableCoefficients().map {
                    WorldInteractionSchemaEntry(
                        coefficientId = it.id,
                        sourceNodeKinds = setOf(WorldNodeKind.EVIDENCE),
                        targetNodeKinds = setOf(WorldNodeKind.EVIDENCE),
                    )
                },
            ),
            projectionContract = WorldProjectionContractSnapshot.create(
                registry,
                setOf("structural-provider"),
            ),
            requiredDimensions = dimensions,
            requiredNodeKinds = setOf(WorldNodeKind.EVIDENCE),
        )
        return Fixture(baseline, registry, dimensions)
    }

    private data class Fixture(
        val baseline: WorldEquationPack,
        val registry: WorldProjectionRegistrySnapshot,
        val dimensions: Set<app.lifeos.core.field.world.WorldSignalDimension>,
    )
}
