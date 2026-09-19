package app.lifeos.core.runtime.evolution

import app.lifeos.core.field.world.WorldDimensionValue
import app.lifeos.core.field.world.WorldFieldVector
import app.lifeos.core.field.world.WorldNodeKind
import app.lifeos.core.field.world.WorldTargetRef
import app.lifeos.core.runtime.world.CognitiveWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaConfig
import app.lifeos.core.runtime.world.WorldFormulaInputSnapshot
import app.lifeos.core.runtime.world.WorldFormulaInteraction
import app.lifeos.core.runtime.world.WorldFormulaRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldEquationShadowEvaluatorTest {
    @Test
    fun baselineAndCandidateUseSameFrozenCaseAndStayNonProductive() = runTest {
        val baseline = CognitiveWorldEquationProfile().spec
        val coefficient = baseline.stableCoefficients().first()
        val candidate = baseline.copy(
            version = baseline.version + "-candidate",
            coefficients = baseline.coefficients.map {
                if (it.id == coefficient.id) {
                    it.copy(
                        multiplier = if (it.multiplier < 0.9) {
                            it.multiplier + 0.05
                        } else {
                            it.multiplier - 0.05
                        }
                    )
                } else {
                    it
                }
            },
        )
        val source = WorldTargetRef(WorldNodeKind.STRATEGY, "shadow-source")
        val target = WorldTargetRef(WorldNodeKind.GOAL, "shadow-target")
        val request = WorldFormulaRequest(
            inputs = listOf(
                WorldFormulaInputSnapshot(
                    target = source,
                    vector = WorldFieldVector(
                        listOf(
                            WorldDimensionValue(
                                dimension = coefficient.sourceDimension,
                                value = 0.8,
                                confidence = 1.0,
                                provenanceFingerprints = setOf("shadow-source-v1"),
                            )
                        )
                    ),
                    sourceSnapshotFingerprint = "shadow-source-v1",
                ),
                WorldFormulaInputSnapshot(
                    target = target,
                    vector = WorldFieldVector(
                        listOf(
                            WorldDimensionValue(
                                dimension = coefficient.targetDimension,
                                value = 0.2,
                                confidence = 1.0,
                                provenanceFingerprints = setOf("shadow-target-v1"),
                            )
                        )
                    ),
                    sourceSnapshotFingerprint = "shadow-target-v1",
                ),
            ),
            interactions = listOf(
                WorldFormulaInteraction(
                    source = source,
                    target = target,
                    sourceDimension = coefficient.sourceDimension,
                    targetDimension = coefficient.targetDimension,
                    coefficientId = coefficient.id,
                    strength = 1.0,
                    explanation = "paired shadow evaluation",
                )
            ),
            equationVersion = baseline.version,
            observedAt = Instant.parse("2026-09-19T07:15:00Z"),
            config = WorldFormulaConfig(
                maxIterations = 4,
                requiredStableRounds = 1,
                epsilon = 0.0005,
            ),
        )
        val case = WorldEquationShadowCase(
            runId = "shadow-run-1",
            workloadId = "shadow-workload-a",
            request = request,
        )

        val observation = WorldEquationShadowEvaluator().evaluate(
            baseline = baseline,
            candidate = candidate,
            case = case,
        )

        assertEquals(case.fingerprint(), observation.caseFingerprint)
        assertEquals(baseline.fingerprint(), observation.baselineEquationFingerprint)
        assertEquals(candidate.fingerprint(), observation.candidateEquationFingerprint)
        assertTrue(coefficient.id in observation.baseline.activeCoefficientIds)
        assertTrue(coefficient.id in observation.candidate.activeCoefficientIds)
    }
}
