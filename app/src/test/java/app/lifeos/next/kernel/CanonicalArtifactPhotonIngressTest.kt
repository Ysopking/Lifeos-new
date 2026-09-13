package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.PhotonIngressMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CanonicalArtifactPhotonIngressTest {
    @Test
    fun `artifact ingress is always derived and preserves queue receipt`() = runTest {
        val photon = Photon(
            content = "artifact",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = Instant.parse("2026-09-13T10:00:00Z"),
            ),
        )
        var observedMode: PhotonIngressMode? = null
        val ingress = CanonicalArtifactPhotonIngress { submitted, mode ->
            assertEquals(photon, submitted)
            observedMode = mode
            PhotonSubmissionResult(
                photon = submitted,
                processingQueued = true,
            )
        }

        val receipt = ingress.ingest(photon)

        assertEquals(PhotonIngressMode.DERIVED, observedMode)
        assertTrue(receipt.accepted)
        assertNull(receipt.durableTaskId)
    }

    @Test
    fun `artifact ingress exposes an unqueued submission as rejected`() = runTest {
        val photon = Photon(
            content = "artifact",
            provenance = Provenance(
                source = "test",
                actor = "test",
                createdAt = Instant.parse("2026-09-13T10:00:00Z"),
            ),
        )
        val ingress = CanonicalArtifactPhotonIngress { submitted, mode ->
            assertEquals(PhotonIngressMode.DERIVED, mode)
            PhotonSubmissionResult(
                photon = submitted,
                processingQueued = false,
                processingFailure = "not queued",
            )
        }

        val receipt = ingress.ingest(photon)

        assertFalse(receipt.accepted)
    }
}
