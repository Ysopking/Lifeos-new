package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.InformationObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Process-local hand-off from Android's NotificationListenerService to the registered B482
 * notification sensor bridge. It owns no durable/shadow state: producers wait until the productive
 * sensor handler is ready. B467 then applies structural validation, Owner Observation Policy and
 * canonical ORIGIN Photon persistence before Continuous Cognition.
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
