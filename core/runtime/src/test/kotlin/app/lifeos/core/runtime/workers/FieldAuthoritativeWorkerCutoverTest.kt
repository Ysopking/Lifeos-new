package app.lifeos.core.runtime.workers

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.FieldRunId
import app.lifeos.core.field.FieldSnapshotId
import app.lifeos.core.model.FieldInfluence
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonLoadReport
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.ForceField
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.field.AuthoritativeFieldProcessor
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import app.lifeos.core.runtime.field.FieldCutoverMode
import app.lifeos.core.runtime.field.FieldCutoverRuntimeRouter
import app.lifeos.core.runtime.field.FieldCutoverState
import app.lifeos.core.runtime.field.FieldCutoverStateRepository
import app.lifeos.core.runtime.field.FieldShadowExecution
import app.lifeos.core.runtime.field.FieldShadowProcessor
import app.lifeos.core.runtime.field.FieldShadowState
import app.lifeos.core.runtime.field.runtimePhotonFingerprint
import app.lifeos.core.runtime.tasks.InMemoryTaskRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class FieldAuthoritativeWorkerCutoverTest {
    private val at = Instant.parse("2026-09-19T02:20:00Z")
    private val workerId = WorkerId("field-cutover-worker")
    private val domain = FieldDomainId("domain:test-authoritative-cutover")

    @Test
    fun shadowStateKeepsLegacyAndObservationalShadowPaths() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claim(tasks, photon)
        var legacyCalls = 0
        var shadowCalls = 0
        var authoritativeCalls = 0
        val worker = CognitiveTaskWorker(
            workerId = workerId,
            tasks = tasks,
            photons = photons,
            fields = StaticFieldRegistry(
                listOf(
                    ForceField { input ->
                        legacyCalls += 1
                        FieldInfluence(
                            module = "legacy",
                            photonId = input.id,
                            type = "LEGACY",
                            deltaEnergy = 0.1,
                            confidence = 1.0,
                            explanation = "legacy",
                        )
                    }
                )
            ),
            executor = InfluenceExecutor(),
            fieldShadowProcessor = FieldShadowProcessor {
                shadowCalls += 1
                completed(DefaultPhotonFieldRequestFactory.DOMAIN_ID, it)
            },
            fieldCutoverRouter = router(
                state = cutoverState(FieldCutoverMode.SHADOW),
                onAuthoritative = {
                    authoritativeCalls += 1
                    completed(domain, it)
                },
            ),
            now = { at },
        )

        val result = worker.execute(claimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals(1, legacyCalls)
        assertEquals(1, shadowCalls)
        assertEquals(0, authoritativeCalls)
        assertEquals(1, result.influences.size)
        assertNotNull(result.fieldShadow)
        assertNull(result.fieldAuthoritative)
    }

    @Test
    fun authoritativeStateSkipsLegacyAndShadowAndUsesOnlySelectedUniversalDomain() = runTest {
        val tasks = InMemoryTaskRepository()
        val photons = FakePhotonRepository()
        val photon = photon()
        photons.save(photon)
        val claimed = claim(tasks, photon)
        var legacyCalls = 0
        var shadowCalls = 0
        var authoritativeCalls = 0
        val worker = CognitiveTaskWorker(
            workerId = workerId,
            tasks = tasks,
            photons = photons,
            fields = StaticFieldRegistry(
                listOf(ForceField {
                    legacyCalls += 1
                    null
                })
            ),
            executor = InfluenceExecutor(),
            fieldShadowProcessor = FieldShadowProcessor {
                shadowCalls += 1
                completed(DefaultPhotonFieldRequestFactory.DOMAIN_ID, it)
            },
            fieldCutoverRouter = router(
                state = cutoverState(FieldCutoverMode.AUTHORITATIVE),
                onAuthoritative = {
                    authoritativeCalls += 1
                    completed(domain, it)
                },
            ),
            now = { at },
        )

        val result = worker.execute(claimed)

        assertEquals(TaskState.COMPLETED, result.finalState)
        assertEquals(0, legacyCalls)
        assertEquals(0, shadowCalls)
        assertEquals(1, authoritativeCalls)
        assertEquals(emptyList(), result.influences)
        assertNull(result.fieldShadow)
        val authoritative = assertNotNull(result.fieldAuthoritative)
        assertEquals(domain, authoritative.cutoverState.domainId)
        assertEquals(FieldShadowState.COMPLETED, authoritative.universal.state)
    }

    @Test
    fun genericShadowDomainCannotBeRegisteredAsAuthoritativeProcessor() {
        val repository = MemoryStateRepository(null)
        assertFailsWith<IllegalArgumentException> {
            FieldCutoverRuntimeRouter(
                states = repository,
                processors = listOf(
                    object : AuthoritativeFieldProcessor {
                        override val domainId = DefaultPhotonFieldRequestFactory.DOMAIN_ID
                        override fun accepts(photon: Photon): Boolean = true
                        override suspend fun process(photon: Photon) =
                            completed(domainId, photon)
                    }
                ),
            )
        }
    }

    private fun router(
        state: FieldCutoverState,
        onAuthoritative: suspend (Photon) -> FieldShadowExecution,
    ) = FieldCutoverRuntimeRouter(
        states = MemoryStateRepository(state),
        processors = listOf(
            object : AuthoritativeFieldProcessor {
                override val domainId: FieldDomainId = domain
                override fun accepts(photon: Photon): Boolean = true
                override suspend fun process(photon: Photon): FieldShadowExecution =
                    onAuthoritative(photon)
            }
        ),
    )

    private fun cutoverState(mode: FieldCutoverMode): FieldCutoverState =
        FieldCutoverState(
            domainId = domain,
            generation = if (mode == FieldCutoverMode.AUTHORITATIVE) 2L else 1L,
            revision = 1L,
            mode = mode,
            evidenceFingerprint = if (mode == FieldCutoverMode.SHADOW) null else "a".repeat(64),
            replayCaseCount = if (mode == FieldCutoverMode.SHADOW) 0 else 32,
            updatedAt = at,
            authoritativeSince = at.takeIf { mode == FieldCutoverMode.AUTHORITATIVE },
            provenance = "test",
        )

    private fun completed(
        domainId: FieldDomainId,
        photon: Photon,
    ) = FieldShadowExecution(
        state = FieldShadowState.COMPLETED,
        domainId = domainId,
        runId = FieldRunId("run-" + domainId.value),
        snapshotId = FieldSnapshotId("snapshot-" + domainId.value),
        convergenceStatus = ConvergenceStatus.CONVERGED,
        sourcePhotonId = photon.id,
        sourceRevision = photon.revision,
        sourceFingerprint = runtimePhotonFingerprint(photon),
    )

    private suspend fun claim(
        tasks: InMemoryTaskRepository,
        photon: Photon,
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            inputPhotonIds = setOf(photon.id),
            inputPhotonRevisions = mapOf(photon.id to photon.revision),
            idempotencyKey = "field-cutover:" + photon.id.value + ":r" + photon.revision,
            createdAt = at,
            updatedAt = at,
        )
        tasks.create(task)
        tasks.transition(task.id, TaskState.CREATED, TaskState.QUEUED, at)
        return checkNotNull(tasks.claim(task.id, workerId, at, at.plusSeconds(30)))
    }

    private fun photon() = Photon(
        id = PhotonId("field-cutover-photon"),
        content = "domain-specific field input",
        provenance = Provenance("test", "test", at),
    )

    private class MemoryStateRepository(
        private var state: FieldCutoverState?,
    ) : FieldCutoverStateRepository {
        override suspend fun load(domainId: FieldDomainId): FieldCutoverState? =
            state?.takeIf { it.domainId == domainId }

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            state: FieldCutoverState,
        ): Boolean {
            val current = this.state
            if (current?.revision != expectedRevision) return false
            this.state = state
            return true
        }
    }

    private class FakePhotonRepository : PhotonRepository {
        private val values = linkedMapOf<PhotonId, Photon>()

        override suspend fun save(photon: Photon) {
            values[photon.id] = photon
        }

        override suspend fun load(id: PhotonId): Photon? = values[id]

        override suspend fun loadReport(): PhotonLoadReport =
            PhotonLoadReport(values.values.toList(), emptyList())

        override suspend fun loadAll(): List<Photon> = values.values.toList()

        override suspend fun delete(id: PhotonId) {
            values.remove(id)
        }
    }
}
