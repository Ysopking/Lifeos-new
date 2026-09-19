package app.lifeos.core.data.photon

import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexEntry
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRevisionRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PhotonIndexDeltaCodecTest {
    @Test
    fun advanceDeltaRoundTripsDeterministically() {
        val previous = PhotonRevisionRef(PhotonId("photon-a"), 3)
        val current = PhotonRevisionRef(PhotonId("photon-a"), 4)
        val delta = PhotonIndexDelta(
            sequence = 17,
            operation = PhotonIndexDeltaOperation.ADVANCE,
            ref = current,
            previousHeadRef = previous,
            newEntry = entry(current),
            snapshotGeneration = 5,
            snapshotFingerprint = "a".repeat(64),
        )

        val encoded = PhotonIndexDeltaCodec.encode(delta)
        val decoded = PhotonIndexDeltaCodec.decode(encoded)

        assertEquals(delta, decoded)
        assertEquals(encoded.toList(), PhotonIndexDeltaCodec.encode(decoded).toList())
    }

    @Test
    fun malformedTrailingBytesFailClosed() {
        val ref = PhotonRevisionRef(PhotonId("photon-b"), 1)
        val encoded = PhotonIndexDeltaCodec.encode(
            PhotonIndexDelta(
                sequence = 1,
                operation = PhotonIndexDeltaOperation.CREATE,
                ref = ref,
                previousHeadRef = null,
                newEntry = entry(ref),
                snapshotGeneration = 0,
                snapshotFingerprint = "b".repeat(64),
            )
        )

        assertFailsWith<IllegalArgumentException> {
            PhotonIndexDeltaCodec.decode(encoded + byteArrayOf(1))
        }
    }

    private fun entry(ref: PhotonRevisionRef) = PhotonIndexEntry(
        ref = ref,
        createdAt = Instant.parse("2026-09-19T00:00:00Z"),
        phase = PhotonPhase.ACTIVE,
        mimeType = "text/plain",
        tags = setOf("journal-test"),
        semanticMass = 1.0,
        confidence = 0.9,
        contentFingerprint = "c".repeat(64),
        latest = true,
    )
}
