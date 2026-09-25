package app.lifeos.core.runtime.android

import app.lifeos.core.field.EvidenceKind
import app.lifeos.core.field.EvidencePayload
import app.lifeos.core.field.EvidenceReliability
import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SemanticEvidenceCandidate
import app.lifeos.core.runtime.life.SemanticObservationProjector
import app.lifeos.core.runtime.world.StateDimensionId

class SemanticAppUiObservationProjector : SemanticObservationProjector {
    override val projectorId: String = "android-semantic-app-ui"
    override val domainId: FieldDomainId = FieldDomainId("app")

    override fun supports(observation: InformationObservation): Boolean =
        observation.surface == ObservationSurfaceKind.APP_UI &&
            observation.metadata["package"]?.isNotBlank() == true

    override fun project(observation: InformationObservation): List<SemanticEvidenceCandidate> {
        val packageName = requireNotNull(observation.metadata["package"])
        val windowRevision = observation.metadata["windowRevision"].orEmpty()
        val snapshotFingerprint = observation.metadata["snapshotFingerprint"].orEmpty()
        return listOf(
            SemanticEvidenceCandidate(
                stateDimension = StateDimensionId("app.ui." + packageName + ".current"),
                semanticKey = "semantic-surface",
                kind = EvidenceKind.OBSERVATION,
                confidence = 0.72,
                reliability = EvidenceReliability(
                    score = 0.55,
                    reason = "projected-accessibility-semantic-surface",
                ),
                payload = EvidencePayload(
                    type = "semantic-app-ui",
                    values = mapOf(
                        "package" to packageName,
                        "windowRevision" to windowRevision,
                        "snapshotFingerprint" to snapshotFingerprint,
                    ),
                ),
                explanation = "Observed bounded semantic Accessibility surface; not authoritative app state",
            )
        )
    }
}

class NotificationObservationProjector : SemanticObservationProjector {
    override val projectorId: String = "android-notification-observation"
    override val domainId: FieldDomainId = FieldDomainId("communication")

    override fun supports(observation: InformationObservation): Boolean =
        observation.surface == ObservationSurfaceKind.NOTIFICATION

    override fun project(observation: InformationObservation): List<SemanticEvidenceCandidate> =
        listOf(
            SemanticEvidenceCandidate(
                stateDimension = StateDimensionId("communication.notification.current"),
                semanticKey = "notification-observed",
                kind = EvidenceKind.MESSAGE_EVENT,
                confidence = 0.78,
                reliability = EvidenceReliability(
                    score = 0.68,
                    reason = "platform-notification-surface",
                ),
                payload = EvidencePayload(
                    type = "notification-observation",
                    values = mapOf(
                        "resource" to observation.sourceResource,
                        "payloadFingerprint" to StableCognitiveIds.fingerprint(
                            "notification-payload/v1",
                            observation.payload,
                        ),
                    ),
                ),
                explanation = "Platform notification observed; notification evidence is not source-app truth",
            )
        )
}
