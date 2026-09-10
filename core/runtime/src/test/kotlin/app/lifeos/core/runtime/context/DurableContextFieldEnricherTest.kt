package app.lifeos.core.runtime.context

import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.FieldConvergenceEngine
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DurableContextFieldEnricherTest {
    private val at = Instant.parse("2026-09-10T18:00:00Z")

    @Test
    fun `active durable goal context becomes request reference and context field`() = runTest {
        val repository = Repository()
        val goal = photon("goal-context", "LIFEOS goal", tags = setOf("goal"))
        repository.save(goal)
        GoalContextStore(repository, GoalContextId("goal-lifeos"))
            .record(goal, recordedAt = at)
        val source = photon("source", "Continue", tags = setOf("goal"))
        val base = DefaultPhotonFieldRequestFactory().create(source)

        val enriched = DurableContextFieldEnricher(repository).enrich(base)

        val reference = enriched.context.photonReferences.single()
        assertEquals(goal.id, reference.photonId)
        assertEquals(setOf(FieldContextScope.CURRENT_GOAL), reference.scopes)
        assertTrue("goal" in reference.semanticTerms)
        assertTrue("tag:goal" in reference.semanticTerms)
        assertTrue(FieldContextScope.CURRENT_GOAL in enriched.context.activeScopes)
        assertEquals("true", enriched.context.domain.attributes["context.g02.complete"])
        assertEquals("1", enriched.context.domain.attributes["context.g02.referenceCount"])
        assertTrue(
            enriched.domainFields.any {
                it.descriptor.name == ContextDomainField.NAME &&
                    it.descriptor.domainId == base.domainId
            }
        )
        assertNotEquals(base.context.fingerprint(), enriched.context.fingerprint())
    }

    @Test
    fun `stale context target is excluded and integrity remains observable`() = runTest {
        val repository = Repository()
        val goalV1 = photon("goal-context", "Version one", tags = setOf("goal"))
        repository.save(goalV1)
        GoalContextStore(repository, GoalContextId("goal-lifeos"))
            .record(goalV1, recordedAt = at)
        repository.save(
            goalV1.copy(
                revision = 2,
                content = "Version two",
                provenance = goalV1.provenance.copy(createdAt = at.plusSeconds(60)),
            )
        )
        val base = DefaultPhotonFieldRequestFactory().create(
            photon("source", "Continue", tags = setOf("goal"))
        )

        val enriched = DurableContextFieldEnricher(repository).enrich(base)

        assertTrue(enriched.context.photonReferences.isEmpty())
        assertEquals("false", enriched.context.domain.attributes["context.g02.complete"])
        assertEquals("0", enriched.context.domain.attributes["context.g02.referenceCount"])
        assertTrue(
            enriched.context.domain.attributes["context.g02.integrityFingerprint"] != "complete"
        )
    }

    @Test
    fun `context changes deterministic field run identity without mutating source request`() = runTest {
        val repository = Repository()
        val goal = photon("goal-context", "Goal", tags = setOf("goal"))
        repository.save(goal)
        GoalContextStore(repository, GoalContextId("goal-lifeos"))
            .record(goal, recordedAt = at)
        val source = photon("source", "Continue", tags = setOf("goal"))
        val base = DefaultPhotonFieldRequestFactory().create(source)
        val baseFingerprint = base.context.fingerprint()
        val enriched = DurableContextFieldEnricher(repository).enrich(base)

        val plainResult = FieldConvergenceEngine().converge(base)
        val contextualResult = FieldConvergenceEngine().converge(enriched)

        assertNotEquals(plainResult.state.runId, contextualResult.state.runId)
        assertEquals(baseFingerprint, base.context.fingerprint())
        assertTrue(base.domainFields.isEmpty())
    }

    @Test
    fun `unreadable vault entries change integrity fingerprint but readable context remains usable`() = runTest {
        val repository = Repository()
        val goal = photon("goal-context", "Goal", tags = setOf("goal"))
        repository.save(goal)
        GoalContextStore(repository, GoalContextId("goal-lifeos"))
            .record(goal, recordedAt = at)
        repository.unreadableFiles = listOf("broken.photon")
        val base = DefaultPhotonFieldRequestFactory().create(
            photon("source", "Continue", tags = setOf("goal"))
        )

        val enriched = DurableContextFieldEnricher(repository).enrich(base)

        assertEquals(goal.id, enriched.context.photonReferences.single().photonId)
        assertEquals("false", enriched.context.domain.attributes["context.g02.complete"])
        assertTrue(
            enriched.context.domain.attributes["context.g02.integrityFingerprint"] != "complete"
        )
    }

    private class Repository : PhotonRepository {
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

        override suspend fun loadAll(): List<Photon> {
            val report = loadReport()
            check(report.unreadableFiles.isEmpty())
            return report.photons
        }

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }

    private fun photon(
        id: String,
        content: String,
        tags: Set<String>,
    ) = Photon(
        id = PhotonId(id),
        revision = 1,
        content = content,
        confidence = 0.9,
        provenance = Provenance("test", "user", at),
        tags = tags,
    )
}
