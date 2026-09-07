package app.lifeos.core.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RuntimeSupervisor(
    private val runtime: LifeOsRuntime,
) {
    private val mutex = Mutex()

    suspend fun start() = mutex.withLock {
        when (runtime.state.value.status) {
            RuntimeStatus.STARTING,
            RuntimeStatus.RUNNING,
            -> return@withLock

            else -> runtime.start()
        }
    }

    suspend fun stop() = mutex.withLock {
        when (runtime.state.value.status) {
            RuntimeStatus.CREATED,
            RuntimeStatus.STOPPING,
            RuntimeStatus.STOPPED,
            -> return@withLock

            else -> runtime.stop()
        }
    }

    suspend fun restart() = mutex.withLock {
        runtime.stop()
        runtime.start()
    }
}
