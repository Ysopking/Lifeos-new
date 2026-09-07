package app.lifeos.core.runtime.health

/** All state changes use the shared, durable protection repository. */
class SafeModeController(internal val controls: HealthControlRepository = HealthControlRepository()) {
    val active: Boolean get() = controls.snapshot().safeReasons.isNotEmpty()
    fun enter(reason: String) = controls.protect(reason, quarantine = false)
}

class QuarantineRegistry(internal val controls: HealthControlRepository = HealthControlRepository()) {
    fun contains(node: HealthNode): Boolean = node.id in controls.snapshot().quarantined
    fun isolate(node: HealthNode) = controls.protect(node.id, quarantine = true)
}
