package app.lifeos.next.kernel

import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.TemporalStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CanonicalInformationObservationIngressTest {
    private val now = Instant.parse("2026-09-25T00:00:00Z")

    @Test
    fun ownerAuthorizedObservationBecomesOriginPhotonAndPreservesGrantProvenance() = runTest {
        var observedMode: PhotonIngressMode? = null
        val ingress = CanonicalInformationObservationIngress { photon, mode ->
            observedMode = mode
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = true,
            )
        }

        val receipt = ingress.ingest(
            observation().authorizedBy("owner-observation-grant:" + "a".repeat(64)),
            salience = 0.7,
        )

        assertEquals(PhotonIngressMode.ORIGIN, observedMode)
        assertTrue(receipt.processingQueued)
        assertEquals("event=appointment", receipt.photon.content)
        assertEquals(0.7, receipt.photon.semanticMass)
        assertTrue("owner-authorized-observation" in receipt.photon.tags)
        assertTrue(
            receipt.photon.tags.any {
                it == "observation-grant:owner-observation-grant:" + "a".repeat(64)
            }
        )
    }

    @Test
    fun ungrantedObservationCannotReachCanonicalPhotonCommit() = runTest {
        val ingress = CanonicalInformationObservationIngress { photon, _ ->
            PhotonSubmissionResult(
                photon = photon,
                processingQueued = true,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            ingress.ingest(observation())
        }
    }

    private fun observation() = InformationObservation(
        sourceId = "calendar-provider",
        sourceResource = "calendar://personal/event/1",
        surface = ObservationSurfaceKind.CONTENT_PROVIDER,
        observedAt = now,
        sourceTimestamp = now,
        sourceRevision = "rev-1",
        mimeType = "application/vnd.lifeos.calendar+text",
        payload = "event=appointment",
        realization = RealizationDescriptor(
            representation = RepresentationLevel.ACTUAL,
            epistemicStatus = EpistemicStatus.OBSERVED,
            temporalStatus = TemporalStatus.CURRENT,
            controlStatus = ControlStatus.PASSIVE,
        ),
        authority = ObservationAuthorityClass.PLATFORM_PROVIDER,
        privacy = ObservationPrivacyClass.PERSONAL,
        confidence = 1.0,
    )
}
