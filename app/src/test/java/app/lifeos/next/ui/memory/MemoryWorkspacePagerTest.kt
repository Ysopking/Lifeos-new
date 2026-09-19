package app.lifeos.next.ui.memory

import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.life.MemoryAtomKind
import app.lifeos.core.runtime.life.MemoryStage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryWorkspacePagerTest {
    private val at = Instant.parse("2026-09-19T19:00:00Z")

    @Test
    fun pagingBoundsEveryWorkspaceLaneAndExpandsDeterministically() {
        val full = model(
            nowCount = 100,
            atomsPerGroup = 60,
            crystalCount = 70,
            episodeCount = 100,
        )
        val initialState = MemoryWorkspacePageState()
        val initial = MemoryWorkspacePager.page(full, initialState)

        assertEquals(40, initial.now.size)
        assertEquals(24, initial.topicGroups.single().atoms.size)
        assertEquals(24, initial.crystals.size)
        assertEquals(40, initial.episodes.size)
        assertTrue(initial.paging.hasMoreNow)
        assertTrue(initial.paging.hasMoreTopics)
        assertTrue(initial.paging.hasMoreTimeline)

        val expanded = MemoryWorkspacePager.page(
            full,
            initialState.expandNow().expandTopics().expandTimeline(),
        )
        assertEquals(80, expanded.now.size)
        assertEquals(48, expanded.topicGroups.single().atoms.size)
        assertEquals(48, expanded.crystals.size)
        assertEquals(80, expanded.episodes.size)
    }

    @Test
    fun pagingDoesNotChangeAuthoritativeCountsOrQuery() {
        val full = model(3, 2, 1, 4).copy(
            authoritativePhotonCount = 999,
            query = "project",
        )
        val page = MemoryWorkspacePager.page(full, MemoryWorkspacePageState())

        assertEquals(999, page.authoritativePhotonCount)
        assertEquals("project", page.query)
        assertFalse(page.paging.hasMoreNow)
        assertFalse(page.paging.hasMoreTopics)
        assertFalse(page.paging.hasMoreTimeline)
    }

    private fun model(
        nowCount: Int,
        atomsPerGroup: Int,
        crystalCount: Int,
        episodeCount: Int,
    ): MemoryWorkspaceUiModel = MemoryWorkspaceUiModel(
        projectionAvailable = true,
        projectionEvaluatedAt = at,
        authoritativePhotonCount = nowCount,
        now = List(nowCount) { index ->
            MemorySourceUi(
                photonId = PhotonId("source-" + index),
                content = "source " + index,
                mimeType = "text/plain",
                stage = MemoryStage.HOT,
                isNew = false,
                confidence = 1.0,
                createdAt = at.plusSeconds(index.toLong()),
                source = "test",
                actor = "test",
                tags = emptySet(),
                parentCount = 0,
                relationCount = 0,
            )
        },
        topicGroups = listOf(
            MemoryTopicGroupUi(
                kind = MemoryAtomKind.FACT,
                label = "Fakten",
                atoms = List(atomsPerGroup) { index ->
                    MemoryAtomUi(
                        atomId = "atom-" + index,
                        kind = MemoryAtomKind.FACT,
                        content = "atom " + index,
                        stage = MemoryStage.WARM,
                        confidence = 1.0,
                        observedAt = at,
                        sourcePhotonIds = emptySet(),
                        resolvedSourceCount = 0,
                        missingSourceCount = 0,
                    )
                },
            )
        ),
        crystals = List(crystalCount) { index ->
            MemoryCrystalUi(
                crystalId = "crystal-" + index,
                semanticCore = "crystal " + index,
                confidence = 1.0,
                startedAt = at,
                endedAt = at,
                sourcePhotonIds = emptySet(),
                resolvedSourceCount = 0,
                missingSourceCount = 0,
            )
        },
        episodes = List(episodeCount) { index ->
            MemoryEpisodeUi(
                episodeId = "episode-" + index,
                stage = MemoryStage.COLD,
                startedAt = at,
                endedAt = at,
                semanticKeys = emptySet(),
                sourcePhotonIds = emptySet(),
                resolvedSourceCount = 0,
                missingSourceCount = 0,
            )
        },
        query = "",
    )
}
