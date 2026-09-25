package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectionClosureEvaluatorTest {
    private val evaluator = ProjectionClosureEvaluator()

    @Test
    fun `same coarse projection with divergent projected successors proves non closure`() {
        val result = evaluator.evaluate(
            listOf(
                sample("r1", "coarse:x", "r2", "coarse:a", "e1"),
                sample("r3", "coarse:x", "r4", "coarse:b", "e2"),
            )
        )

        assertEquals(ProjectionClosureStatus.NOT_CLOSED, result.status)
        assertFalse(result.autonomousProjectionEstablished)
        assertEquals(1, result.conflicts.size)
        assertEquals(
            listOf("coarse:a", "coarse:b"),
            result.conflicts.single().successorProjectionFingerprints,
        )
    }

    @Test
    fun `same coarse projection with same projected successor is closed for observed sample`() {
        val result = evaluator.evaluate(
            listOf(
                sample("r1", "coarse:x", "r2", "coarse:a", "e1"),
                sample("r3", "coarse:x", "r4", "coarse:a", "e2"),
            )
        )

        assertEquals(ProjectionClosureStatus.CLOSED, result.status)
        assertTrue(result.autonomousProjectionEstablished)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun `single source realization cannot establish closure`() {
        val result = evaluator.evaluate(
            listOf(sample("r1", "coarse:x", "r2", "coarse:a", "e1"))
        )

        assertEquals(ProjectionClosureStatus.UNRESOLVED, result.status)
        assertFalse(result.autonomousProjectionEstablished)
    }

    @Test
    fun `duplicate evidence sample cannot fabricate comparability`() {
        val sample = sample("r1", "coarse:x", "r2", "coarse:a", "e1")

        val result = evaluator.evaluate(listOf(sample, sample))

        assertEquals(ProjectionClosureStatus.UNRESOLVED, result.status)
        assertEquals(listOf("e1"), result.evidenceFingerprints)
    }

    @Test
    fun `mixed frozen profiles are rejected`() {
        val first = sample("r1", "coarse:x", "r2", "coarse:a", "e1")
        val second = first.copy(
            realizationProfileFingerprint = "profile:b",
            sourceRevisionId = "r3",
            successorRevisionId = "r4",
            evidenceFingerprint = "e2",
        )

        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(listOf(first, second))
        }
    }

    @Test
    fun `mixed projections are rejected`() {
        val first = sample("r1", "coarse:x", "r2", "coarse:a", "e1")
        val second = first.copy(
            projectionId = "projection:other",
            sourceRevisionId = "r3",
            successorRevisionId = "r4",
            evidenceFingerprint = "e2",
        )

        assertFailsWith<IllegalArgumentException> {
            evaluator.evaluate(listOf(first, second))
        }
    }

    @Test
    fun `self transition sample is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            sample("r1", "coarse:x", "r1", "coarse:a", "e1")
        }
    }

    @Test
    fun `result is deterministic across sample ordering`() {
        val firstSample = sample("r1", "coarse:x", "r2", "coarse:a", "e1")
        val secondSample = sample("r3", "coarse:x", "r4", "coarse:b", "e2")

        val first = evaluator.evaluate(listOf(firstSample, secondSample))
        val second = evaluator.evaluate(listOf(secondSample, firstSample))

        assertEquals(first, second)
    }

    @Test
    fun `closure result grants no truth authority`() {
        val result = evaluator.evaluate(
            listOf(
                sample("r1", "coarse:x", "r2", "coarse:a", "e1"),
                sample("r3", "coarse:x", "r4", "coarse:a", "e2"),
            )
        )

        assertFalse(result.truthAuthority)
    }

    private fun sample(
        sourceRevisionId: String,
        sourceProjection: String,
        successorRevisionId: String,
        successorProjection: String,
        evidence: String,
    ) = ProjectionTransitionSample(
        realizationProfileFingerprint = "profile:a",
        projectionId = "projection:sein",
        sourceRevisionId = sourceRevisionId,
        sourceProjectionFingerprint = sourceProjection,
        successorRevisionId = successorRevisionId,
        successorProjectionFingerprint = successorProjection,
        evidenceFingerprint = evidence,
    )
}
