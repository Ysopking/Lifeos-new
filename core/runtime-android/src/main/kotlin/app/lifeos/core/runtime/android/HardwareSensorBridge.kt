package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppObservationIngress
import app.lifeos.core.runtime.life.AppSensorAdapter
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.AppSensorRegistry
import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.OwnerAuthorizedAppObservationIngress
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.SensorCheckpoint
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.life.TemporalStatus
import java.time.Instant

data class HardwareSensorSample(
    val eventId: String,
    val resource: String,
    val observedAt: Instant,
    val sourceRevision: String,
    val values: Map<String, Double>,
    val confidence: Double = 1.0,
) {
    init {
        require(eventId.isNotBlank())
        require(resource.isNotBlank())
        require(sourceRevision.isNotBlank())
        require(values.isNotEmpty())
        require(values.keys.none { it.isBlank() })
        require(values.values.all { it.isFinite() })
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

data class HardwareSensorRead(
    val samples: List<HardwareSensorSample>,
    val nextRevision: Long,
    val sourcePosition: String? = null,
    val exhausted: Boolean,
) {
    init {
        require(nextRevision >= 0L)
        require(sourcePosition == null || sourcePosition.isNotBlank())
        require(samples.map { it.eventId }.distinct().size == samples.size)
    }
}

interface HardwareSensorSource {
    val descriptor: SensorDescriptor
    val authority: ObservationAuthorityClass
    val privacy: ObservationPrivacyClass
    val transportId: String

    suspend fun availability(): SensorHealthState

    suspend fun read(
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
    ): HardwareSensorRead
}

/**
 * B468 transport-neutral physical sensor bridge. A concrete source can represent platform sensors,
 * Bluetooth devices, USB peripherals or other owner-authorized hardware without changing the
 * canonical LIFEOS observation contract.
 */
class HardwareSensorBridgeAdapter(
    private val source: HardwareSensorSource,
) : AppSensorAdapter {
    override val descriptor: SensorDescriptor
        get() = source.descriptor

    override suspend fun availability(): SensorHealthState = source.availability()

    override suspend fun observe(
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
    ): AppObservationBatch {
        require(cursor.sensorId == descriptor.sensorId)
        require(source.transportId.isNotBlank())
        require(descriptor.supportedSurfaces == setOf(ObservationSurfaceKind.SENSOR)) {
            "Hardware bridge requires SENSOR-only descriptor surface"
        }

        val read = source.read(cursor, budget)
        require(read.nextRevision >= cursor.revision)
        require(read.samples.size <= budget.maxObservations)

        val observations = read.samples
            .sortedWith(compareBy<HardwareSensorSample> { it.observedAt }.thenBy { it.eventId })
            .map { sample ->
                require(sample.resource.startsWith(descriptor.resourcePrefix))
                InformationObservation(
                    sourceId = descriptor.sensorId.value,
                    sourceResource = sample.resource,
                    surface = ObservationSurfaceKind.SENSOR,
                    observedAt = sample.observedAt,
                    sourceTimestamp = sample.observedAt,
                    sourceRevision = sample.sourceRevision,
                    mimeType = "application/vnd.lifeos.hardware-sensor+text",
                    payload = canonicalPayload(sample),
                    realization = RealizationDescriptor(
                        representation = RepresentationLevel.PROJECTED,
                        epistemicStatus = EpistemicStatus.OBSERVED,
                        temporalStatus = TemporalStatus.CURRENT,
                        controlStatus = ControlStatus.PASSIVE,
                    ),
                    authority = source.authority,
                    privacy = source.privacy,
                    confidence = sample.confidence,
                    tags = setOf(
                        "hardware-sensor",
                        "hardware-transport:" + source.transportId,
                    ),
                    metadata = mapOf("hardwareEventId" to sample.eventId),
                )
            }

        return AppObservationBatch.create(
            sensorId = descriptor.sensorId,
            observations = observations,
            nextCursor = AppSensorCursor(
                sensorId = descriptor.sensorId,
                revision = read.nextRevision,
                sourcePosition = read.sourcePosition,
            ),
            exhausted = read.exhausted,
        )
    }

    private fun canonicalPayload(sample: HardwareSensorSample): String =
        sample.values.toSortedMap().entries.joinToString("\n") { (key, value) ->
            key + "=" + java.lang.Double.toHexString(value)
        }
}

data class HardwareSensorCycleResult(
    val sensorId: SensorId,
    val observedCount: Int,
    val authorizedCount: Int,
    val blockedCount: Int,
    val committedCount: Int,
    val nextCursor: AppSensorCursor,
    val checkpoint: SensorCheckpoint,
    val effectAuthority: Boolean = false,
) {
    init {
        require(observedCount >= 0)
        require(authorizedCount + blockedCount == observedCount)
        require(committedCount == authorizedCount)
        require(!effectAuthority)
    }
}

/**
 * Validation -> Owner Observation Policy -> productive commit. Product composition supplies the
 * B467 commit callback, so this runtime receives no direct Photon-store or effect authority.
 */
class HardwareSensorObservationRuntime(
    private val adapter: HardwareSensorBridgeAdapter,
    private val registry: AppSensorRegistry,
    private val authorization: OwnerAuthorizedAppObservationIngress,
    private val commitAuthorized: suspend (InformationObservation) -> Unit,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun run(
        cursor: AppSensorCursor,
        budget: AppSensorBudget = AppSensorBudget(),
    ): HardwareSensorCycleResult? {
        val descriptor = adapter.descriptor
        require(cursor.sensorId == descriptor.sensorId)
        registry.register(descriptor)

        val health = adapter.availability()
        if (health !in setOf(SensorHealthState.HEALTHY, SensorHealthState.DEGRADED)) {
            registry.updateHealth(descriptor.sensorId, health)
            return null
        }

        return try {
            val observed = AppObservationIngress.validate(
                descriptor,
                cursor,
                budget,
                adapter.observe(cursor, budget),
            )
            val authorized = authorization.authorize(descriptor, observed)
            authorized.authorized.forEach { commitAuthorized(it) }

            val last = authorized.authorized.lastOrNull()
            val checkpoint = SensorCheckpoint(
                sensorId = descriptor.sensorId,
                revision = observed.nextCursor.revision,
                sourcePosition = observed.nextCursor.sourcePosition,
                lastObservationId = last?.id,
                lastObservationFingerprint = last?.provenanceFingerprint,
                committedAt = now(),
            )
            registry.checkpoint(checkpoint)
            registry.updateHealth(descriptor.sensorId, health)

            HardwareSensorCycleResult(
                sensorId = descriptor.sensorId,
                observedCount = observed.observations.size,
                authorizedCount = authorized.authorized.size,
                blockedCount = authorized.blocked.size,
                committedCount = authorized.authorized.size,
                nextCursor = observed.nextCursor,
                checkpoint = checkpoint,
            )
        } catch (error: Exception) {
            registry.updateHealth(
                descriptor.sensorId,
                SensorHealthState.DEGRADED,
                error.message ?: "hardware-sensor-cycle-failed",
            )
            throw error
        }
    }
}
