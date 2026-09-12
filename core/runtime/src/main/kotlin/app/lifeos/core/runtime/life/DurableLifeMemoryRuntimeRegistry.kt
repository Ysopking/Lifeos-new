package app.lifeos.core.runtime.life

/** Process seam used by authorized Android/source adapters after boot. */
object DurableLifeMemoryRuntimeRegistry {
    @Volatile
    private var installed: DurableLifeMemoryRuntime? = null

    fun install(runtime: DurableLifeMemoryRuntime) {
        installed = runtime
    }

    fun current(): DurableLifeMemoryRuntime? = installed
}
