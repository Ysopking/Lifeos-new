package app.lifeos.next.kernel

/** Narrow process registry used by private diagnostics and later V10/V11 automation paths. */
object PrivateHotSwapRuntimeRegistry {
    @Volatile
    private var installed: PrivateHotSwapRuntime? = null

    fun install(runtime: PrivateHotSwapRuntime) {
        installed = runtime
    }

    fun current(): PrivateHotSwapRuntime? = installed
}
