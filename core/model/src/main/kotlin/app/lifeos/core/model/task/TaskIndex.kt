package app.lifeos.core.model.task

data class TaskIndexReport(
    val formatVersion: Int,
    val taskCount: Int,
    val activeTaskCount: Int,
    val idempotencyKeyCount: Int,
    val unreadableEntries: List<String> = emptyList(),
) {
    init {
        require(formatVersion > 0) { "Task index format version must be positive" }
        require(taskCount >= 0) { "Task index task count must not be negative" }
        require(activeTaskCount >= 0) { "Task index active count must not be negative" }
        require(idempotencyKeyCount >= 0) { "Task index idempotency count must not be negative" }
        require(activeTaskCount <= taskCount) { "Task index active count exceeds task count" }
        require(idempotencyKeyCount <= taskCount) {
            "Task index idempotency count exceeds task count"
        }
        require(unreadableEntries.distinct().size == unreadableEntries.size) {
            "Task index unreadable entries must be unique"
        }
    }

    val isCorrupted: Boolean get() = unreadableEntries.isNotEmpty()
}
