package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.health.CircuitBreaker
import app.lifeos.core.runtime.health.HealthGate
import app.lifeos.core.runtime.health.QuarantineRegistry
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DurableHotSwapRecoveryTest {
    @Test
    fun `restart rolls back every staged nonterminal activation`() = runBlocking {
        val repository = InMemoryHotSwapRepository(stagedSnapshot())
        val runtime = FakeRuntime()
        val coordinator = coordinator(repository, runtime)

        val results = coordinator.recoverPending()

        val rolled = assertIs<DurableHotSwapResult.RolledBack>(results.single())
        assertTrue(rolled.recoveredAfterRestart)
        assertEquals(HotSwapActivationState.ROLLED_BACK, rolled.snapshot.state)
        assertEquals(listOf("stage-1"), runtime.rolledBack)
        assertTrue(HotSwapActivationLedger(repository).pending().isEmpty())
    }

    @Test
    fun `failed restart rollback remains nonterminal for mandatory retry`() = runBlocking {
        val original = stagedSnapshot()
        val repository = InMemoryHotSwapRepository(original)
        val runtime = FakeRuntime(failRollback = true)
        val coordinator = coordinator(repository, runtime)

        val result = assertIs<DurableHotSwapResult.RecoveryRequired>(coordinator.recoverPending().single())

        assertTrue(result.reason.startsWith("rollback-failed:"))
        assertEquals(HotSwapActivationState.STAGED, repository.current?.state)
        assertEquals(original.revision, repository.current?.revision)
        assertEquals(1, HotSwapActivationLedger(repository).pending().size)
    }

    private fun coordinator(
        repository: InMemoryHotSwapRepository,
        runtime: FakeRuntime,
    ): DurableHotSwapCoordinator = DurableHotSwapCoordinator(
        ledger = HotSwapActivationLedger(repository, now = { NOW }),
        runtime = runtime,
        healthGate = HealthGate(CircuitBreaker(), QuarantineRegistry()),
        healthVerifier = HotSwapHealthVerifier {
            HotSwapHealthEvidence("unused-health", healthy = true, detail = "unused")
        },
        ownerPolicy = OwnerPolicyEffectGate(OwnerPolicyLedger(EmptyOwnerPolicyRepository(), now = { NOW })),
        ownerRequestFactory = HotSwapOwnerEffectRequestFactory {
            OwnerEffectRequest(
                actorId = OwnerActorId("hot-swap-test"),
                effect = OwnerEffectType.PROVIDER_ACTIVATION,
                resource = "hot-swap:test",
                scope = "buildstudio-hot-swap",
            )
        },
        now = { NOW },
    )

    private fun stagedSnapshot() = HotSwapActivationSnapshot(
        id = HotSwapActivationId(HotSwapActivationId.PREFIX + "a".repeat(64)),
        verifiedCandidateId = "verified-candidate-1",
        candidateArtifactId = "candidate-artifact-1",
        candidateId = "candidate-1",
        state = HotSwapActivationState.STAGED,
        stageId = "stage-1",
        previousActiveCandidateId = "previous-candidate",
        revision = 2L,
        lastRecordedAt = NOW,
    )

    private class FakeRuntime(
        private val failRollback: Boolean = false,
    ) : HotSwapRuntimeAdapter {
        val rolledBack = mutableListOf<String>()

        override suspend fun stage(candidate: VerifiedRuntimeCandidate): HotSwapStage =
            error("stage must not run during restart recovery")

        override suspend fun activate(stage: HotSwapStage) =
            error("activate must not run during restart recovery")

        override suspend fun rollback(stage: HotSwapStage) {
            if (failRollback) error("forced rollback failure")
            rolledBack += stage.id
        }

        override suspend fun recoverStage(snapshot: HotSwapActivationSnapshot): HotSwapStage = HotSwapStage(
            id = requireNotNull(snapshot.stageId),
            verifiedCandidateId = snapshot.verifiedCandidateId,
            candidateId = snapshot.candidateId,
            previousActiveCandidateId = snapshot.previousActiveCandidateId,
        )
    }

    private class InMemoryHotSwapRepository(
        initial: HotSwapActivationSnapshot? = null,
    ) : HotSwapActivationRepository {
        var current: HotSwapActivationSnapshot? = initial

        override suspend fun loadReport(): HotSwapActivationRepositoryLoadReport =
            HotSwapActivationRepositoryLoadReport(listOfNotNull(current))

        override suspend fun compareAndSet(
            id: HotSwapActivationId,
            expectedRevision: Long,
            next: HotSwapActivationSnapshot,
        ): Boolean {
            val existing = current
            val currentRevision = if (existing?.id == id) existing.revision else 0L
            if (currentRevision != expectedRevision) return false
            current = next
            return true
        }
    }

    private class EmptyOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            if (events.size.toLong() != expectedRevision) return false
            events += event
            return true
        }
    }

    companion object {
        private val NOW = Instant.parse("2026-09-15T08:50:00Z")
    }
}
