package app.lifeos.next

import app.lifeos.core.runtime.life.InitialDataBootstrapRuntime
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class InitialDataProcessController(
    private val bootstrap: () -> InitialDataBootstrapRuntime,
    private val startupReady: () -> Boolean,
    private val onSnapshot: (InitialDataBootstrapSnapshot) -> Unit,
    private val onFailure: (String?) -> Unit,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    fun refresh() {
        if (!startupReady()) {
            val available = runCatching { bootstrap() }.isSuccess
            if (!available) return
        }
        scope.launch {
            try {
                onSnapshot(bootstrap().run())
                onFailure(null)
            } catch (error: Exception) {
                onFailure(
                    error.message ?: error::class.simpleName
                    ?: "initial-data-bootstrap-failed",
                )
            }
        }
    }
}
