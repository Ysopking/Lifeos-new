package app.lifeos.core.model.task

import java.util.UUID

@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "Task ID must not be blank" }
    }

    companion object {
        fun new(): TaskId = TaskId(UUID.randomUUID().toString())
    }
}
