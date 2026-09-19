package app.lifeos.core.runtime.process

/**
 * Non-owning process-local indirection for runtime services.
 *
 * This slot deliberately has no serialization, revision, replay or persistence semantics. Durable
 * state must be reconstructed by composition-owned repositories before a service is installed.
 * Re-installation replaces the previous process instance so process recreation cannot make an old
 * runtime object authoritative.
 */
internal class NonOwningRuntimeSlot<T : Any>(
    private val name: String,
) {
    init { require(name.isNotBlank()) }

    @Volatile
    private var installed: T? = null

    fun install(value: T) {
        synchronized(this) { installed = value }
    }

    fun currentOrNull(): T? = installed

    fun requireCurrent(): T =
        requireNotNull(installed) { "$name is not installed" }

    fun clear() {
        synchronized(this) { installed = null }
    }
}
