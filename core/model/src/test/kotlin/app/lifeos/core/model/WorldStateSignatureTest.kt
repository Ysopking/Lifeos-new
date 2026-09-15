package app.lifeos.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldStateSignatureTest {
    @Test
    fun `photon projection is deterministic and confidence bounded`() {
        val photon = Photon(
            id = PhotonId("world-state-source"),
            revision = 3,
            content = "state",
            phase = PhotonPhase.CONVERGED,
            semanticMass = 4.0,
            energy = 2.0,
            confidence = 0.8,
            provenance = Provenance("test", "owner", Instant.parse("2026-09-15T12:00:00Z")),
        )

        val first = photon.toWorldStateSignature(polarity = -0.25, temporalDepth = 2.0)
        val second = photon.toWorldStateSignature(polarity = -0.25, temporalDepth = 2.0)

        assertEquals(first, second)
        assertEquals(4.0, first.semanticMass)
        assertEquals(2.0, first.energy)
        assertEquals(0.75, first.phase)
        assertEquals(0.2, first.entropy, absoluteTolerance = 1e-12)
        assertEquals(0.8, first.coherence)
        assertEquals(0.8, first.coupling)
    }

    @Test
    fun `invalid world state is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            WorldStateSignature(
                semanticMass = 1.0,
                energy = 1.0,
                phase = 0.5,
                polarity = 2.0,
                entropy = 0.5,
                coherence = 0.5,
                coupling = 0.5,
                temporalDepth = 0.0,
                potential = 0.0,
            )
        }
    }
}
