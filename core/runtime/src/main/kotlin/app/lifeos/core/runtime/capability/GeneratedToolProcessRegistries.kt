package app.lifeos.core.runtime.capability

object GeneratedToolRuntimeProcessRegistry {
    @Volatile private var capabilities: CapabilityRegistry? = null
    @Volatile private var tools: GeneratedToolRegistry? = null
    @Volatile private var lifecycle: GeneratedToolLifecycleCoordinator? = null

    fun installCapabilities(value: CapabilityRegistry) { capabilities = value }
    fun installTools(value: GeneratedToolRegistry) { tools = value }
    fun installLifecycle(value: GeneratedToolLifecycleCoordinator) { lifecycle = value }

    fun capabilities(): CapabilityRegistry? = capabilities
    fun tools(): GeneratedToolRegistry? = tools
    fun lifecycle(): GeneratedToolLifecycleCoordinator? = lifecycle
}

object HotSwapBootRuntimeRegistry {
    @Volatile private var reconciler: HotSwapBootReconciler? = null

    fun install(value: HotSwapBootReconciler) { reconciler = value }
    suspend fun reconcileIfInstalled(): HotSwapBootReconciliationReport? = reconciler?.reconcile()
}
