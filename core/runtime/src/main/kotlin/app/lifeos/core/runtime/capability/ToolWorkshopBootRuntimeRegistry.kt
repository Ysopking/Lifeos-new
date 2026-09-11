package app.lifeos.core.runtime.capability

/**
 * Narrow process bridge used by the generated-tool boot rehydrator. The app installs the productive
 * V11 reconciler, while core keeps no Android dependency. Reconciliation runs only after durable
 * generated-tool lifecycle state has been restored, so open workshop jobs can safely rebind exact
 * stage artifacts without reconstructing a parallel registry.
 */
object ToolWorkshopBootRuntimeRegistry {
    @Volatile private var reconciler: (suspend () -> Unit)? = null

    fun install(value: suspend () -> Unit) {
        reconciler = value
    }

    suspend fun reconcileIfInstalled() {
        reconciler?.invoke()
    }
}
