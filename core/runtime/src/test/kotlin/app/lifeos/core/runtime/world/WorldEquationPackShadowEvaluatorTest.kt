package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldEquationPackShadowEvaluatorTest {
    @Test
    fun structuralCandidateRunsOnlyInIsolatedShadowAndExercisesChangedTopology() = runTest {
        val fixture = fixture()
        val observation = WorldEquationPackShadowEvaluator().evaluate(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            case = shadowCase(
                fixture = fixture,
                runId = "run-1",
                workloadId = "workload-a",
                value = 0.65,
                partition = WorldEquationPackEvidencePartition.SHADOW,
            ),
        )

        assertTrue(observation.structuralDifferenceExercised)
        assertNotEquals(
            observation.baseline.materializationFingerprint,
            observation.candidate.materializationFingerprint,
        )
        assertNotEquals(
            observation.baseline.graphFingerprint,
            observation.candidate.graphFingerprint,
        )
        assertNotEquals(WorldFormulaStatus.INVALID_EQUATION, observation.baseline.status)
        assertNotEquals(WorldFormulaStatus.INVALID_EQUATION, observation.candidate.status)
        assertFalse(observation.productiveActivationAllowed)
        assertFalse(observation.productiveWorldMutationAllowed)
    }

    @Test
    fun parameterOnlyCandidateCannotEnterStructuralShadow() = runTest {
        val fixture = fixture()
        val first = fixture.baseline.equation.stableCoefficients().first()
        val parameterCandidate = fixture.baseline.copy(
            version = "pack-parameter-v2",
            equation = fixture.baseline.equation.copy(
                version = fixture.baseline.equation.version + "-parameter",
                coefficients = fixture.baseline.equation.coefficients.map {
                    if (it.id == first.id) it.copy(multiplier = it.multiplier * 0.9) else it
                },
            ),
        )
        val candidate = WorldEquationPackCandidate.create(
            fixture.baseline,
            parameterCandidate,
        )
        assertEquals(WorldEquationPackChangeKind.PARAMETER_ONLY, candidate.changeKind)

        assertFailsWith<IllegalArgumentException> {
            WorldEquationPackShadowEvaluator().evaluate(
                fixture.baseline,
                candidate,
                shadowCase(
                    fixture,
                    "run-parameter",
                    "workload-parameter",
                    0.55,
                    WorldEquationPackEvidencePartition.SHADOW,
                ),
            )
        }
    }

    @Test
    fun structuralEvidenceLifecycleStopsAtShadowSupported() = runTest {
        val fixture = fixture()
        val preflight = WorldEquationPackStructuralEvaluator().evaluate(
            fixture.baseline,
            fixture.candidate,
            fixture.registry,
        )
        assertEquals(WorldEquationPackStructuralStatus.SHADOW_ELIGIBLE, preflight.status)

        val repository = InMemoryWorldEquationPackEvidenceRepository()
        val coordinator = WorldEquationPackEvidenceCoordinator(repository)
        val protocol = WorldEquationPackEvaluationProtocol(
            version = "structural-shadow-v1",
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 2,
            minimumStructuralExerciseRuns = 2,
            minimumShadowRuns = 1,
            minimumHoldoutRuns = 1,
        )
        var record = coordinator.beginShadow(
            baseline = fixture.baseline,
            candidate = fixture.candidate,
            preflight = preflight,
            protocol = protocol,
        )
        assertEquals(WorldEquationPackLifecycleState.SHADOW, record.state)

        val evaluator = WorldEquationPackShadowEvaluator()
        val first = evaluator.evaluate(
            fixture.baseline,
            fixture.candidate,
            shadowCase(
                fixture,
                "run-shadow",
                "workload-a",
                0.60,
                WorldEquationPackEvidencePartition.SHADOW,
            ),
        )
        record = coordinator.recordObservation(
            fixture.baseline,
            fixture.candidate,
            first,
        )
        assertEquals(WorldEquationPackLifecycleState.SHADOW, record.state)

        val second = evaluator.evaluate(
            fixture.baseline,
            fixture.candidate,
            shadowCase(
                fixture,
                "run-holdout",
                "workload-b",
                0.80,
                WorldEquationPackEvidencePartition.HOLDOUT,
            ),
        )
        record = coordinator.recordObservation(
            fixture.baseline,
            fixture.candidate,
            second,
        )

        assertEquals(WorldEquationPackLifecycleState.SHADOW_SUPPORTED, record.state)
        assertNotNull(record.latestAssessmentId)
        assertFalse(record.productiveActivationAllowed)
        assertFalse(record.promotionAuthorityAllowed)

        val assessment = WorldEquationPackEvidenceEvaluator().evaluate(record.evidence)
        assertEquals(WorldEquationPackShadowDecision.SHADOW_SUPPORTED, assessment.decision)
        assertTrue(assessment.gateResults.all { it.status == WorldEquationPackGateStatus.PASS })
        assertFalse(assessment.productiveActivationAllowed)
        assertFalse(assessment.promotionAuthorityAllowed)
    }

    @Test
    fun blockedPreflightCannotEnterStructuralShadow() = runTest {
        val fixture = fixture()
        val incompatibleRegistry = WorldProjectionRegistrySnapshot.create(
            listOf(
                WorldProjectionDescriptor(
                    providerId = "incompatible-provider",
                    sourceKind = WorldProjectionSourceKind.FIELD,
                    nodeKinds = setOf(WorldNodeKind.EVIDENCE),
                    signalDimensions = fixture.dimensions,
                )
            )
        )
        val blocked = WorldEquationPackStructuralEvaluator().evaluate(
            fixture.baseline,
            fixture.candidate,
            incompatibleRegistry,
        )
        assertEquals(WorldEquationPackStructuralStatus.BLOCKED, blocked.status)

        val coordinator = WorldEquationPackEvidenceCoordinator(
            InMemoryWorldEquationPackEvidenceRepository()
        )
        val protocol = WorldEquationPackEvaluationProtocol(
            version = "blocked-v1",
            minimumIndependentRuns = 2,
            minimumDistinctWorkloads = 1,
            minimumStructuralExerciseRuns = 1,
            minimumShadowRuns = 1,
            minimumHoldoutRuns = 1,
        )
        val registered = coordinator.register(
            fixture.baseline,
            fixture.candidate,
            blocked,
            protocol,
        )
        assertEquals(WorldEquationPackLifecycleState.PREFLIGHT_BLOCKED, registered.state)

        assertFailsWith<IllegalArgumentException> {
            coordinator.beginShadow(
                fixture.baseline,
                fixture.candidate,
                blocked,
                protocol,
            )
        }
    }

    private fun shadowCase(
        fixture: Fixture,
        runId: String,
        workloadId: String,
        value: Double,
        partition: WorldEquationPackEvidencePartition,
    ): WorldEquationPackShadowCase {
        val coefficient = fixture.baseline.equation.stableCoefficients().first()
        val source = WorldFormulaInputSnapshot(
            target = WorldTargetRef(WorldNodeKind.EVIDENCE, "evidence-" + runId),
            vector = WorldFieldVector(
                listOf(
                    WorldDimensionValue(
                        dimension = coefficient.sourceDimension,
                        value = value,
                        confidence = 0.9,
                        provenanceFingerprints = setOf("source-" + runId),
                    )
                )
            ),
            sourceSnapshotFingerprint = "source-snapshot-" + runId,
        )
        val target = WorldFormulaInputSnapshot(
            target = WorldTargetRef(WorldNodeKind.HYPOTHESIS, "hypothesis-" + runId),
            vector = WorldFieldVector(
                listOf(
                    WorldDimensionValue(
                        dimension = coefficient.targetDimension,
                        value = 0.20,
                        confidence = 0.8,
                        provenanceFingerprints = setOf("target-" + runId),
                    )
                )
            ),
            sourceSnapshotFingerprint = "target-snapshot-" + runId,
        )
        val request = WorldFormulaRequest(
            inputs = listOf(source, target),
            interactions = listOf(
                WorldFormulaInteraction(
                    source = source.target,
                    target = target.target,
                    sourceDimension = coefficient.sourceDimension,
                    targetDimension = coefficient.targetDimension,
                    coefficientId = coefficient.id,
                    strength = 0.75,
                    explanation = "structural shadow topology exercise",
                )
            ),
            equationVersion = fixture.baseline.equation.version,
            observedAt = Instant.parse("2026-09-19T08:00:00Z"),
        )
        return WorldEquationPackShadowCase(
            runId = runId,
            workloadId = workloadId,
            request = request,
            providerIdByInputFingerprint = request.inputs.associate {
                it.fingerprint() to "structural-provider"
            },
            partition = partition,
        )
    }

    private fun fixture(): Fixture {
        val equation = CognitiveWorldEquationProfile().spec
        val dimensions = equation.stableCoefficients()
            .flatMap { listOf(it.sourceDimension, it.targetDimension) }
            .toSet()
        val registry = WorldProjectionRegistrySnapshot.create(
            listOf(
                WorldProjectionDescriptor(
                    providerId = "structural-provider",
                    sourceKind = WorldProjectionSourceKind.FIELD,
                    nodeKinds = setOf(
                        WorldNodeKind.EVIDENCE,
                        WorldNodeKind.HYPOTHESIS,
                    ),
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
        val firstId = baseline.interactionSchema.stableEntries().first().coefficientId
        val candidatePack = baseline.copy(
            version = "pack-v2",
            interactionSchema = baseline.interactionSchema.copy(
                version = "interaction-v2",
                entries = baseline.interactionSchema.entries.map { entry ->
                    if (entry.coefficientId == firstId) {
                        entry.copy(
                            sourceNodeKinds = setOf(WorldNodeKind.EVIDENCE),
                            targetNodeKinds = setOf(WorldNodeKind.HYPOTHESIS),
                        )
                    } else {
                        entry
                    }
                },
            ),
            requiredNodeKinds = setOf(
                WorldNodeKind.EVIDENCE,
                WorldNodeKind.HYPOTHESIS,
            ),
        )
        return Fixture(
            baseline = baseline,
            candidate = WorldEquationPackCandidate.create(baseline, candidatePack),
            registry = registry,
            dimensions = dimensions,
        )
    }

    private data class Fixture(
        val baseline: WorldEquationPack,
        val candidate: WorldEquationPackCandidate,
        val registry: WorldProjectionRegistrySnapshot,
        val dimensions: Set<app.lifeos.core.field.world.WorldSignalDimension>,
    )
}
