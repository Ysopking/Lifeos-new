package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KernelBootstrapStateM213Test {
    @Test
    fun retainedWorkingSetReportsDurableAndColdCountsWithoutHoldingColdPayloads() {
        val retained = listOf(
            Photon(
                id = PhotonId("hot"),
                content = "hot",
                provenance = Provenance("test", "test", Instant.EPOCH),
            )
        )
        val state = KernelBootstrapState(
            status = KernelBootstrapStatus.READY,
            photons = retained,
            durablePhotonCount = 10_000,
            coldPhotonCount = 9_999,
        )

        assertEquals(1, state.retainedPhotonCount)
        assertEquals(10_000, state.durablePhotonCount)
        assertEquals(9_999, state.coldPhotonCount)
        assertTrue(state.workingSetTruncated)
    }
}
