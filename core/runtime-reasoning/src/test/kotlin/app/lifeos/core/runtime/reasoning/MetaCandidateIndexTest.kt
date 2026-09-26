package app.lifeos.core.runtime.reasoning

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetaCandidateIndexTest {
    private val at = Instant.parse("2026-09-26T12:00:00Z")

    @Test
    fun retainsOnlyAmbiguousEquivalenceGroupsInDeterministicOrder() {
        val projector = MetaTheoryMemoryProjector()
        fun photon(id: String, content: String) = Photon(
            id = PhotonId(id),
            content = content,
            mimeType = "text/plain",
            provenance = Provenance("test", "owner", at),
            tags = setOf("document"),
        )
        val projections = listOf(
            projector.project(photon("b", "same")),
            projector.project(photon("unique", "different")),
            projector.project(photon("a", "same")),
        )
        val index = MetaCandidateIndex()

        index.replace(projections.reversed())

        val groups = index.ambiguousGroups()
        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().candidates.map { it.photonId.value })
        assertTrue(groups.single().candidates.all { it.sourceRevision == 1L })
    }
}
