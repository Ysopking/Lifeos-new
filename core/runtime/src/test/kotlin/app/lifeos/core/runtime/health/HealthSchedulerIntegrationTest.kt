package app.lifeos.core.runtime.health

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskRepository
import app.lifeos.core.model.worker.WorkerId
import app.lifeos.core.runtime.tasks.*
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.*
import kotlin.test.*

class HealthSchedulerIntegrationTest {
    @Test fun schedulerSurvivesStorageFailureAndProbesAfterCooldown() = runTest {
        var scans = 0
        var broken = true
        val storage = object : TaskRepository by InMemoryTaskRepository() {
            override suspend fun listRunnable(now: Instant, limit: Int): List<LifeTask> {
                scans++
                if (broken) throw IOException("storage offline")
                return emptyList()
            }
        }
        val health = RecoveryCoordinator(breakerFactory = {
            CircuitBreaker(1, 10_000_000_000L) { testScheduler.currentTime * 1_000_000 }
        })
        val loop = TaskSchedulerLoop(
            scope = backgroundScope,
            scheduler = TaskScheduler(storage, WorkerId("test"), ClaimedTaskDispatcher {}),
            wakeSource = ConflatedTaskSchedulerSignal(),
            rescanInterval = Duration.ofSeconds(1),
            health = health,
        )
        loop.start()
        runCurrent()
        assertEquals(1, scans)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, scans)
        broken = false
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, scans)
        assertEquals(HealthState.HEALTHY, health.graph.states.value["TaskScheduler"])
        health.safeMode.enter("test")
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, scans)
        loop.stop()
    }
}
