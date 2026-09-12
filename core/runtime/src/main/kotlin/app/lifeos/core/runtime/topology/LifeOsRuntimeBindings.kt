package app.lifeos.core.runtime.topology

/** Runtime presence/health binding for one canonical LIFEOS subsystem. */
enum class LifeOsRuntimeBindingState {
    REGISTERED,
    ACTIVE,
    DEGRADED,
    QUARANTINED,
    STOPPED,
}

data class LifeOsRuntimeBinding(
    val subsystemId: String,
    val state: LifeOsRuntimeBindingState,
    val source: String,
    val detail: String? = null,
) {
    init {
        require(subsystemId.isNotBlank())
        require(source.isNotBlank())
    }
}

/**
 * Single process registry for subsystem lifecycle only.
 * Capability/provider ownership remains exclusively in CapabilityRegistry.
 */
object LifeOsRuntimeBindingRegistry {
    private val lock = Any()
    private val bindings = linkedMapOf<String, LifeOsRuntimeBinding>()

    fun install(
        subsystemId: String,
        state: LifeOsRuntimeBindingState = LifeOsRuntimeBindingState.ACTIVE,
        source: String,
        detail: String? = null,
    ): LifeOsRuntimeBinding = synchronized(lock) {
        val binding = LifeOsRuntimeBinding(subsystemId, state, source, detail)
        bindings[subsystemId] = binding
        binding
    }

    fun installAll(
        subsystemIds: Iterable<String>,
        state: LifeOsRuntimeBindingState = LifeOsRuntimeBindingState.ACTIVE,
        source: String,
    ) = synchronized(lock) {
        subsystemIds.forEach { subsystemId ->
            require(subsystemId.isNotBlank())
            bindings[subsystemId] = LifeOsRuntimeBinding(subsystemId, state, source)
        }
    }

    fun update(
        subsystemId: String,
        state: LifeOsRuntimeBindingState,
        detail: String? = null,
    ): LifeOsRuntimeBinding = synchronized(lock) {
        val previous = requireNotNull(bindings[subsystemId]) {
            "Subsystem is not bound: $subsystemId"
        }
        previous.copy(state = state, detail = detail).also { bindings[subsystemId] = it }
    }

    fun current(subsystemId: String): LifeOsRuntimeBinding? = synchronized(lock) {
        bindings[subsystemId]
    }

    fun snapshot(): Map<String, LifeOsRuntimeBinding> = synchronized(lock) {
        bindings.toMap()
    }

    internal fun clearForTests() = synchronized(lock) {
        bindings.clear()
    }
}
