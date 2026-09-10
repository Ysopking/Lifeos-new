package app.lifeos.core.runtime.thought

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ThoughtMatrixMultiDomainTest {
    @Test
    fun `same photon can coexist in distinct domain semantic projections`() = runTest {
        val at = Instant.parse("2026-09-10T11:30:00Z")
        val photon = Photon(
            id = PhotonId("shared-photon"),
            revision = 1,
            content = "shared source",
            provenance = Provenance("source", "actor", at),
        )
        val matrix = ThoughtMatrixV2 { at }
        val finance = ThoughtProjectionInput(
            photon,
            StableFieldIds.domain("finance"),
            "balance",
            verification = ThoughtVerificationStatus.OBSERVED,
        )
        val planning = ThoughtProjectionInput(
            photon,
            StableFieldIds.domain("planning"),
            "available-funds",
            verification = ThoughtVerificationStatus.OBSERVED,
        )

        assertIs<ThoughtProjectionResult.Applied>(matrix.project(finance))
        val second = assertIs<ThoughtProjectionResult.Applied>(matrix.project(planning))

        assertEquals(2, second.snapshot.nodes.size)
        assertTrue(second.snapshot.conflicts.isEmpty())
        assertNotEquals(second.snapshot.nodes[0].id, second.snapshot.nodes[1].id)
        assertEquals(setOf(PhotonId("shared-photon")), second.snapshot.nodes.map { it.photonId }.toSet())
    }

    @Test
    fun `stale revision is rejected only within the same node identity`() = runTest {
        val at = Instant.parse("2026-09-10T11:31:00Z")
        fun photon(revision: Long, content: String) = Photon(
            id = PhotonId("revisioned"),
            revision = revision,
            content = content,
            provenance = Provenance("source", "actor", at),
        )
        val domain = StableFieldIds.domain("domain")
        val matrix = ThoughtMatrixV2 { at }

        matrix.project(ThoughtProjectionInput(photon(2, "new"), domain, "key-a"))
        val stale = assertIs<ThoughtProjectionResult.Stale>(
            matrix.project(ThoughtProjectionInput(photon(1, "old"), domain, "key-a"))
        )
        val separate = assertIs<ThoughtProjectionResult.Applied>(
            matrix.project(ThoughtProjectionInput(photon(1, "old"), domain, "key-b"))
        )

        assertEquals(2L, stale.existingRevision)
        assertEquals(1L, stale.rejectedRevision)
        assertEquals(2, separate.snapshot.nodes.size)
    }
}
