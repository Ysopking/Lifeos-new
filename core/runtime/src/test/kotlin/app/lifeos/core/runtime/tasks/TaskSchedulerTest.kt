package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask
import app.lifeos.core.model.task.TaskPriority
import app.lifeos.core.model.task.TaskState
import app.lifeos.core.model.task.TaskType
import app.lifeos.core.model.worker.WorkerId
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskSchedulerTest {
    private val t0 = Instant.parse("2026-09-07T13:00:00Z")
    private val schedulerWorker = WorkerId("scheduler-worker")

    @Test
    fun higherPriorityTasksAreClaimedAndDispatchedFirst() = runTest {
        val repository = InMemoryTaskRepository()
        val normal = queuedTask(repository, "normal", TaskPriority.NORMAL)
        val critical = queuedTask(repository, "critical", TaskPriority.CRITICAL)
        val dispatched = mutableListOf<LifeTask>()
        val scheduler = scheduler(repository) { dispatched += it }

        val result = scheduler.scheduleOnce()

        assertEquals(listOf(critical.id, normal.id), dispatched.map { it.id })
        assertEquals(2, result.claimed)
        assertEquals(2, result.dispatched)
        assertTrue(result.dispatchFailures.isEmpty())
    }

    @Test
    fun retryWaitTaskIsNormalizedBeforeClaim() = runTest {
        val repository = InMemoryTaskRepository()
        val task = queuedTask(repository, "retry")
        val oldWorker = WorkerId("old-worker")
        val claimed = checkNotNull(repository.claim(task.id, oldWorker, t0, t0.plusSeconds(30)))
        checkNotNull(repository.startExecution(claimed.id, oldWorker, t0))
        repository.transition(task.id, TaskState.RUNNING, TaskState.RETRY_WAIT, t0)

        val dispatched = mutableListOf<LifeTask>()
        val scheduler = scheduler(repository) { dispatched += it }
        scheduler.scheduleOnce()

        assertEquals(1, dispatched.size)
        assertEquals(TaskState.CLAIMED, dispatched.single().state)
        assertEquals(schedulerWorker, dispatched.single().claimedBy)
    }

    @Test
    fun alreadyClaimedTaskIsNotDispatchedTwice() = runTest {
        val repository = InMemoryTaskRepository()
        queuedTask(repository, "once")
        val dispatched = mutableListOf<LifeTask>()
        val scheduler = scheduler(repository) { dispatched += it }

        val first = scheduler.scheduleOnce()
        val second = scheduler.scheduleOnce()

        assertEquals(1, first.dispatched)
        assertEquals(0, second.scanned)
        assertEquals(1, dispatched.size)
    }

    @Test
    fun dispatcherFailureDoesNotPreventLaterClaims() = runTest {
        val repository = InMemoryTaskRepository()
        val failing = queuedTask(repository, "failing", TaskPriority.CRITICAL)
        val healthy = queuedTask(repository, "healthy", TaskPriority.NORMAL)
        val dispatched = mutableListOf<LifeTask>()
        val scheduler = scheduler(repository) { task ->
            if (task.id == failing.id) error("dispatch failure")
            dispatched += task
        }

        val result = scheduler.scheduleOnce()

        assertEquals(2, result.claimed)
        assertEquals(1, result.dispatched)
        assertEquals(listOf(failing.id), result.dispatchFailures)
        assertEquals(listOf(healthy.id), dispatched.map { it.id })
    }

    private fun scheduler(
        repository: InMemoryTaskRepository,
        dispatch: suspend (LifeTask) -> Unit,
    ) = TaskScheduler(
        tasks = repository,
        workerId = schedulerWorker,
        dispatcher = ClaimedTaskDispatcher(dispatch),
        leaseDuration = Duration.ofSeconds(30),
        now = { t0 },
    )

    private suspend fun queuedTask(
        repository: InMemoryTaskRepository,
        key: String,
        priority: TaskPriority = TaskPriority.NORMAL,
    ): LifeTask {
        val task = LifeTask(
            type = TaskType.PROCESS_PHOTON,
            priority = priority,
            idempotencyKey = key,
            createdAt = t0,
            updatedAt = t0,
        )
        repository.create(task)
        return checkNotNull(repository.transition(task.id, TaskState.CREATED, TaskState.QUEUED, t0))
    }
}
