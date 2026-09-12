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
 * Capability/provider ownership remains exclusively in CapabilityRegistry. Every binding id must
 * already exist in the canonical typed manifest graph; callers cannot extend topology with strings.
 */
object LifeOsRuntimeBindingRegistry {
    private val lock = Any()
    private val bindings = linkedMapOf<String, LifeOsRuntimeBinding>()

    fun install(
        subsystemId: SubsystemId,
        state: LifeOsRuntimeBindingState = LifeOsRuntimeBindingState.ACTIVE,
        source: String,
        detail: String? = null,
    ): LifeOsRuntimeBinding = synchronized(lock) {
        requireKnown(subsystemId)
        val binding = LifeOsRuntimeBinding(subsystemId.value, state, source, detail)
        bindings[subsystemId.value] = binding
        binding
    }

    fun install(
        subsystemId: String,
        state: LifeOsRuntimeBindingState = LifeOsRuntimeBindingState.ACTIVE,
        source: String,
        detail: String? = null,
    ): LifeOsRuntimeBinding = install(SubsystemId(subsystemId), state, source, detail)

    fun installAll(
        subsystemIds: Iterable<SubsystemId>,
        state: LifeOsRuntimeBindingState = LifeOsRuntimeBindingState.ACTIVE,
        source: String,
    ) = synchronized(lock) {
        val ids = subsystemIds.toList()
        ids.forEach(::requireKnown)
        ids.forEach { subsystemId ->
            bindings[subsystemId.value] = LifeOsRuntimeBinding(subsystemId.value, state, source)
        }
    }

    @JvmName("installAllStrings")
    fun installAll(
        subsystemIds: Iterable<String>,
        state: LifeOsRuntimeBindingState = LifeOsRuntimeBindingState.ACTIVE,
        source: String,
    ) = installAll(subsystemIds.map(::SubsystemId), state, source)

    fun update(
        subsystemId: SubsystemId,
        state: LifeOsRuntimeBindingState,
        detail: String? = null,
    ): LifeOsRuntimeBinding = synchronized(lock) {
        requireKnown(subsystemId)
        val previous = requireNotNull(bindings[subsystemId.value]) {
            "Subsystem is not bound: ${subsystemId.value}"
        }
        previous.copy(state = state, detail = detail).also { bindings[subsystemId.value] = it }
    }

    fun update(
        subsystemId: String,
        state: LifeOsRuntimeBindingState,
        detail: String? = null,
    ): LifeOsRuntimeBinding = update(SubsystemId(subsystemId), state, detail)

    fun current(subsystemId: SubsystemId): LifeOsRuntimeBinding? = synchronized(lock) {
        bindings[subsystemId.value]
    }

    fun current(subsystemId: String): LifeOsRuntimeBinding? = current(SubsystemId(subsystemId))

    fun snapshot(): Map<String, LifeOsRuntimeBinding> = synchronized(lock) {
        bindings.toMap()
    }

    internal fun clearForTests() = synchronized(lock) {
        bindings.clear()
    }

    private fun requireKnown(subsystemId: SubsystemId) {
        require(LifeOsProcessTopology.isKnownSubsystem(subsystemId)) {
            "Unknown LIFEOS subsystem cannot be bound: ${subsystemId.value}"
        }
    }
}
