package app.lifeos.core.runtime.life

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypedPerceptionRuntimeTest {
    private val observedAt = Instant.parse("2026-09-12T14:00:00Z")

    @Test
    fun genericSignalRetainsBlockBV1Fingerprint() {
        val signal = PerceptionSignal(
            source = PerceptionSource.CHAT,
            sourceId = "user:local",
            observedAt = observedAt,
            payload = "hello",
            confidence = 0.9,
            salience = 0.7,
            tags = setOf("chat"),
        )
        val expected = StableCognitiveIds.fingerprint(
            "perception-signal/v1",
            PerceptionSource.CHAT.name,
            "user:local",
            observedAt.toString(),
            "text/plain",
            "hello",
            java.lang.Double.toHexString(0.9),
            java.lang.Double.toHexString(0.7),
            "chat",
        )

        assertEquals(expected, signal.fingerprint)
    }

    @Test
    fun candidateInputOrderDoesNotChangeTypedSignalOrBatchIdentity() {
        val firstCandidates = listOf(
            PerceptionCandidate("0:hallo", 0.88, "greeting"),
            PerceptionCandidate("0:halo", 0.31, "unknown"),
        )
        val first = PerceptionSignal(
            source = PerceptionSource.SENSOR,
            sourceId = "mic:local",
            observedAt = observedAt,
            observedUntil = observedAt.plusMillis(420),
            payload = "hallo",
            confidence = 0.88,
            modality = PerceptionModality.SPEECH,
            candidates = firstCandidates,
        )
        val reordered = first.copy(candidates = firstCandidates.reversed())
        val engine = PerceptionFusionEngine()

        assertEquals(first.fingerprint, reordered.fingerprint)
        assertEquals(first.candidateDistributionFingerprint, reordered.candidateDistributionFingerprint)
        assertEquals(engine.fuse(listOf(first)), engine.fuse(listOf(reordered, first)))
    }

    @Test
    fun materiallyDifferentCandidateDistributionDoesNotDeduplicate() {
        val base = PerceptionSignal(
            source = PerceptionSource.SENSOR,
            sourceId = "camera:local",
            observedAt = observedAt,
            payload = "frame-17",
            mimeType = "application/vnd.lifeos.visual-observation+text",
            confidence = 0.8,
            modality = PerceptionModality.VISUAL,
            candidates = listOf(PerceptionCandidate("document", 0.8)),
            sourceAssetPhotonId = PhotonId("asset-frame-17"),
        )
        val changed = base.copy(candidates = listOf(PerceptionCandidate("document", 0.79)))
        val batch = PerceptionFusionEngine().fuse(listOf(base, changed))

        assertNotEquals(base.fingerprint, changed.fingerprint)
        assertEquals(2, batch.photons.size)
    }

    @Test
    fun speechObservationCanonicalizesCandidatesAndPreservesTiming() {
        val observation = SpeechObservation(
            sourceId = "mic:local",
            observedAt = observedAt,
            observedUntil = observedAt.plusSeconds(1),
            words = listOf(
                SpeechWordObservation(
                    index = 0,
                    candidates = listOf(
                        PerceptionCandidate("halo", 0.25),
                        PerceptionCandidate("hallo", 0.91, "greeting"),
                    ),
                ),
                SpeechWordObservation(
                    index = 1,
                    candidates = listOf(PerceptionCandidate("welt", 0.84)),
                ),
            ),
        )
        val signal = observation.toSignal()

        assertEquals("hallo welt", observation.transcript)
        assertEquals(PerceptionModality.SPEECH, signal.modality)
        assertEquals(observedAt.plusSeconds(1), signal.observedUntil)
        assertEquals(listOf("0:hallo", "1:welt", "0:halo"), signal.canonicalCandidates.map { it.value })
    }

    @Test
    fun fusedTypedPhotonKeepsRawAssetLineageDistinct() {
        val assetId = PhotonId("image-asset-photon")
        val observation = VisualObservation(
            sourceId = "camera:local",
            observedAt = observedAt,
            observedUntil = observedAt.plusMillis(33),
            candidates = listOf(
                PerceptionCandidate("handwriting", 0.73),
                PerceptionCandidate("document", 0.61),
            ),
            sourceAssetPhotonId = assetId,
            frameKey = "frame-001",
        )
        val photon = PerceptionFusionEngine().fuse(listOf(observation.toSignal())).photons.single()

        assertTrue(photon.id != assetId)
        assertTrue(assetId in photon.provenance.parentIds)
        assertTrue(photon.relations.any { it.target == assetId && it.type == RelationType.REFERENCES })
        assertTrue("source-asset:${assetId.value}" in photon.tags)
        assertTrue(photon.tags.any { it.startsWith("candidate-distribution:") })
    }
}
