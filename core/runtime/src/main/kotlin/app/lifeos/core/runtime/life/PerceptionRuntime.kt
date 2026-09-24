package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.policy.OwnerObservationRequest
import app.lifeos.core.runtime.policy.OwnerObservationPolicyLedger
import app.lifeos.core.runtime.policy.OwnerObservationDecision
import app.lifeos.core.runtime.policy.OwnerActorId
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PerceptionSource {
    CHAT,
    SENSOR,
    FILE,
    APP_EVENT,
    TOOL_RESULT,
    HUMAN_FEEDBACK,
}

data class PerceptionSignal(
    val source: PerceptionSource,
    val sourceId: String,
    val observedAt: Instant,
    val payload: String,
    val mimeType: String = "text/plain",
    val confidence: Double = 1.0,
    val salience: Double = 0.5,
    val tags: Set<String> = emptySet(),
    val modality: PerceptionModality? = null,
    val observedUntil: Instant = observedAt,
    val candidates: List<PerceptionCandidate> = emptyList(),
    val sourceAssetPhotonId: PhotonId? = null,
) {
    init {
        require(sourceId.isNotBlank()) { "Perception source id must not be blank" }
        require(mimeType.isNotBlank()) { "Perception MIME type must not be blank" }
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(salience.isFinite() && salience in 0.0..1.0)
        require(tags.none { it.isBlank() })
        require(!observedUntil.isBefore(observedAt))
        if (modality == null) {
            require(observedUntil == observedAt && candidates.isEmpty() && sourceAssetPhotonId == null) {
                "Typed perception metadata requires an explicit modality"
            }
        }
    }

    val canonicalCandidates: List<PerceptionCandidate> = candidates.sortedWith(
        compareByDescending<PerceptionCandidate> { it.confidence }
            .thenBy { it.value }
            .thenBy { it.semanticTag.orEmpty() }
    )

    val candidateDistributionFingerprint: String? = modality?.let {
        StableCognitiveIds.fingerprint(
            "perception-candidate-distribution/v1",
            *canonicalCandidates.flatMap { candidate ->
                listOf(
                    candidate.value,
                    candidate.semanticTag.orEmpty(),
                    java.lang.Double.toHexString(candidate.confidence),
                )
            }.toTypedArray(),
        )
    }

    /** Generic Block-B callers retain their exact v1 replay identity. Typed M callers use v2. */
    val fingerprint: String = if (modality == null) {
        StableCognitiveIds.fingerprint(
            "perception-signal/v1",
            source.name,
            sourceId,
            observedAt.toString(),
            mimeType,
            payload,
            java.lang.Double.toHexString(confidence),
            java.lang.Double.toHexString(salience),
            *tags.sorted().toTypedArray(),
        )
    } else {
        StableCognitiveIds.fingerprint(
            "perception-signal/v2",
            source.name,
            sourceId,
            modality.name,
            observedAt.toString(),
            observedUntil.toString(),
            mimeType,
            payload,
            java.lang.Double.toHexString(confidence),
            java.lang.Double.toHexString(salience),
            sourceAssetPhotonId?.value.orEmpty(),
            candidateDistributionFingerprint.orEmpty(),
            *tags.sorted().toTypedArray(),
        )
    }
}

data class PerceptionBatch(
    val photons: List<Photon>,
    val batchFingerprint: String,
)

/**
 * Normalizes generic and typed observations into canonical Photons without performing domain
 * interpretation. Equal signal sets yield equal ids/order. Typed candidate distributions and source
 * asset lineage are part of replay identity, so materially different observations cannot collapse.
 */
class PerceptionFusionEngine {
    fun fuse(signals: Collection<PerceptionSignal>): PerceptionBatch {
        val canonical = signals
            .distinctBy { it.fingerprint }
            .sortedWith(compareBy<PerceptionSignal> { it.observedAt }.thenBy { it.fingerprint })

        val photons = canonical.mapIndexed { ordinal, signal ->
            val parentIds = signal.sourceAssetPhotonId?.let(::setOf).orEmpty()
            val relations = signal.sourceAssetPhotonId?.let { assetId ->
                setOf(PhotonRelation(assetId, RelationType.REFERENCES))
            }.orEmpty()
            Photon(
                id = PhotonId(
                    "perception-" + StableCognitiveIds.fingerprint(
                        signal.fingerprint,
                        ordinal.toString(),
                    )
                ),
                content = signal.payload,
                mimeType = signal.mimeType,
                semanticMass = signal.salience,
                energy = signal.salience,
                confidence = signal.confidence,
                provenance = Provenance(
                    source = "perception:${signal.source.name.lowercase()}",
                    actor = signal.sourceId,
                    createdAt = signal.observedAt,
                    parentIds = parentIds,
                ),
                relations = relations,
                tags = buildSet {
                    addAll(signal.tags)
                    add("perception")
                    add("perception-source:${signal.source.name.lowercase()}")
                    add("perception-fingerprint:${signal.fingerprint}")
                    signal.modality?.let { add("perception-modality:${it.name.lowercase()}") }
                    signal.candidateDistributionFingerprint?.let { add("candidate-distribution:$it") }
                    if (signal.observedUntil != signal.observedAt) {
                        add("observation-until:${signal.observedUntil}")
                    }
                    signal.sourceAssetPhotonId?.let { add("source-asset:${it.value}") }
                },
            )
        }
        return PerceptionBatch(
            photons = photons,
            batchFingerprint = StableCognitiveIds.fingerprint(
                "perception-batch/v1",
                *photons.map { it.id.value }.toTypedArray(),
            ),
        )
    }
}

// ---- B451 Information Observation Kernel ----

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

// ---- B453 App Sensor Registry ----

@JvmInline
value class SensorId(val value: String) {
    init {
        require(value.isNotBlank()) { "Sensor id must not be blank" }
    }

    override fun toString(): String = value
}

enum class SensorClass {
    NOTIFICATION,
    APP_USAGE,
    APP_CONTENT,
    FILE,
    CALENDAR,
    CONTACT,
    BROWSER,
    DEVICE,
    EXTERNAL,
}

enum class SensorAttentionMode {
    EVENT_DRIVEN,
    PERIODIC,
    FOCUSED,
    SUSPENDED,
}

enum class SensorHealthState {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    QUARANTINED,
    DISABLED,
}

data class SensorDescriptor(
    val sensorId: SensorId,
    val sensorClass: SensorClass,
    val adapterVersion: String,
    val observationType: OwnerObservationType,
    val resourcePrefix: String,
    val supportedSurfaces: Set<ObservationSurfaceKind>,
    val defaultMode: SensorAttentionMode = SensorAttentionMode.EVENT_DRIVEN,
) {
    init {
        require(adapterVersion.isNotBlank())
        require(resourcePrefix.isNotBlank())
        require(supportedSurfaces.isNotEmpty())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "sensor-descriptor/v1",
        sensorId.value,
        sensorClass.name,
        adapterVersion,
        observationType.name,
        resourcePrefix,
        defaultMode.name,
        *supportedSurfaces.map { it.name }.sorted().toTypedArray(),
    )
}

data class SensorCheckpoint(
    val sensorId: SensorId,
    val revision: Long,
    val sourcePosition: String? = null,
    val lastObservationId: InformationObservationId? = null,
    val lastObservationFingerprint: String? = null,
    val committedAt: Instant,
) {
    init {
        require(revision >= 0L)
        require(sourcePosition == null || sourcePosition.isNotBlank())
        require(
            lastObservationFingerprint == null ||
                lastObservationFingerprint.matches(Regex("[0-9a-f]{64}"))
        )
        require(
            (lastObservationId == null) == (lastObservationFingerprint == null)
        ) {
            "Sensor checkpoint observation id/fingerprint must be present together"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "sensor-checkpoint/v1",
        sensorId.value,
        revision.toString(),
        sourcePosition.orEmpty(),
        lastObservationId?.value.orEmpty(),
        lastObservationFingerprint.orEmpty(),
        committedAt.toString(),
    )
}

data class SensorRuntimeState(
    val descriptor: SensorDescriptor,
    val mode: SensorAttentionMode = descriptor.defaultMode,
    val health: SensorHealthState = SensorHealthState.HEALTHY,
    val checkpoint: SensorCheckpoint? = null,
    val lastFailure: String? = null,
) {
    init {
        require(checkpoint == null || checkpoint.sensorId == descriptor.sensorId)
        require(lastFailure == null || lastFailure.isNotBlank())
        if (health == SensorHealthState.HEALTHY) {
            require(lastFailure == null)
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "sensor-runtime-state/v1",
        descriptor.fingerprint,
        mode.name,
        health.name,
        checkpoint?.fingerprint.orEmpty(),
        lastFailure.orEmpty(),
    )
}

data class AppSensorRegistrySnapshot private constructor(
    val id: String,
    val sensors: List<SensorRuntimeState>,
) {
    init {
        require(sensors.map { it.descriptor.sensorId }.distinct().size == sensors.size)
        require(sensors == sensors.sortedBy { it.descriptor.sensorId.value })
        require(id == "sensor-registry:${fingerprint()}")
    }

    fun fingerprint(): String = StableCognitiveIds.fingerprint(
        "app-sensor-registry/v1",
        *sensors.map { it.fingerprint }.toTypedArray(),
    )

    companion object {
        fun create(
            sensors: Collection<SensorRuntimeState>,
        ): AppSensorRegistrySnapshot {
            val canonical = sensors.sortedBy { it.descriptor.sensorId.value }
            val fingerprint = StableCognitiveIds.fingerprint(
                "app-sensor-registry/v1",
                *canonical.map { it.fingerprint }.toTypedArray(),
            )
            return AppSensorRegistrySnapshot(
                id = "sensor-registry:$fingerprint",
                sensors = canonical,
            )
        }
    }
}

/**
 * B453 process registry for sensor availability, attention mode and replay cursor metadata.
 *
 * The registry is not a source-of-truth store for observations. Durable information remains in the
 * canonical Photon/evidence path and durable policy ledger.
 */
class AppSensorRegistry(
    initial: Collection<SensorRuntimeState> = emptyList(),
) {
    private val mutex = Mutex()
    private val states = initial.associateByTo(linkedMapOf()) {
        it.descriptor.sensorId
    }

    suspend fun register(
        descriptor: SensorDescriptor,
    ): SensorRuntimeState = mutex.withLock {
        val existing = states[descriptor.sensorId]
        if (existing != null && existing.descriptor.fingerprint == descriptor.fingerprint) {
            return@withLock existing
        }
        SensorRuntimeState(descriptor = descriptor).also {
            states[descriptor.sensorId] = it
        }
    }

    suspend fun updateMode(
        sensorId: SensorId,
        mode: SensorAttentionMode,
    ): SensorRuntimeState = mutex.withLock {
        val current = requireNotNull(states[sensorId]) {
            "Unknown sensor $sensorId"
        }
        current.copy(mode = mode).also { states[sensorId] = it }
    }

    suspend fun updateHealth(
        sensorId: SensorId,
        health: SensorHealthState,
        failure: String? = null,
    ): SensorRuntimeState = mutex.withLock {
        require(
            health != SensorHealthState.HEALTHY || failure == null
        ) {
            "Healthy sensor cannot retain a failure"
        }
        val current = requireNotNull(states[sensorId]) {
            "Unknown sensor $sensorId"
        }
        current.copy(
            health = health,
            lastFailure = if (health == SensorHealthState.HEALTHY) null else failure,
        ).also { states[sensorId] = it }
    }

    suspend fun checkpoint(
        checkpoint: SensorCheckpoint,
    ): SensorRuntimeState = mutex.withLock {
        val current = requireNotNull(states[checkpoint.sensorId]) {
            "Unknown sensor ${checkpoint.sensorId}"
        }
        require(
            current.checkpoint == null ||
                checkpoint.revision >= current.checkpoint.revision
        ) {
            "Sensor checkpoint revision must not move backwards"
        }
        current.copy(checkpoint = checkpoint).also {
            states[checkpoint.sensorId] = it
        }
    }

    suspend fun state(
        sensorId: SensorId,
    ): SensorRuntimeState? = mutex.withLock {
        states[sensorId]
    }

    suspend fun snapshot(): AppSensorRegistrySnapshot = mutex.withLock {
        AppSensorRegistrySnapshot.create(states.values)
    }
}

// ---- B454 Projection Classification ----

/**
 * B454 explicit structural claims supplied by one adapter contract for a single observation.
 *
 * These claims are not authority on their own. The classifier combines them with the concrete
 * observation surface and source authority and defaults conservatively to PROJECTED.
 */
data class ProjectionClassificationContext(
    val directStateRead: Boolean = false,
    val sourceStateClosedForContract: Boolean = false,
    val historicalRecord: Boolean = false,
    val distributionValued: Boolean = false,
    val externallyForced: Boolean = false,
    val schedulerMediated: Boolean = false,
    val inferred: Boolean = false,
    val ownerConfirmed: Boolean = false,
    val predicted: Boolean = false,
) {
    init {
        require(!(ownerConfirmed && inferred)) {
            "Owner confirmation and inference are distinct epistemic states"
        }
        require(!(predicted && historicalRecord)) {
            "One classified observation cannot be both predicted and historical"
        }
    }
}

data class ProjectionClassificationResult(
    val observation: InformationObservation,
    val reasons: List<String>,
) {
    init {
        require(reasons.isNotEmpty())
    }

    val descriptor: RealizationDescriptor
        get() = observation.realization
}

/**
 * Conservative classifier for the WELTFORMEL realization dimensions.
 *
 * A notification, usage event, visual UI observation or browser surface can never be promoted to
 * ACTUAL solely because the adapter reports a direct read. ACTUAL requires a source surface capable
 * of state authority, sufficient source authority and an explicit closed-state contract claim.
 */
class ProjectionClassificationEngine {
    fun classify(
        observation: InformationObservation,
        context: ProjectionClassificationContext = ProjectionClassificationContext(),
    ): ProjectionClassificationResult {
        val reasons = mutableListOf<String>()

        val representation = when {
            context.distributionValued -> {
                reasons += "distribution-valued"
                RepresentationLevel.DISTRIBUTION
            }
            context.externallyForced -> {
                reasons += "externally-forced"
                RepresentationLevel.FORCED
            }
            actualAllowed(observation, context) -> {
                reasons += "authoritative-closed-direct-state"
                RepresentationLevel.ACTUAL
            }
            else -> {
                reasons += when (observation.surface) {
                    ObservationSurfaceKind.NOTIFICATION -> "notification-is-projection"
                    ObservationSurfaceKind.APP_USAGE -> "usage-is-projection"
                    ObservationSurfaceKind.APP_UI -> "ui-is-projection"
                    ObservationSurfaceKind.WEB -> "web-surface-is-projection"
                    else -> "state-closure-not-established"
                }
                RepresentationLevel.PROJECTED
            }
        }

        val epistemicStatus = when {
            context.ownerConfirmed -> {
                reasons += "owner-confirmed"
                EpistemicStatus.OWNER_CONFIRMED
            }
            context.inferred -> {
                reasons += "adapter-inference"
                EpistemicStatus.INFERRED
            }
            else -> EpistemicStatus.OBSERVED
        }

        val temporalStatus = when {
            context.predicted -> {
                reasons += "predicted"
                TemporalStatus.PREDICTED
            }
            context.historicalRecord -> {
                reasons += "historical-record"
                TemporalStatus.HISTORY
            }
            else -> TemporalStatus.CURRENT
        }

        val controlStatus = if (context.schedulerMediated) {
            reasons += "scheduler-mediated"
            ControlStatus.SCHEDULED
        } else {
            observation.realization.controlStatus
        }

        return ProjectionClassificationResult(
            observation = observation.copy(
                realization = RealizationDescriptor(
                    representation = representation,
                    epistemicStatus = epistemicStatus,
                    temporalStatus = temporalStatus,
                    controlStatus = controlStatus,
                )
            ),
            reasons = reasons.distinct(),
        )
    }

    private fun actualAllowed(
        observation: InformationObservation,
        context: ProjectionClassificationContext,
    ): Boolean {
        if (!context.directStateRead || !context.sourceStateClosedForContract) return false
        if (observation.authority.rank < ObservationAuthorityClass.PLATFORM_PROVIDER.rank) return false
        return when (observation.surface) {
            ObservationSurfaceKind.CONTENT_PROVIDER,
            ObservationSurfaceKind.FILE,
            ObservationSurfaceKind.API,
            ObservationSurfaceKind.OWNER_INPUT,
            -> true

            ObservationSurfaceKind.NOTIFICATION,
            ObservationSurfaceKind.SENSOR,
            ObservationSurfaceKind.WEB,
            ObservationSurfaceKind.APP_UI,
            ObservationSurfaceKind.APP_USAGE,
            -> false
        }
    }
}

// ---- B460 Universal App Observation Ingress ----

data class AppSensorCursor(
    val sensorId: SensorId,
    val revision: Long,
    val sourcePosition: String? = null,
) {
    init {
        require(revision >= 0L)
        require(sourcePosition == null || sourcePosition.isNotBlank())
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "app-sensor-cursor/v1",
        sensorId.value,
        revision.toString(),
        sourcePosition.orEmpty(),
    )
}

data class AppSensorBudget(
    val maxObservations: Int = 128,
    val maxPayloadChars: Int = 128 * 1024,
) {
    init {
        require(maxObservations in 1..4096)
        require(maxPayloadChars in 1..4 * 1024 * 1024)
    }
}

data class AppObservationBatch private constructor(
    val sensorId: SensorId,
    val observations: List<InformationObservation>,
    val nextCursor: AppSensorCursor,
    val exhausted: Boolean,
) {
    init {
        require(nextCursor.sensorId == sensorId)
        require(observations.map { it.id }.distinct().size == observations.size)
        require(
            observations == observations.sortedWith(
                compareBy<InformationObservation> { it.observedAt }
                    .thenBy { it.id.value }
            )
        ) {
            "App observation batches must use deterministic ordering"
        }
    }

    val fingerprint: String = StableCognitiveIds.fingerprint(
        "app-observation-batch/v1",
        sensorId.value,
        nextCursor.fingerprint,
        exhausted.toString(),
        *observations.map { it.provenanceFingerprint }.toTypedArray(),
    )

    companion object {
        fun create(
            sensorId: SensorId,
            observations: Collection<InformationObservation>,
            nextCursor: AppSensorCursor,
            exhausted: Boolean,
        ): AppObservationBatch {
            require(nextCursor.sensorId == sensorId)
            val grouped = observations.groupBy { it.id }
            grouped.forEach { (id, copies) ->
                require(copies.distinct().size == 1) {
                    "Conflicting replay copies for observation $id"
                }
            }
            val canonical = grouped.values
                .map { it.first() }
                .sortedWith(
                    compareBy<InformationObservation> { it.observedAt }
                        .thenBy { it.id.value }
                )
            return AppObservationBatch(
                sensorId = sensorId,
                observations = canonical,
                nextCursor = nextCursor,
                exhausted = exhausted,
            )
        }
    }
}

interface AppSensorAdapter {
    val descriptor: SensorDescriptor

    suspend fun availability(): SensorHealthState

    suspend fun observe(
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
    ): AppObservationBatch
}

/**
 * B460 validates a sensor batch before Owner Observation Policy and canonical Photon persistence.
 *
 * Adapters can describe observations, but cannot mint owner grants, choose another sensor identity or
 * emit resources/surfaces outside their registered descriptor.
 */
object AppObservationIngress {
    fun validate(
        descriptor: SensorDescriptor,
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
        batch: AppObservationBatch,
    ): AppObservationBatch {
        require(cursor.sensorId == descriptor.sensorId)
        require(batch.sensorId == descriptor.sensorId)
        require(batch.nextCursor.revision >= cursor.revision) {
            "App sensor cursor revision must not move backwards"
        }
        require(batch.observations.size <= budget.maxObservations) {
            "App sensor batch exceeds observation budget"
        }
        require(batch.observations.sumOf { it.payload.length.toLong() } <= budget.maxPayloadChars.toLong()) {
            "App sensor batch exceeds payload budget"
        }
        batch.observations.forEach { observation ->
            require(observation.sourceId == descriptor.sensorId.value) {
                "Observation source id must be the registered sensor id"
            }
            require(observation.sourceResource.startsWith(descriptor.resourcePrefix)) {
                "Observation resource is outside the sensor descriptor"
            }
            require(observation.surface in descriptor.supportedSurfaces) {
                "Observation surface is outside the sensor descriptor"
            }
            require(observation.observationGrantId == null) {
                "Sensor adapter cannot self-authorize Owner Observation Policy"
            }
        }
        return batch
    }
}

// ---- B461 App Usage Context ----

enum class AppUsageEventType {
    FOREGROUND_ENTER,
    FOREGROUND_EXIT,
    FOREGROUND_INTERVAL,
}

data class AppUsageEvent(
    val packageName: String,
    val foregroundSince: Instant,
    val backgroundAt: Instant?,
    val observedAt: Instant,
    val eventType: AppUsageEventType,
    val sourceRevision: String,
) {
    init {
        require(packageName.isNotBlank())
        require(sourceRevision.isNotBlank())
        require(!observedAt.isBefore(foregroundSince))
        when (eventType) {
            AppUsageEventType.FOREGROUND_ENTER ->
                require(backgroundAt == null) {
                    "Foreground-enter usage event must remain open"
                }
            AppUsageEventType.FOREGROUND_EXIT,
            AppUsageEventType.FOREGROUND_INTERVAL,
            -> require(backgroundAt != null && !backgroundAt.isBefore(foregroundSince)) {
                "Closed usage event requires a non-decreasing background time"
            }
        }
        backgroundAt?.let {
            require(!observedAt.isBefore(it)) {
                "Usage observation cannot predate the closed foreground interval"
            }
        }
    }

    val durationMillis: Long?
        get() = backgroundAt?.let {
            java.time.Duration.between(foregroundSince, it).toMillis()
        }
}

/**
 * B461 emits behavior/context evidence only. App usage never exposes screen contents and never
 * becomes owner intent or a domain fact by itself.
 */
class AppUsageObservationFactory(
    private val sensorId: SensorId,
) {
    fun create(event: AppUsageEvent): InformationObservation =
        InformationObservation(
            sourceId = sensorId.value,
            sourceResource = "android-usage:${event.packageName}",
            surface = ObservationSurfaceKind.APP_USAGE,
            observedAt = event.observedAt,
            sourceTimestamp = event.backgroundAt ?: event.foregroundSince,
            sourceRevision = event.sourceRevision,
            mimeType = "application/vnd.lifeos.app-usage+text",
            payload = buildString {
                appendLine("package=${event.packageName}")
                appendLine("event=${event.eventType.name}")
                appendLine("foreground_since=${event.foregroundSince}")
                appendLine("background_at=${event.backgroundAt.orEmptyString()}")
                append("duration_ms=${event.durationMillis?.toString().orEmpty()}")
            },
            realization = RealizationDescriptor(
                representation = RepresentationLevel.PROJECTED,
                epistemicStatus = EpistemicStatus.OBSERVED,
                temporalStatus = if (event.backgroundAt == null) {
                    TemporalStatus.CURRENT
                } else {
                    TemporalStatus.HISTORY
                },
                controlStatus = ControlStatus.PASSIVE,
            ),
            authority = ObservationAuthorityClass.PLATFORM_PROVIDER,
            privacy = ObservationPrivacyClass.PERSONAL,
            confidence = 1.0,
            tags = setOf(
                "app-usage",
                "behavior-context",
                "app:${event.packageName}",
            ),
            metadata = buildMap {
                put("package", event.packageName)
                put("eventType", event.eventType.name)
                event.durationMillis?.let { put("durationMillis", it.toString()) }
            },
        )

    private fun Instant?.orEmptyString(): String = this?.toString().orEmpty()
}



// ---- B466 Owner-Authorized Sensor Ingress ----

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
