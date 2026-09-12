package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeepSearchSourceSnapshotTest {
    private val at = Instant.parse("2026-09-11T19:00:00Z")

    @Test
    fun `snapshot fingerprint is order independent and vault safe`() {
        val first = photon("a", 1, "LIFEOS photon evidence", 0.9)
        val second = photon("b", 2, "Second evidence", 0.8)
        val left = DeepSearchSourceSnapshot.fingerprint(listOf(first, second))
        val right = DeepSearchSourceSnapshot.fingerprint(listOf(second, first))
        assertEquals(left, right)
        assertTrue(left.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `content revision and confidence changes invalidate snapshot`() {
        val base = photon("a", 1, "Evidence", 0.9)
        assertNotEquals(
            DeepSearchSourceSnapshot.fingerprint(listOf(base)),
            DeepSearchSourceSnapshot.fingerprint(listOf(base.copy(content = "Changed evidence"))),
        )
        assertNotEquals(
            DeepSearchSourceSnapshot.fingerprint(listOf(base)),
            DeepSearchSourceSnapshot.fingerprint(listOf(base.copy(revision = 2))),
        )
        assertNotEquals(
            DeepSearchSourceSnapshot.fingerprint(listOf(base)),
            DeepSearchSourceSnapshot.fingerprint(listOf(base.copy(confidence = 0.7))),
        )
    }

    private fun photon(
        id: String,
        revision: Long,
        content: String,
        confidence: Double,
    ) = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        confidence = confidence,
        provenance = Provenance("test", "user", at),
        tags = setOf("memory"),
    )
}
