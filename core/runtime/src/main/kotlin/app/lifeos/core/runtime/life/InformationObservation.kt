package app.lifeos.core.runtime.life

import app.lifeos.core.model.StableCognitiveIds
import java.time.Instant

@JvmInline
value class InformationObservationId(val value: String) {
    init {
        require(value.startsWith(PREFIX)) { "Invalid information observation id prefix" }
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}"))) {
            "Invalid information observation id digest"
        }
    }

    override fun toString(): String = value

    companion object {
        const val PREFIX = "observation:"
    }
}

enum class ObservationSurfaceKind {
    NOTIFICATION,
    CONTENT_PROVIDER,
    FILE,
    SENSOR,
    WEB,
    APP_UI,
    APP_USAGE,
    API,
    OWNER_INPUT,
}

enum class ObservationAuthorityClass(val rank: Int) {
    DERIVED_INFERENCE(0),
    UI_OBSERVATION(1),
    PLATFORM_NOTIFICATION(2),
    PLATFORM_PROVIDER(3),
    OWNER_PROVIDED_EXPORT(4),
    AUTHENTICATED_API(5),
    AUTHORITATIVE_PROVIDER(6),
}

enum class ObservationPrivacyClass {
    PUBLIC,
    PERSONAL,
    SENSITIVE,
    RESTRICTED,
}

enum class RepresentationLevel {
    ACTUAL,
    PROJECTED,
    QUOTIENT,
    FORCED,
    DISTRIBUTION,
    POSSIBILITY,
}

enum class EpistemicStatus {
    OBSERVED,
    INFERRED,
    BELIEVED,
    OWNER_CONFIRMED,
    RECONCILED,
}

enum class TemporalStatus {
    CURRENT,
    HISTORY,
    PREDICTED,
}

enum class ControlStatus {
    PASSIVE,
    SCHEDULED,
    ACTION_GENERATED,
}

data class RealizationDescriptor(
    val representation: RepresentationLevel,
    val epistemicStatus: EpistemicStatus,
    val temporalStatus: TemporalStatus,
    val controlStatus: ControlStatus,
) {
    val fingerprint: String = StableCognitiveIds.fingerprint(
        "realization-descriptor/v1",
        representation.name,
        epistemicStatus.name,
        temporalStatus.name,
        controlStatus.name,
    )
}

/**
 * B451 canonical observation envelope.
 *
 * Observation identity describes what was observed and is deliberately independent from the Owner
 * Observation Policy grant that later authorizes persistence. Authorization is provenance, not a
 * mutation of the observed source event.
 *
 * Invariants:
 * - observation != fact
 * - observation != complete state
 * - usage != intent
 * - inference != owner confirmation
 */
data class InformationObservation(
    val sourceId: String,
    val sourceResource: String,
    val surface: ObservationSurfaceKind,
    val observedAt: Instant,
    val sourceTimestamp: Instant? = null,
    val sourceRevision: String? = null,
    val mimeType: String,
    val payload: String,
    val realization: RealizationDescriptor,
    val authority: ObservationAuthorityClass,
    val privacy: ObservationPrivacyClass,
    val observationGrantId: String? = null,
    val confidence: Double? = null,
    val tags: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(sourceId.isNotBlank()) { "Observation source id must not be blank" }
        require(sourceResource.isNotBlank()) { "Observation resource must not be blank" }
        require(mimeType.isNotBlank()) { "Observation MIME type must not be blank" }
        require(sourceRevision == null || sourceRevision.isNotBlank()) {
            "Observation source revision must be null or non-blank"
        }
        require(observationGrantId == null || observationGrantId.isNotBlank()) {
            "Observation grant id must be null or non-blank"
        }
        require(confidence == null || (confidence.isFinite() && confidence in 0.0..1.0)) {
            "Observation confidence must be null or finite in 0..1"
        }
        require(tags.none { it.isBlank() }) { "Observation tags must not be blank" }
        require(metadata.keys.none { it.isBlank() }) { "Observation metadata keys must not be blank" }
    }

    val contentFingerprint: String = StableCognitiveIds.fingerprint(
        "information-observation-content/v1",
        mimeType,
        payload,
    )

    val sourceObservationFingerprint: String = StableCognitiveIds.fingerprint(
        "information-observation-source/v1",
        sourceId,
        sourceResource,
        surface.name,
        observedAt.toString(),
        sourceTimestamp?.toString().orEmpty(),
        sourceRevision.orEmpty(),
        contentFingerprint,
        realization.fingerprint,
        authority.name,
        privacy.name,
        java.lang.Double.toHexString(confidence ?: 1.0),
        *tags.sorted().toTypedArray(),
        *metadata.toSortedMap().flatMap { (key, value) ->
            listOf("meta-key:$key", "meta-value:$value")
        }.toTypedArray(),
    )

    val id: InformationObservationId = InformationObservationId(
        InformationObservationId.PREFIX + sourceObservationFingerprint
    )

    val provenanceFingerprint: String = StableCognitiveIds.fingerprint(
        "information-observation-provenance/v1",
        sourceObservationFingerprint,
        observationGrantId.orEmpty(),
    )

    fun authorizedBy(grantId: String): InformationObservation {
        require(grantId.isNotBlank())
        return copy(observationGrantId = grantId)
    }

    fun toPerceptionSignal(
        salience: Double = 0.5,
        extraTags: Set<String> = emptySet(),
    ): PerceptionSignal = PerceptionSignal(
        source = when (surface) {
            ObservationSurfaceKind.FILE -> PerceptionSource.FILE
            ObservationSurfaceKind.SENSOR -> PerceptionSource.SENSOR
            ObservationSurfaceKind.OWNER_INPUT -> PerceptionSource.HUMAN_FEEDBACK
            else -> PerceptionSource.APP_EVENT
        },
        sourceId = sourceId,
        observedAt = observedAt,
        payload = payload,
        mimeType = mimeType,
        confidence = confidence ?: 1.0,
        salience = salience,
        tags = buildSet {
            addAll(tags)
            addAll(extraTags)
            add("information-observation")
            add("observation-id:${id.value}")
            add("observation-surface:${surface.name.lowercase()}")
            add("observation-authority:${authority.name.lowercase()}")
            add("representation:${realization.representation.name.lowercase()}")
            add("epistemic:${realization.epistemicStatus.name.lowercase()}")
            add("temporal:${realization.temporalStatus.name.lowercase()}")
            observationGrantId?.let { add("observation-grant:$it") }
        },
    )
}
