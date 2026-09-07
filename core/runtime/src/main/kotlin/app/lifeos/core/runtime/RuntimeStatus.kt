package app.lifeos.core.runtime

enum class RuntimeStatus {
    CREATED,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPING,
    STOPPED,
    FAILED,
}
