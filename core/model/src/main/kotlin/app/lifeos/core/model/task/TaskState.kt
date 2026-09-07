package app.lifeos.core.model.task

enum class TaskState {
    CREATED,
    QUEUED,
    CLAIMED,
    RUNNING,
    CHECKPOINTED,
    RETRY_WAIT,
    INTERRUPTED,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
