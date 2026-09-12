package app.lifeos.core.runtime.capability

/**
 * Single process owner for the productive capability/generated-tool graph.
 *
 * Once a CapabilityRegistry and GeneratedToolRegistry have become a ready pair, temporary planning
 * registries (for example Genesis composition sandboxes) must never replace either process owner.
 * A second productive kernel in the same process is likewise rejected instead of silently creating
 * a split-brain capability/tool pair.
 */
object GeneratedToolRuntimeProcessRegistry {
    private val lock = Any()

    @Volatile private var capabilities: CapabilityRegistry? = null
    @Volatile private var tools: GeneratedToolRegistry? = null
    @Volatile private var lifecycle: GeneratedToolLifecycleCoordinator? = null
    private val readyListeners = mutableListOf<(CapabilityRegistry, GeneratedToolRegistry) -> Unit>()

    fun installCapabilities(value: CapabilityRegistry) {
        val callbacks = synchronized(lock) {
            val current = capabilities
            if (current != null && current !== value && tools != null) {
                // Productive pair is already sealed. Detached/planning registries remain local only.
                return@synchronized emptyList()
            }
            capabilities = value
            drainReadyListenersLocked()
        }
        callbacks.forEach { it.first(it.second.first, it.second.second) }
    }

    fun installTools(value: GeneratedToolRegistry) {
        val callbacks = synchronized(lock) {
            val current = tools
            if (current != null && current !== value && capabilities != null) {
                // Do not split an already-ready process pair.
                return@synchronized emptyList()
            }
            tools = value
            drainReadyListenersLocked()
        }
        callbacks.forEach { it.first(it.second.first, it.second.second) }
    }

    fun installLifecycle(value: GeneratedToolLifecycleCoordinator) {
        synchronized(lock) {
            val current = lifecycle
            require(current == null || current === value) {
                "Productive generated-tool lifecycle is already installed for this process"
            }
            lifecycle = value
        }
    }

    fun whenReady(listener: (CapabilityRegistry, GeneratedToolRegistry) -> Unit) {
        val immediate = synchronized(lock) {
            val currentCapabilities = capabilities
            val currentTools = tools
            if (currentCapabilities != null && currentTools != null) {
                currentCapabilities to currentTools
            } else {
                readyListeners += listener
                null
            }
        }
        immediate?.let { listener(it.first, it.second) }
    }

    fun capabilities(): CapabilityRegistry? = capabilities
    fun tools(): GeneratedToolRegistry? = tools
    fun lifecycle(): GeneratedToolLifecycleCoordinator? = lifecycle

    private fun drainReadyListenersLocked(): List<Pair<(CapabilityRegistry, GeneratedToolRegistry) -> Unit, Pair<CapabilityRegistry, GeneratedToolRegistry>>> {
        val currentCapabilities = capabilities ?: return emptyList()
        val currentTools = tools ?: return emptyList()
        if (readyListeners.isEmpty()) return emptyList()
        val callbacks = readyListeners.map { listener -> listener to (currentCapabilities to currentTools) }
        readyListeners.clear()
        return callbacks
    }

    internal fun clearForTests() = synchronized(lock) {
        capabilities = null
        tools = null
        lifecycle = null
        readyListeners.clear()
    }
}

object HotSwapBootRuntimeRegistry {
    @Volatile private var reconciler: HotSwapBootReconciler? = null

    fun install(value: HotSwapBootReconciler) { reconciler = value }
    suspend fun reconcileIfInstalled(): HotSwapBootReconciliationReport? = reconciler?.reconcile()
}
