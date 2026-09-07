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

class MatrixRevisionTest {
    @Test fun duplicateAndStaleRevisionsCannotOverwriteNewKnowledge() = runTest {
        val matrix = ThoughtMatrix()
        val photon = Photon(content = "Original", energy = 2.0, provenance = Provenance("test", "user"))
        matrix.influence(photon)
        matrix.influence(photon.copy(revision = 2, content = "Aktualisiert", energy = 5.0))
        matrix.influence(photon)
        matrix.influence(photon.copy(revision = 2, energy = 99.0))
        assertEquals(1, matrix.state.value.nodes.size)
        assertEquals(5.0, matrix.state.value.totalEnergy)
        assertEquals("Aktualisiert", matrix.state.value.nodes[photon.id]?.summary)
    }
}
