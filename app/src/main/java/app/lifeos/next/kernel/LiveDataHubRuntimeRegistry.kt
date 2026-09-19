package app.lifeos.next.kernel

import app.lifeos.core.runtime.livedata.LiveDataHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Process-local M01 bridge for Android connector services.
 *
 * It owns no persistence. Every connector receives the exact LiveDataHub owned by
 * CanonicalPhotonIngress and therefore the canonical revisioned Photon store/ingress.
 */
object LiveDataHubRuntimeRegistry {
    private val current = MutableStateFlow<LiveDataHub?>(null)

    fun install(hub: LiveDataHub) {
        current.value = hub
    }

    fun currentOrNull(): LiveDataHub? = current.value

    suspend fun await(): LiveDataHub = current.filterNotNull().first()

    internal fun clearForTests() {
        current.value = null
    }
}
