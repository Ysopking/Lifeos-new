package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.InformationObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Process-local bridge from Android's NotificationListenerService into the canonical information
 * observation boundary. It owns no durable/shadow state: producers wait until productive ingress is
 * ready. B452 inserts Owner Observation Policy before the observation becomes a durable Photon.
 */
object LiveNotificationPhotonIngress {
    private val handler =
        MutableStateFlow<(suspend (InformationObservation) -> Unit)?>(null)

    fun install(ingest: suspend (InformationObservation) -> Unit) {
        handler.value = ingest
    }

    suspend fun ingest(observation: InformationObservation) {
        handler.filterNotNull().first().invoke(observation)
    }

    internal fun clearForTests() {
        handler.value = null
    }
}
