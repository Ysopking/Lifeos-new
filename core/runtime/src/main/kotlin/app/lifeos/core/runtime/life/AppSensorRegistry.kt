package app.lifeos.core.runtime.life

import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.policy.OwnerObservationType
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
