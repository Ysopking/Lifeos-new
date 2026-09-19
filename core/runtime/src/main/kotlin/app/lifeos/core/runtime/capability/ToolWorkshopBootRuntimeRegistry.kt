package app.lifeos.core.runtime.capability

/**
 * Narrow non-owning process bridge used by the generated-tool boot rehydrator.
 *
 * The productive reconciler is composed from durable repositories first and only then installed
 * here. Clearing this registry loses no state; restart reconstruction comes from durable ledgers.
 */
object ToolWorkshopBootRuntimeRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<suspend () -> Unit>(
            "ToolWorkshop boot reconciler"
        )

    fun install(value: suspend () -> Unit) {
        slot.install(value)
    }

    suspend fun reconcileIfInstalled() {
        slot.currentOrNull()?.invoke()
    }

    internal fun clearForTests() {
        slot.clear()
    }
}
