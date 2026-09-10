package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThoughtMatrixCompatibilityTest {
    @Test
    fun `legacy matrix keeps API behavior while v2 captures provenance`() = runTest {
        val at = Instant.parse("2026-09-10T12:00:00Z")
        val matrix = ThoughtMatrix()
        val first = photon("legacy", 1, "first", 2.0, at)
        val second = photon("legacy", 2, "second", 5.0, at)

        assertEquals("INDEX", assertNotNull(matrix.influence(first)).type)
        assertEquals(2.0, matrix.state.value.totalEnergy)
        assertEquals(1L, matrix.state.value.nodes[first.id]?.revision)

        assertEquals("INDEX", assertNotNull(matrix.influence(second)).type)
        assertEquals(5.0, matrix.state.value.totalEnergy)
        assertEquals(2L, matrix.state.value.nodes[first.id]?.revision)

        val v2 = matrix.v2Snapshot(at)
        val node = v2.nodes.single()
        assertEquals(first.id, node.photonId)
        assertEquals(2L, node.sourceRevision)
        assertEquals("legacy-source", node.provenance.source)
        assertEquals("legacy-actor", node.provenance.actor)
        assertTrue(node.provenance.sourceFingerprint.isNotBlank())
    }

    @Test
    fun `legacy matrix removes equal revision ambiguity instead of choosing first value`() = runTest {
        val at = Instant.parse("2026-09-10T12:05:00Z")
        val matrix = ThoughtMatrix()
        val first = photon("ambiguous", 1, "Value A", 3.0, at)
        val conflicting = photon("ambiguous", 1, "Value B", 3.0, at)

        assertEquals("INDEX", assertNotNull(matrix.influence(first)).type)
        val conflictInfluence = assertNotNull(matrix.influence(conflicting))

        assertEquals("INDEX_CONFLICT", conflictInfluence.type)
        assertTrue(matrix.state.value.nodes.isEmpty())
        assertEquals(0.0, matrix.state.value.totalEnergy)
        val v2 = matrix.v2Snapshot(at)
        assertTrue(v2.nodes.isEmpty())
        assertEquals(1, v2.conflicts.size)
    }

    @Test
    fun `unchanged and stale legacy photons remain no-ops`() = runTest {
        val at = Instant.parse("2026-09-10T12:10:00Z")
        val matrix = ThoughtMatrix()
        val current = photon("stable", 2, "current", 1.0, at)
        val stale = photon("stable", 1, "old", 9.0, at)

        assertNotNull(matrix.influence(current))
        assertNull(matrix.influence(current))
        assertNull(matrix.influence(stale))
        assertEquals(1.0, matrix.state.value.totalEnergy)
        assertEquals(2L, matrix.state.value.nodes[current.id]?.revision)
    }

    private fun photon(
        id: String,
        revision: Long,
        content: String,
        energy: Double,
        at: Instant,
    ) = Photon(
        id = PhotonId(id),
        revision = revision,
        content = content,
        energy = energy,
        provenance = Provenance(
            source = "legacy-source",
            actor = "legacy-actor",
            createdAt = at,
        ),
        tags = setOf("legacy"),
    )
}
