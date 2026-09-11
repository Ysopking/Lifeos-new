package app.lifeos.core.runtime.capability

object GeneratedToolRuntimeProcessRegistry {
    private val lock = Any()

    @Volatile private var capabilities: CapabilityRegistry? = null
    @Volatile private var tools: GeneratedToolRegistry? = null
    @Volatile private var lifecycle: GeneratedToolLifecycleCoordinator? = null
    private val readyListeners = mutableListOf<(CapabilityRegistry, GeneratedToolRegistry) -> Unit>()

    fun installCapabilities(value: CapabilityRegistry) {
        val callbacks = synchronized(lock) {
            capabilities = value
            drainReadyListenersLocked()
        }
        callbacks.forEach { it.first(it.second.first, it.second.second) }
    }

    fun installTools(value: GeneratedToolRegistry) {
        val callbacks = synchronized(lock) {
            tools = value
            drainReadyListenersLocked()
        }
        callbacks.forEach { it.first(it.second.first, it.second.second) }
    }

    fun installLifecycle(value: GeneratedToolLifecycleCoordinator) {
        lifecycle = value
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
}

object HotSwapBootRuntimeRegistry {
    @Volatile private var reconciler: HotSwapBootReconciler? = null

    fun install(value: HotSwapBootReconciler) { reconciler = value }
    suspend fun reconcileIfInstalled(): HotSwapBootReconciliationReport? = reconciler?.reconcile()
}
