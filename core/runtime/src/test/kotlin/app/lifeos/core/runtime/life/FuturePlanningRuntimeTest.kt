package app.lifeos.core.runtime.life

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.model.PhotonRevisionWriteResult
import app.lifeos.core.model.RevisionedPhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.CausalDerivedPhotonPersistence
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FuturePlanningRuntimeTest {
    @Test
    fun admissibleEvidenceProducesReplayableNonExecutingCandidate() = runTest {
        val repository = MemoryPhotonRepository()
        val evidence = futureEvidence(
            photonId = "future-evidence-a",
            scenarioId = "scenario-a",
            type = FutureScenarioType.PREEMPTIVE_ACTION,
            allowed = true,
        )
        repository.save(evidence)
        val coordinator = FuturePlanningCoordinator(repository, fixedAuthority())

        val first = coordinator.planPersisted(evidence)

        assertEquals(2, first.size)
        val decision = first.single { "future-planning-decision" in it.tags }
        val candidate = first.single { "future-planning-output" in it.tags }
        assertTrue("non-executing" in decision.tags)
        assertTrue("non-executing" in candidate.tags)
        assertTrue("requires-goal-routing" in candidate.tags)
        assertTrue("requires-owner-policy-at-execution" in candidate.tags)
        first.forEach { repository.save(it) }

        assertTrue(coordinator.planPersisted(evidence).isEmpty())
        val persistedOutputIds = first.map { it.id }.toSet()
        assertEquals(first, repository.loadAll().filter { it.id in persistedOutputIds })
    }

    @Test
    fun blockedAuthorityProducesDecisionOnlyAndNoGoalCandidate() = runTest {
        val repository = MemoryPhotonRepository()
        val evidence = futureEvidence(
            photonId = "future-evidence-blocked",
            scenarioId = "scenario-blocked",
            type = FutureScenarioType.OPPORTUNITY_ACTION,
            allowed = true,
        )
        repository.save(evidence)
        val authority = FuturePlanningAuthority {
            FuturePlanningAdmissibility(
                allowed = false,
                policyRevision = 4,
                policyFingerprint = "policy-4",
                resourceFingerprint = "resource-blocked",
                reason = "hardware-state-requires-suspension",
            )
        }

        val outputs = FuturePlanningCoordinator(repository, authority).planPersisted(evidence)

        assertEquals(1, outputs.size)
        val decision = outputs.single()
        assertTrue("future-plan:no-action" in decision.tags)
        assertTrue(decision.content.contains("blocked:hardware-state-requires-suspension"))
        assertTrue(repository.loadAll().none { "future-planning-output" in it.tags })
    }

    @Test
    fun restartReevaluationChangesDecisionIdentityWhenPolicyRevisionChanges() = runTest {
        val repository = MemoryPhotonRepository()
        val evidence = futureEvidence(
            photonId = "future-evidence-restart",
            scenarioId = "scenario-restart",
            type = FutureScenarioType.KNOWLEDGE_ACQUISITION,
            allowed = true,
        )
        repository.save(evidence)
        var revision = 8L
        val authority = FuturePlanningAuthority {
            FuturePlanningAdmissibility(
                allowed = true,
                policyRevision = revision,
                policyFingerprint = "policy-$revision",
                resourceFingerprint = "resource-stable",
                reason = "planning-resource-admissible",
            )
        }
        val coordinator = FuturePlanningCoordinator(repository, authority)

        val first = coordinator.reconsiderAll()
        revision = 9L
        val second = coordinator.reconsiderAll()

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        assertNotEquals(first.first().id, second.first().id)
        assertNotEquals(first.last().id, second.last().id)
        assertTrue(first.first().content.contains("policy_revision=8"))
        assertTrue(second.first().content.contains("policy_revision=9"))
    }

    @Test
    fun ownerPolicyMutationInsideOneDecisionFailsClosed() = runTest {
        val repository = MemoryPhotonRepository()
        val source = PhotonId("shared-source")
        val observation = futureEvidence(
            photonId = "future-evidence-observation",
            scenarioId = "scenario-observation",
            type = FutureScenarioType.INACTION_PRESSURE,
            allowed = false,
            sourceId = source,
        )
        val action = futureEvidence(
            photonId = "future-evidence-action",
            scenarioId = "scenario-action",
            type = FutureScenarioType.PREEMPTIVE_ACTION,
            allowed = true,
            sourceId = source,
        )
        repository.save(observation)
        repository.save(action)
        var revision = 0L
        val authority = FuturePlanningAuthority { scenario ->
            revision += 1L
            FuturePlanningAdmissibility(
                allowed = scenario.allowed,
                policyRevision = revision,
                policyFingerprint = "policy-$revision",
                resourceFingerprint = "resource-${scenario.id}",
                reason = if (scenario.allowed) "planning-resource-admissible" else "observation-only",
            )
        }

        assertFailsWith<IllegalArgumentException> {
            FuturePlanningCoordinator(repository, authority).planPersisted(action)
        }
    }

    @Test
    fun persistenceStoresEvidenceBeforeDecisionAndCandidate() = runTest {
        val repository = MemoryPhotonRepository()
        val evidence = futureEvidence(
            photonId = "future-evidence-persistence",
            scenarioId = "scenario-persistence",
            type = FutureScenarioType.PREEMPTIVE_ACTION,
            allowed = true,
        )
        val order = mutableListOf<PhotonId>()
        val delegate = CausalDerivedPhotonPersistence { photon, _ ->
            order += photon.id
            repository.save(photon)
        }
        val persistence = FuturePlanningPersistence(
            delegate = delegate,
            planning = FuturePlanningCoordinator(repository, fixedAuthority()),
        )

        persistence.persist(evidence, CausalTraceId("trace:future-planning"))

        assertEquals(3, order.size)
        assertEquals(evidence.id, order.first())
        assertTrue(order[1].value.startsWith("future-plan-decision-"))
        assertTrue(order[2].value.startsWith("future-goal-candidate-"))
        assertTrue("non-executing" in requireNotNull(repository.load(order[2])).tags)
    }

    private fun fixedAuthority(): FuturePlanningAuthority = FuturePlanningAuthority { scenario ->
        FuturePlanningAdmissibility(
            allowed = scenario.allowed,
            policyRevision = 3,
            policyFingerprint = "policy-stable",
            resourceFingerprint = "resource-${scenario.id}",
            reason = if (scenario.allowed) "planning-resource-admissible" else "observation-only",
        )
    }

    private fun futureEvidence(
        photonId: String,
        scenarioId: String,
        type: FutureScenarioType,
        allowed: Boolean,
        sourceId: PhotonId = PhotonId("source-$scenarioId"),
    ): Photon {
        val delta = when (type) {
            FutureScenarioType.INACTION_PRESSURE -> "FRICTION:0.16,AGENCY:-0.08"
            FutureScenarioType.PREEMPTIVE_ACTION -> "FRICTION:-0.12,AGENCY:0.10,CONTINUITY:0.04"
            FutureScenarioType.OPPORTUNITY_ACTION -> "AGENCY:0.14,CONTINUITY:0.08"
            FutureScenarioType.KNOWLEDGE_ACQUISITION -> "PRESENT_COHERENCE:0.10,AGENCY:0.05"
        }
        val horizon = when (type) {
            FutureScenarioType.INACTION_PRESSURE,
            FutureScenarioType.PREEMPTIVE_ACTION -> FutureHorizon.WEEK
            FutureScenarioType.OPPORTUNITY_ACTION -> FutureHorizon.MONTH
            FutureScenarioType.KNOWLEDGE_ACQUISITION -> FutureHorizon.DAY
        }
        return Photon(
            id = PhotonId(photonId),
            content = buildString {
                appendLine("scenario=$scenarioId")
                appendLine("type=${type.name}")
                appendLine("horizon=${horizon.name}")
                appendLine("probability=0.8")
                appendLine("resourceCost=0.2")
                appendLine("allowed=$allowed")
                appendLine("projectionVersion=${FutureEvidenceEngine.DEFAULT_PROJECTION_VERSION}")
                appendLine("delta=$delta")
                append("explanation=test future hypothesis")
            },
            mimeType = FutureEvidencePhotonCodec.MIME_TYPE,
            provenance = Provenance(
                source = "future-evidence",
                actor = "lifeos",
                createdAt = Instant.parse("2026-09-12T12:00:00Z"),
                parentIds = setOf(sourceId),
            ),
            tags = setOf(
                "future-evidence",
                "future-projection:${FutureEvidenceEngine.DEFAULT_PROJECTION_VERSION}",
                if (allowed) "future-actionable" else "future-observation",
            ),
        )
    }

    private class MemoryPhotonRepository : RevisionedPhotonRepository {
        private val data = linkedMapOf<PhotonRevisionRef, Photon>()

        override suspend fun save(photon: Photon) {
            val ref = PhotonRevisionRef(photon.id, photon.revision)
            data[ref]?.let { existing -> require(existing == photon) }
            data[ref] = photon
        }

        override suspend fun load(id: PhotonId): Photon? =
            data.values.filter { it.id == id }.maxByOrNull { it.revision }

        override suspend fun load(ref: PhotonRevisionRef): Photon? = data[ref]

        override suspend fun latestRef(id: PhotonId): PhotonRevisionRef? =
            data.keys.filter { it.photonId == id }.maxByOrNull { it.revision }

        override suspend fun saveRevision(
            photon: Photon,
            expectedPreviousRevision: Long?,
        ): PhotonRevisionWriteResult {
            val previous = load(photon.id)
            if (previous == photon) {
                return PhotonRevisionWriteResult.Idempotent(photon, previous)
            }
            if (previous?.revision != expectedPreviousRevision) {
                return PhotonRevisionWriteResult.Conflict(
                    photon = photon,
                    previous = previous,
                    reason = "test-revision-conflict",
                )
            }
            save(photon)
            return if (previous == null) {
                PhotonRevisionWriteResult.Created(photon)
            } else {
                PhotonRevisionWriteResult.Advanced(photon, previous)
            }
        }

        override suspend fun query(query: PhotonIndexQuery): List<PhotonRevisionRef> {
            val latestRefs = data.keys
                .groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            val latest = data.values
                .filter { photon ->
                    (!query.latestOnly ||
                        latestRefs[photon.id]?.revision == photon.revision) &&
                        (query.ids.isEmpty() || photon.id in query.ids) &&
                        (query.phases.isEmpty() || photon.phase in query.phases) &&
                        (query.mimeTypes.isEmpty() || photon.mimeType in query.mimeTypes) &&
                        photon.tags.containsAll(query.allTags)
                }
            val ordered = when (query.order) {
                PhotonIndexOrder.IDENTITY ->
                    latest.sortedWith(compareBy<Photon>({ it.id.value }, { it.revision }))
                PhotonIndexOrder.NEWEST_FIRST ->
                    latest.sortedByDescending { it.provenance.createdAt }
                PhotonIndexOrder.OLDEST_FIRST ->
                    latest.sortedBy { it.provenance.createdAt }
                PhotonIndexOrder.HIGHEST_SEMANTIC_MASS ->
                    latest.sortedByDescending { it.semanticMass }
                PhotonIndexOrder.HIGHEST_CONFIDENCE ->
                    latest.sortedByDescending { it.confidence }
            }.map { PhotonRevisionRef(it.id, it.revision) }

            val after = query.after?.lastRef
            val remaining = if (after == null) {
                ordered
            } else {
                val index = ordered.indexOf(after)
                if (index < 0) emptyList() else ordered.drop(index + 1)
            }
            return remaining.take(query.limit)
        }

        override suspend fun indexReport(): PhotonIndexReport {
            val latestRefs = data.keys
                .groupBy { it.photonId }
                .mapValues { (_, refs) -> refs.maxBy { it.revision } }
            return PhotonIndexReport(
                formatVersion = 1,
                entryCount = data.size,
                livePhotonCount = latestRefs.size,
                tombstonedPhotonCount = 0,
                latestRefs = latestRefs,
            )
        }

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(data.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = data.values.toList()

        override suspend fun delete(id: PhotonId) {
            data.keys.filter { it.photonId == id }.toList().forEach(data::remove)
        }
    }
}
