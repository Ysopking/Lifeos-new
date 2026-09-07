package app.lifeos.core.model.task

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskStateMachineTest {
    @Test
    fun validExecutionPathIsAllowed() {
        assertTrue(TaskStateMachine.canTransition(TaskState.CREATED, TaskState.QUEUED))
        assertTrue(TaskStateMachine.canTransition(TaskState.QUEUED, TaskState.CLAIMED))
        assertTrue(TaskStateMachine.canTransition(TaskState.CLAIMED, TaskState.RUNNING))
        assertTrue(TaskStateMachine.canTransition(TaskState.RUNNING, TaskState.COMPLETED))
    }

    @Test
    fun interruptedTaskMustRecoverBeforeRequeue() {
        assertFalse(TaskStateMachine.canTransition(TaskState.INTERRUPTED, TaskState.QUEUED))
        assertTrue(TaskStateMachine.canTransition(TaskState.INTERRUPTED, TaskState.RECOVERING))
        assertTrue(TaskStateMachine.canTransition(TaskState.RECOVERING, TaskState.QUEUED))
    }

    @Test
    fun terminalStatesCannotReenterExecution() {
        assertFalse(TaskStateMachine.canTransition(TaskState.COMPLETED, TaskState.RUNNING))
        assertFalse(TaskStateMachine.canTransition(TaskState.FAILED, TaskState.QUEUED))
        assertFalse(TaskStateMachine.canTransition(TaskState.CANCELLED, TaskState.CLAIMED))
    }

    @Test
    fun illegalTransitionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            TaskStateMachine.requireTransition(TaskState.CREATED, TaskState.COMPLETED)
        }
    }
}
