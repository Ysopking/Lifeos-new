package app.lifeos.core.runtime.health

enum class HealthState { UNKNOWN, HEALTHY, DEGRADED, UNHEALTHY, RECOVERING, QUARANTINED, DISABLED }

data class HealthNode(val id: String, val requiredForRuntime: Boolean = false) {
    init { require(id.isNotBlank()) }
}

data class HealthObservation(
    val nodeId: String,
    val state: HealthState,
    val sequence: Long,
    val reason: String,
)

object HealthNodes {
    val Kernel = HealthNode("Kernel", true)
    val Runtime = HealthNode("Runtime", true)
    val TaskScheduler = HealthNode("TaskScheduler", true)
    val Worker = HealthNode("Worker")
    val PhotonStore = HealthNode("PhotonStore", true)
    val TaskStore = HealthNode("TaskStore", true)
    val CheckpointStore = HealthNode("CheckpointStore")
    val Memory = HealthNode("Memory")
    val all = listOf(Kernel, Runtime, TaskScheduler, Worker, PhotonStore, TaskStore,
        CheckpointStore, Memory) + listOf("Chat", "Planner", "Finance", "External Apps", "BuildStudio")
        .map { HealthNode(it) }
}
