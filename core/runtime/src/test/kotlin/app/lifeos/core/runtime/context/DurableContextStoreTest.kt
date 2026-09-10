package app.lifeos.core.runtime.context

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DurableContextStoreTest {
    private val at = Instant.parse("2026-09-10T16:00:00Z")

    @Test
    fun `conversation context rehydrates from same durable photon repository`() = runTest {
        val repository = InMemoryPhotonRepository()
        val image = photon(
            id = "image-1",
            content = "Architecture diagram",
            mimeType = "image/png",
            tags = setOf("result", "image"),
        )
        repository.save(image)

        val firstProcess = ConversationContextStore(repository, ConversationContextId("conversation-a"))
        val write = assertIs<ContextWriteResult.Applied>(firstProcess.record(image, recordedAt = at))
        assertEquals(image.id, write.entry.targetPhotonId)
        assertEquals("image", write.entry.kind)

        val secondProcess = ConversationContextStore(repository, ConversationContextId("conversation-a"))
        val rehydrated = secondProcess.loadAll().single()

        assertEquals(write.entry.recordPhotonId, rehydrated.recordPhotonId)
        assertEquals(image.id, rehydrated.targetPhotonId)
        assertEquals(image.revision, rehydrated.targetRevision)
        assertEquals(ContextRecordCodec.targetFingerprint(image), rehydrated.targetFingerprint)
        assertEquals("Architecture diagram", repository.load(image.id)?.content)
        assertTrue(repository.load(rehydrated.recordPhotonId)?.relations?.any { it.target == image.id } == true)
    }

    @Test
    fun `context write refuses target that is not durable`() = runTest {
        val repository = InMemoryPhotonRepository()
        val store = ProjectContextStore(repository, ProjectContextId("project-a"))
        val transient = photon("module-x", "Transient module", tags = setOf("module"))

        assertFailsWith<IllegalArgumentException> {
            store.record(transient, recordedAt = at)
        }
        assertTrue(repository.snapshot().isEmpty())
    }

    @Test
    fun `context write refuses same id revision with different durable content`() = runTest {
        val repository = InMemoryPhotonRepository()
        val durable = photon("module-x", "Durable A", tags = setOf("module"))
        repository.save(durable)
        val store = ProjectContextStore(repository, ProjectContextId("project-a"))

        assertFailsWith<IllegalArgumentException> {
            store.record(durable.copy(content = "Conflicting B"), recordedAt = at)
        }
        assertEquals(1, repository.snapshot().size)
    }

    @Test
    fun `same context projection is idempotent while metadata change increments record revision`() = runTest {
        val repository = InMemoryPhotonRepository()
        val goal = photon("goal-1", "Finish G01", tags = setOf("goal"))
        repository.save(goal)
        val store = GoalContextStore(repository, GoalContextId("goal-scope"))

        val first = assertIs<ContextWriteResult.Applied>(store.record(goal, recordedAt = at))
        val unchanged = assertIs<ContextWriteResult.Unchanged>(store.record(goal, recordedAt = at.plusSeconds(1)))
        val changed = assertIs<ContextWriteResult.Applied>(
            store.record(goal, active = false, recordedAt = at.plusSeconds(2))
        )

        assertEquals(first.entry.recordRevision, unchanged.entry.recordRevision)
        assertEquals(first.entry.recordRevision + 1, changed.entry.recordRevision)
        assertTrue(!changed.entry.active)
    }

    internal class InMemoryPhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()
        var unreadableFiles: List<String> = emptyList()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadReport(): PhotonLoadReport = PhotonLoadReport(
            photons = values.values.sortedBy { it.provenance.createdAt },
            unreadableFiles = unreadableFiles,
        )

        fun snapshot(): Map<PhotonId, Photon> = values.toMap()
    }

    private fun photon(
        id: String,
        content: String,
        mimeType: String = "text/plain",
        tags: Set<String> = emptySet(),
        createdAt: Instant = at,
        confidence: Double = 0.9,
    ) = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        mimeType = mimeType,
        confidence = confidence,
        provenance = Provenance("test", "user", createdAt),
        tags = tags,
    )
}
