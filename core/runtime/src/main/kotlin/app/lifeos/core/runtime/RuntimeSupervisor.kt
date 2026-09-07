package app.lifeos.core.runtime

import kotlinx.coroutines.flow.first
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
            RuntimeStatus.STOPPING -> return@withLock

            else -> runtime.start()
        }
    }

    suspend fun stop() = mutex.withLock {
        when (runtime.state.value.status) {
            RuntimeStatus.CREATED,
            RuntimeStatus.STOPPING,
            RuntimeStatus.STOPPED -> return@withLock

            else -> runtime.stop()
        }
    }

    suspend fun restart() = mutex.withLock {
        when (runtime.state.value.status) {
            RuntimeStatus.CREATED,
            RuntimeStatus.STOPPED -> {
                runtime.start()
                return@withLock
            }

            RuntimeStatus.STOPPING -> Unit
            else -> runtime.stop()
        }

        val stopped = runtime.state.first { state ->
            state.status == RuntimeStatus.STOPPED || state.status == RuntimeStatus.FAILED
        }
        check(stopped.status == RuntimeStatus.STOPPED) {
            stopped.lastFailure?.message ?: "Runtime failed while stopping for restart"
        }
        runtime.start()
    }
}
