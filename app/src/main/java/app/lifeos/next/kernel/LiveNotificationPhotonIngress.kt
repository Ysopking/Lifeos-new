package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.PhotonIngressMode
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.PerceptionFusionEngine
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

/**
 * B467 canonical adapter from one owner-authorized InformationObservation to the productive Photon
 * ingress. The grant remains provenance only; the resulting Photon is still an ORIGIN observation
 * and must pass the normal durable cognition admission path.
 */
internal class CanonicalInformationObservationIngress(
    private val submit: suspend (Photon, PhotonIngressMode) -> PhotonSubmissionResult,
) {
    private val fusion = PerceptionFusionEngine()

    suspend fun ingest(
        observation: InformationObservation,
        salience: Double = DEFAULT_SALIENCE,
    ): PhotonSubmissionResult {
        require(observation.observationGrantId != null) {
            "Information observation must be owner-authorized before canonical commit"
        }
        require(salience.isFinite() && salience in 0.0..1.0) {
            "Observation salience must be finite in 0..1"
        }

        val photon = fusion
            .fuse(
                listOf(
                    observation.toPerceptionSignal(
                        salience = salience,
                        extraTags = setOf("owner-authorized-observation"),
                    )
                )
            )
            .photons
            .single()

        return submit(photon, PhotonIngressMode.ORIGIN)
    }

    private companion object {
        const val DEFAULT_SALIENCE = 0.5
    }
}

/**
 * Process-local bridge for every non-notification AppSensorAdapter after Owner Observation Policy.
 * Sensor implementations do not receive Photon-store or cognition authority directly.
 */
object AuthorizedObservationPhotonIngress {
    private val handler =
        MutableStateFlow<(suspend (InformationObservation) -> PhotonSubmissionResult)?>(null)

    fun install(
        ingest: suspend (InformationObservation) -> PhotonSubmissionResult,
    ) {
        handler.value = ingest
    }

    suspend fun ingest(
        observation: InformationObservation,
    ): PhotonSubmissionResult =
        handler.filterNotNull().first().invoke(observation)

    internal fun clearForTests() {
        handler.value = null
    }
}
