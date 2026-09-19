package app.lifeos.next

import app.lifeos.core.runtime.self.LifeOsSelfStateSnapshot
import app.lifeos.core.runtime.self.SelfHealthState
import app.lifeos.core.runtime.self.SelfLiveSourceState
import app.lifeos.core.runtime.self.SelfMemoryState
import app.lifeos.core.runtime.self.SelfObservationBand
import app.lifeos.core.runtime.self.SelfObservationCapture
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.self.SelfObservationTrigger
import app.lifeos.core.runtime.self.SelfPhotonState
import app.lifeos.core.runtime.self.SelfRecoveryState
import app.lifeos.core.runtime.self.SelfResourceState
import app.lifeos.core.runtime.self.SelfRuntimeState
import app.lifeos.core.runtime.self.SelfStateProjectionResult
import app.lifeos.core.runtime.self.SelfToolState
import app.lifeos.core.runtime.self.SelfWorldState
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SelfObservationSchedulingContractTest {
    @Test
    fun healthyRepeatedObservationSettlesToThirtySecondBand() = runTest {
        val result = SelfStateProjectionResult(
            snapshot = LifeOsSelfStateSnapshot(
                capturedAt = Instant.parse("2026-09-19T12:00:00Z"),
                photon = SelfPhotonState(1, 1, 0, "index"),
                memory = SelfMemoryState(1, 1, 0, "memory"),
                world = SelfWorldState(1, "world", 1, "eq-v1", "eq", "boot", "boot-fp", "cog"),
                runtime = SelfRuntimeState(
                    "topology",
                    setOf("runtime"),
                    setOf("runtime"),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                resource = SelfResourceState("hardware", 1.0, 1.0, 1.0, 1.0, 1.0),
                health = SelfHealthState(1, 0, 0, 0, 0, 0),
                recovery = SelfRecoveryState(emptySet(), "recovery"),
                tools = SelfToolState(0, 0, 0, 0, 0),
                liveSources = SelfLiveSourceState(0, 0, 0, 0, "sources"),
            ),
            issues = emptyList(),
        )
        val coordinator = SelfObservationCoordinator(SelfObservationCapture { result })
        coordinator.refresh(SelfObservationTrigger.STARTUP)
        val stable = coordinator.refresh(SelfObservationTrigger.TIMER)

        assertEquals(SelfObservationBand.STABLE, stable.band)
        assertEquals(30_000L, coordinator.nextInterval(stable.band).toMillis())
    }
}
