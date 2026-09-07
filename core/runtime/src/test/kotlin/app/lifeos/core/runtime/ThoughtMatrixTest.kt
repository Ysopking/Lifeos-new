package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ThoughtMatrixTest {
    @Test fun indexesPhotonAndAccumulatesEnergy() = runTest {
        val matrix = ThoughtMatrix()
        matrix.influence(Photon(content = "Gedanke", energy = 2.5, confidence = .9, provenance = Provenance("chat", "user")))
        assertEquals(1, matrix.state.value.nodes.size)
        assertEquals(2.5, matrix.state.value.totalEnergy)
    }
}
