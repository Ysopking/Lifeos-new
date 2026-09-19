package app.lifeos.core.runtime.life

/** Process seam used by authorized Android/source adapters after boot. It owns no durable state. */
object DurableLifeMemoryRuntimeRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<DurableLifeMemoryRuntime>(
            "Durable life-memory runtime"
        )

    fun install(runtime: DurableLifeMemoryRuntime) {
        slot.install(runtime)
    }

    fun current(): DurableLifeMemoryRuntime? = slot.currentOrNull()

    internal fun clearForTests() {
        slot.clear()
    }
}
