package app.lifeos.core.runtime.life

import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerObservationDecision
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationRequest

data class BlockedInformationObservation(
    val observationId: InformationObservationId,
    val policyRevision: Long,
    val reasons: List<String>,
) {
    init {
        require(policyRevision >= 0L)
        require(reasons.isNotEmpty())
        require(reasons == reasons.distinct().sorted()) {
            "Blocked observation reasons must be unique and canonical"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "blocked-information-observation/v1",
        observationId.value,
        policyRevision.toString(),
        *reasons.toTypedArray(),
    )
}

data class OwnerAuthorizedObservationBatch(
    val sensorId: SensorId,
    val authorized: List<InformationObservation>,
    val blocked: List<BlockedInformationObservation>,
) {
    init {
        require(
            authorized == authorized.sortedWith(
                compareBy<InformationObservation> { it.observedAt }
                    .thenBy { it.id.value }
            )
        ) {
            "Authorized observations must use deterministic ordering"
        }
        require(
            blocked == blocked.sortedBy { it.observationId.value }
        ) {
            "Blocked observations must use deterministic ordering"
        }
        require(authorized.all { it.observationGrantId != null }) {
            "Authorized observations must carry Owner Observation Policy provenance"
        }
        val allIds =
            authorized.map { it.id.value } + blocked.map { it.observationId.value }
        require(allIds.distinct().size == allIds.size) {
            "Observation may not be both authorized and blocked"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "owner-authorized-observation-batch/v1",
        sensorId.value,
        *authorized.map { "authorized:${it.provenanceFingerprint}" }.toTypedArray(),
        *blocked.map { "blocked:${it.fingerprint}" }.toTypedArray(),
    )

    val effectAuthority: Boolean
        get() = false

    val ownerPolicyEffectAuthority: Boolean
        get() = false
}

/**
 * B466 applies the dedicated Owner Observation Policy after adapter validation.
 *
 * The adapter remains incapable of self-authorizing. This boundary binds an existing owner grant
 * to an immutable InformationObservation as provenance only. It cannot authorize effects, activate
 * capabilities or promote an observation into fact/state.
 */
class OwnerAuthorizedAppObservationIngress(
    private val observationPolicy: OwnerObservationPolicyLedger,
    private val actorId: OwnerActorId,
    private val scope: String,
) {
    init {
        require(scope.isNotBlank())
    }

    suspend fun authorize(
        descriptor: SensorDescriptor,
        batch: AppObservationBatch,
    ): OwnerAuthorizedObservationBatch {
        require(batch.sensorId == descriptor.sensorId) {
            "Authorized observation batch sensor differs from descriptor"
        }

        val authorized = mutableListOf<InformationObservation>()
        val blocked = mutableListOf<BlockedInformationObservation>()

        batch.observations.forEach { observation ->
            require(observation.sourceId == descriptor.sensorId.value) {
                "Observation source id differs from registered sensor"
            }
            require(observation.sourceResource.startsWith(descriptor.resourcePrefix)) {
                "Observation resource is outside registered sensor prefix"
            }
            require(observation.surface in descriptor.supportedSurfaces) {
                "Observation surface is outside registered sensor contract"
            }
            require(observation.observationGrantId == null) {
                "Sensor observation arrived pre-authorized"
            }

            when (
                val decision = observationPolicy.evaluate(
                    OwnerObservationRequest(
                        actorId = actorId,
                        observationType = descriptor.observationType,
                        resource = observation.sourceResource,
                        scope = scope,
                        sensorId = descriptor.sensorId.value,
                    ),
                    at = observation.observedAt,
                )
            ) {
                is OwnerObservationDecision.Allowed ->
                    authorized += observation.authorizedBy(
                        decision.grantId.value
                    )

                is OwnerObservationDecision.Blocked ->
                    blocked += BlockedInformationObservation(
                        observationId = observation.id,
                        policyRevision = decision.policyRevision,
                        reasons = decision.reasons.distinct().sorted(),
                    )
            }
        }

        return OwnerAuthorizedObservationBatch(
            sensorId = descriptor.sensorId,
            authorized = authorized.sortedWith(
                compareBy<InformationObservation> { it.observedAt }
                    .thenBy { it.id.value }
            ),
            blocked = blocked.sortedBy { it.observationId.value },
        )
    }
}
