package app.lifeos.core.runtime

import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfluenceExecutorTest {
    @Test fun failingFieldDoesNotPreventHealthyField() = runTest {
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
}
