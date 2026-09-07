package app.lifeos.core.runtime

import app.lifeos.core.model.Photon
import kotlinx.coroutines.flow.StateFlow

interface LifeOsRuntime {
    val state: StateFlow<RuntimeState>

    fun start()

    fun stop()

    suspend fun ingest(photon: Photon)
}
