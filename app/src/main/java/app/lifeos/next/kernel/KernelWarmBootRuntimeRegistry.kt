package app.lifeos.next.kernel

internal object KernelWarmBootRuntimeRegistry {
    @Volatile
    private var current: KernelWarmBootRuntime? = null

    fun install(runtime: KernelWarmBootRuntime) {
        current = runtime
    }

    fun requireCurrent(): KernelWarmBootRuntime =
        requireNotNull(current) { "Kernel warm boot runtime is not installed" }

    internal fun clearForTests() {
        current = null
    }
}
