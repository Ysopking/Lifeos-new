package app.lifeos.core.runtime.tasks

fun interface TaskSchedulerSignal {
    suspend fun wake()
}
