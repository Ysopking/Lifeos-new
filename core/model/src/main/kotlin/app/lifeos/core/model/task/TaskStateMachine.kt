package app.lifeos.core.model.task

object TaskStateMachine {
    fun canTransition(from: TaskState, to: TaskState): Boolean = when (from) {
        TaskState.CREATED -> to == TaskState.QUEUED || to == TaskState.CANCELLED
        TaskState.QUEUED -> to == TaskState.CLAIMED || to == TaskState.CANCELLED
        TaskState.CLAIMED -> to in setOf(
            TaskState.RUNNING,
            TaskState.INTERRUPTED,
            TaskState.CANCELLED,
        )
        TaskState.RUNNING -> to in setOf(
            TaskState.CHECKPOINTED,
            TaskState.RETRY_WAIT,
            TaskState.INTERRUPTED,
            TaskState.COMPLETED,
            TaskState.FAILED,
            TaskState.CANCELLED,
        )
        TaskState.CHECKPOINTED -> to in setOf(
            TaskState.RUNNING,
            TaskState.RETRY_WAIT,
            TaskState.INTERRUPTED,
            TaskState.COMPLETED,
            TaskState.FAILED,
            TaskState.CANCELLED,
        )
        TaskState.RETRY_WAIT -> to == TaskState.QUEUED || to == TaskState.CANCELLED
        TaskState.INTERRUPTED -> to == TaskState.RECOVERING || to == TaskState.CANCELLED
        TaskState.RECOVERING -> to in setOf(
            TaskState.QUEUED,
            TaskState.FAILED,
            TaskState.CANCELLED,
        )
        TaskState.COMPLETED,
        TaskState.FAILED,
        TaskState.CANCELLED,
        -> false
    }

    fun requireTransition(from: TaskState, to: TaskState) {
        require(canTransition(from, to)) { "Illegal task transition: $from -> $to" }
    }
}
