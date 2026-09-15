package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.PhotonId
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class DeepSearchSourceRevisionTest {
    private val at = Instant.parse("2026-09-15T13:00:00Z")

    @Test
    fun `source Photon revision participates in DeepSearch evidence identity`() = runTest {
        val first = search(sourceRevision = 1L)
        val second = search(sourceRevision = 2L)

        val firstEvidence = first.evidence.single()
        val secondEvidence = second.evidence.single()
        assertEquals(1L, firstEvidence.sourcePhotonRevision)
        assertEquals(2L, secondEvidence.sourcePhotonRevision)
        assertNotEquals(firstEvidence.id, secondEvidence.id)
    }

    @Test
    fun `checkpoint v2 round trip retains exact source Photon revision`() = runTest {
        var checkpoint: DeepSearchPlannerCheckpoint? = null
        DeepSearchPlannerV2(now = { at }).search(
            request = request(),
            sources = listOf(RevisionSource(9L)),
            checkpointSink = DeepSearchCheckpointSink { checkpoint = it },
        )
        val persisted = assertNotNull(checkpoint)
        val encoded = DeepSearchPlannerCheckpointCodec.encode(persisted)
        val decoded = DeepSearchPlannerCheckpointCodec.decode(encoded)

        assertEquals(persisted, decoded)
        assertEquals(9L, decoded.evidence.single().sourcePhotonRevision)
    }

    private suspend fun search(sourceRevision: Long): DeepSearchResult = DeepSearchPlannerV2(
        now = { at },
    ).search(
        request = request(),
        sources = listOf(RevisionSource(sourceRevision)),
    )

    private fun request() = DeepSearchRequest(
        query = "revision evidence",
        budget = DeepSearchBudget(
            maxDepth = 2,
            maxBreadth = 4,
            maxWorkUnits = 2,
            maxElapsed = Duration.ofSeconds(4),
        ),
        minimumResolutionScore = 0.0,
        minimumWinnerMargin = 0.0,
    )

    private class RevisionSource(
        private val sourceRevision: Long,
    ) : DeepSearchSource {
        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = "revision-source",
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
            workUnitsPerExpansion = 1,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            if (branch.depth > 0) return emptyList()
            return listOf(
                DeepSearchFindingDraft(
                    statement = "Revision-bound fact",
                    semanticTerms = setOf("revision", "evidence"),
                    confidence = 0.95,
                    evidence = listOf(
                        DeepSearchEvidenceDraft(
                            statement = "Revision-bound evidence",
                            confidence = 0.95,
                            sourcePhotonId = PhotonId("revision-photon"),
                            sourcePhotonRevision = sourceRevision,
                        ),
                    ),
                ),
            )
        }
    }
}
