package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.runtime.CognitiveRuntime
import app.lifeos.core.runtime.InfluenceExecutor
import app.lifeos.core.runtime.RuntimeSupervisor
import app.lifeos.core.runtime.StaticFieldRegistry
import app.lifeos.core.runtime.ThoughtMatrix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Single composition point for the current process-level LIFEOS runtime graph. */
class LifeOsKernelFactory(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun create(): LifeOsKernel {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val store = EncryptedPhotonStore(context.applicationContext)
        val matrix = ThoughtMatrix()
        val registry = StaticFieldRegistry(listOf(matrix))
        val executor = InfluenceExecutor()
        val runtime = CognitiveRuntime(
            scope = scope,
            fieldRegistry = registry,
            influenceExecutor = executor,
        )
        val supervisor = RuntimeSupervisor(runtime)

        return LifeOsKernel(
            runtime = runtime,
            matrix = matrix,
            photonStore = store,
            supervisor = supervisor,
            scope = scope,
        )
    }
}
