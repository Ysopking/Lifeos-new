package app.lifeos.core.runtime.health

import app.lifeos.core.model.health.ProtectionActor
import app.lifeos.core.model.health.ProtectionMode
import app.lifeos.core.model.health.ProtectionReason
import app.lifeos.core.model.health.ProtectionReasonCode
import app.lifeos.core.model.health.ProtectionStateLoadResult
import app.lifeos.core.model.health.ProtectionStateWriteResult
import app.lifeos.core.model.health.RuntimeProtectionState
import app.lifeos.core.model.health.RuntimeProtectionStateRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ProtectionCoordinatorTest {
    private val now = Instant.parse("2026-09-08T04:05:00Z")
    private val workerNode = HealthNodeId("worker:test")
    private val reason = ProtectionReason(
        code = ProtectionReasonCode.RECOVERY_EXHAUSTED,
        source = "recovery:test",
        message = "worker recovery exhausted",
    )

    @Test
    fun processRestartRehydratesDurableQuarantine() = runTest {
        val repository = InMemoryProtectionRepository()
        val firstRegistry = QuarantineRegistry()
        val first = ProtectionCoordinator(
            repository = repository,
            quarantineRegistry = firstRegistry,
            verifier = ProtectionResumeVerifier { ProtectionVerificationResult.Verified() },
            now = { now },
        )
        first.enterQuarantine(
            nodes = setOf(workerNode),
            reasons = listOf(reason),
            actor = ProtectionActor.RECOVERY,
            provenance = "recovery:test",
        )

        val restartedRegistry = QuarantineRegistry()
        val restarted = ProtectionCoordinator(
            repository = repository,
            quarantineRegistry = restartedRegistry,
            verifier = ProtectionResumeVerifier { ProtectionVerificationResult.Verified() },
            now = { now.plusSeconds(10) },
        )
        val state = restarted.rehydrate()

        assertEquals(ProtectionMode.QUARANTINED, state.mode)
        assertNotNull(restartedRegistry.active(workerNode, now.plusSeconds(10)))
        assertIs<ProtectionAdmissionDecision.Blocked>(
            restarted.admit(workerNode, HealthGatePurpose.NORMAL),
        )
    }

    @Test
    fun safeModeBlocksNormalExecutionButAllowsRecoveryPurpose() = runTest {
        val coordinator = ProtectionCoordinator(
            repository = InMemoryProtectionRepository(),
            quarantineRegistry = QuarantineRegistry(),
            verifier = ProtectionResumeVerifier { ProtectionVerificationResult.Verified() },
            now = { now },
        )
        coordinator.enterSafeMode(
            reasons = listOf(reason),
            actor = ProtectionActor.SYSTEM,
            provenance = "integrity:test",
        )

        assertIs<ProtectionAdmissionDecision.Blocked>(
            coordinator.admit(HealthNodeId("field:any"), HealthGatePurpose.NORMAL),
        )
        assertIs<ProtectionAdmissionDecision.Allowed>(
            coordinator.admit(HealthNodeId("field:any"), HealthGatePurpose.RECOVERY),
        )
    }

    @Test
    fun userResumeRunsVerifierBeforeClearingProtection() = runTest {
        val repository = InMemoryProtectionRepository()
        var verifierCalls = 0
        val coordinator = ProtectionCoordinator(
            repository = repository,
            quarantineRegistry = QuarantineRegistry(),
            verifier = ProtectionResumeVerifier {
                verifierCalls += 1
                ProtectionVerificationResult.Verified("repair probe passed")
            },
            now = { now },
        )
        coordinator.enterSafeMode(
            reasons = listOf(reason),
            affectedNodes = setOf(workerNode),
            actor = ProtectionActor.SYSTEM,
            provenance = "integrity:test",
        )

        val resumed = coordinator.requestResume(
            actor = ProtectionActor.USER,
            provenance = "user:resume",
        )

        assertEquals(1, verifierCalls)
        assertIs<ProtectionResumeResult.Resumed>(resumed)
        assertEquals(ProtectionMode.NORMAL, coordinator.snapshot().mode)
        assertEquals(2L, coordinator.snapshot().generation)
    }

    @Test
    fun unreadableDurableStateCannotBeSilentlyReset() = runTest {
        val repository = InMemoryProtectionRepository(unreadable = true)
        val coordinator = ProtectionCoordinator(
            repository = repository,
            quarantineRegistry = QuarantineRegistry(),
            verifier = ProtectionResumeVerifier { ProtectionVerificationResult.Verified() },
        )

        kotlin.test.assertFailsWith<ProtectionStateCorruptedException> {
            coordinator.rehydrate()
        }
        assertEquals(0, repository.writeAttempts)
    }

    private class InMemoryProtectionRepository(
        private var state: RuntimeProtectionState? = null,
        private var unreadable: Boolean = false,
    ) : RuntimeProtectionStateRepository {
        var writeAttempts: Int = 0
            private set

        override suspend fun load(): ProtectionStateLoadResult = when {
            unreadable -> ProtectionStateLoadResult.Unreadable("corrupt protection state")
            state == null -> ProtectionStateLoadResult.Missing
            else -> ProtectionStateLoadResult.Loaded(checkNotNull(state))
        }

        override suspend fun compareAndSet(
            expectedRevision: Long?,
            next: RuntimeProtectionState,
        ): ProtectionStateWriteResult {
            writeAttempts += 1
            if (unreadable) {
                return ProtectionStateWriteResult.UnreadableExisting("corrupt protection state")
            }
            val actual = state?.revision
            if (actual != expectedRevision) return ProtectionStateWriteResult.Conflict(actual)
            state = next
            return ProtectionStateWriteResult.Saved(next)
        }
    }
}
