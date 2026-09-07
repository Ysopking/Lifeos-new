package app.lifeos.core.runtime.tasks

import app.lifeos.core.model.task.LifeTask

fun interface ClaimedTaskDispatcher {
    suspend fun dispatch(task: LifeTask)
}
