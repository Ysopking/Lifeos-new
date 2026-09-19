package app.lifeos.next.kernel

import app.lifeos.core.runtime.livedata.LiveDataAccountObservation
import app.lifeos.core.runtime.livedata.LiveDataDelta
import app.lifeos.core.runtime.livedata.LiveDataIngestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Process-local bridge for push-style Android sources. It owns no durable state: the installed
 * LiveDataHub authority persists account state, source Photon and canonical metadata companion.
 */
object PushLiveDataIngress {
    private val handler = MutableStateFlow<
        (suspend (LiveDataAccountObservation, LiveDataDelta) -> LiveDataIngestResult)?
    >(null)

    fun install(
        ingest: suspend (LiveDataAccountObservation, LiveDataDelta) -> LiveDataIngestResult,
    ) {
        handler.value = ingest
    }

    suspend fun ingest(
        observation: LiveDataAccountObservation,
        delta: LiveDataDelta,
    ): LiveDataIngestResult =
        handler.filterNotNull().first().invoke(observation, delta)

    internal fun clearForTests() {
        handler.value = null
    }
}
