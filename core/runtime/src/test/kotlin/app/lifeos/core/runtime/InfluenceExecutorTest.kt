package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfluenceExecutorTest {
    @Test
    fun failingFieldDoesNotPreventHealthyField() = runTest {
        val photon = Photon(
            content = "test",
            provenance = Provenance("test", "user"),
        )
        var reached = false
        val executor = InfluenceExecutor()

        val result = executor.execute(
            photon = photon,
            fields = listOf(
                ForceField { error("Broken field") },
                ForceField {
                    reached = true
                    FieldInfluence(
                        module = "healthy",
                        photonId = photon.id,
                        type = "TEST",
                        deltaEnergy = 0.0,
                        confidence = 1.0,
                        explanation = "Healthy field executed",
                    )
                },
            ),
        )

        assertTrue(reached)
        assertEquals(1, result.failures.size)
        assertEquals(RuntimeFailureCategory.FIELD, result.failures.single().category)
        assertEquals(photon.id, result.failures.single().photonId)
        assertEquals(1, result.influences.size)
    }

    @Test
    fun completedFieldsAreSkippedAndOnlyNewSuccessesAreReported() = runTest {
        val photon = Photon(
            content = "resume",
            provenance = Provenance("test", "user"),
        )
        val executions = IntArray(3)
        val successes = mutableListOf<Int>()
        val fields = List(3) { index ->
            ForceField {
                executions[index] += 1
                null
            }
        }

        val result = InfluenceExecutor().execute(
            photon = photon,
            fields = fields,
            completedFieldIndexes = setOf(0, 2),
            onFieldSuccess = successes::add,
        )

        assertEquals(listOf(0, 1, 0), executions.toList())
        assertEquals(listOf(1), successes)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun successCallbackIsNotInvokedForFailedField() = runTest {
        val photon = Photon(
            content = "failure",
            provenance = Provenance("test", "user"),
        )
        val successes = mutableListOf<Int>()

        val result = InfluenceExecutor().execute(
            photon = photon,
            fields = listOf(
                ForceField { error("broken") },
                ForceField { null },
            ),
            onFieldSuccess = successes::add,
        )

        assertEquals(listOf(1), successes)
        assertEquals(1, result.failures.size)
    }
}
