package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Process-local bridge from Android's NotificationListenerService into the one canonical Photon
 * ingress. It owns no durable/shadow state: producers wait until the productive ingress is ready.
 */
object LiveNotificationPhotonIngress {
    private val handler = MutableStateFlow<(suspend (Photon) -> Unit)?>(null)

    fun install(ingest: suspend (Photon) -> Unit) {
        handler.value = ingest
    }

    suspend fun ingest(photon: Photon) {
        handler.filterNotNull().first().invoke(photon)
    }

    internal fun clearForTests() {
        handler.value = null
    }
}
