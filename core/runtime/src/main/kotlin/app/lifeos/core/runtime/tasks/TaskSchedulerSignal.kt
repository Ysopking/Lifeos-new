package app.lifeos.core.runtime.tasks

import kotlinx.coroutines.channels.Channel

fun interface TaskSchedulerSignal {
    suspend fun wake()
}

fun interface TaskSchedulerWakeSource {
    suspend fun awaitWake()
}

class ConflatedTaskSchedulerSignal : TaskSchedulerSignal, TaskSchedulerWakeSource {
    private val channel = Channel<Unit>(Channel.CONFLATED)

    override suspend fun wake() {
        channel.send(Unit)
    }

    override suspend fun awaitWake() {
        channel.receive()
    }
}
