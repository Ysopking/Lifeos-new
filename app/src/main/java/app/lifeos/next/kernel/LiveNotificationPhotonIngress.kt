package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorHealthState
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.world.SensorAttentionCoverageProfile
import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.SensorStateDimensionSelectorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface LiveNotificationSensorHandler {
    suspend fun connected()
    suspend fun disconnected()
    suspend fun ingest(observation: InformationObservation)
}

internal fun interface LiveNotificationBatchCommitter {
    suspend fun commit(
        descriptor: SensorDescriptor,
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
        batch: AppObservationBatch,
    )
}

/**
 * B483 notification adapter for the common B460/B467 sensor path.
 *
 * NotificationListenerService remains the platform event source. This bridge owns only bounded
 * cursor/attention/lifecycle state and cannot authorize observations. Productive commits still pass
 * through CanonicalPhotonIngress.ingestSensorBatch -> Owner Observation Policy -> ORIGIN Photon.
 */
internal class LiveNotificationSensorBridge(
    private val commitBatch: LiveNotificationBatchCommitter,
) : LiveNotificationSensorHandler {
    constructor(photonIngress: CanonicalPhotonIngress) : this(
        LiveNotificationBatchCommitter { descriptor, cursor, budget, batch ->
            photonIngress.ingestSensorBatch(
                descriptor = descriptor,
                cursor = cursor,
                budget = budget,
                batch = batch,
                scope = PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SCOPE,
                salience = NOTIFICATION_SALIENCE,
            )
            Unit
        }
    )

    internal val descriptor = SensorDescriptor(
        sensorId = SensorId(PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SENSOR_ID),
        sensorClass = SensorClass.NOTIFICATION,
        adapterVersion = ADAPTER_VERSION,
        observationType = OwnerObservationType.NOTIFICATION,
        resourcePrefix = PrivateOwnerObservationPolicyBaseline.NOTIFICATION_RESOURCE_PREFIX,
        supportedSurfaces = setOf(ObservationSurfaceKind.NOTIFICATION),
        defaultMode = SensorAttentionMode.EVENT_DRIVEN,
    )

    /**
     * Coverage is deliberately limited to what the notification surface itself can observe.
     * It does not claim authoritative state inside the source application.
     */
    internal val attentionCoverage = SensorAttentionCoverageProfile(
        sensorId = descriptor.sensorId,
        stateDimensions = listOf(
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.notification.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "communication.notification.",
            ),
        ).sortedWith(compareBy({ it.type.name }, { it.value })),
        observationContracts = setOf(
            "app.notification.readback",
            "communication.notification.readback",
        ),
        informationGainMicros = 750_000L,
        goalRelevanceMicros = 650_000L,
        verificationValueMicros = 650_000L,
        energyCostMicros = 100_000L,
        privacyCostMicros = 450_000L,
        latencyCostMicros = 50_000L,
        resourceCostMicros = 100_000L,
    )

    private val mutex = Mutex()

    @Volatile
    private var attentionMode: SensorAttentionMode = descriptor.defaultMode

    @Volatile
    private var healthReporter:
        (suspend (SensorHealthState, String?) -> Unit)? = null

    private var cursor = AppSensorCursor(
        sensorId = descriptor.sensorId,
        revision = 0L,
        sourcePosition = null,
    )

    internal fun bindHealthReporter(
        reporter: suspend (SensorHealthState, String?) -> Unit,
    ) {
        require(healthReporter == null) {
            "Notification sensor health reporter is already bound"
        }
        healthReporter = reporter
    }

    internal fun applyAttention(mode: SensorAttentionMode) {
        attentionMode = mode
    }

    override suspend fun connected() {
        requireNotNull(healthReporter) {
            "Notification sensor must be attached before listener connection"
        }.invoke(SensorHealthState.HEALTHY, null)
    }

    override suspend fun disconnected() {
        requireNotNull(healthReporter) {
            "Notification sensor must be attached before listener disconnection"
        }.invoke(
            SensorHealthState.UNAVAILABLE,
            "notification-listener-disconnected",
        )
    }

    override suspend fun ingest(observation: InformationObservation) {
        if (attentionMode == SensorAttentionMode.SUSPENDED) return

        mutex.withLock {
            if (attentionMode == SensorAttentionMode.SUSPENDED) return@withLock

            val current = cursor
            val next = AppSensorCursor(
                sensorId = descriptor.sensorId,
                revision = current.revision + 1L,
                sourcePosition = observation.sourceRevision,
            )
            val batch = AppObservationBatch.create(
                sensorId = descriptor.sensorId,
                observations = listOf(observation),
                nextCursor = next,
                exhausted = true,
            )
            commitBatch.commit(
                descriptor = descriptor,
                cursor = current,
                budget = NOTIFICATION_BUDGET,
                batch = batch,
            )
            cursor = next
        }
    }

    private companion object {
        const val ADAPTER_VERSION = "2"
        const val NOTIFICATION_SALIENCE = 0.6

        val NOTIFICATION_BUDGET = AppSensorBudget(
            maxObservations = 1,
            maxPayloadChars = 32 * 1024,
        )
    }
}

/**
 * Process-local lifecycle bridge from Android's NotificationListenerService to the registered
 * notification sensor. Calls wait for productive composition instead of bypassing policy while the
 * process is still starting.
 */
object LiveNotificationPhotonIngress {
    private val handler =
        MutableStateFlow<LiveNotificationSensorHandler?>(null)

    internal fun install(value: LiveNotificationSensorHandler) {
        handler.value = value
    }

    suspend fun connected() {
        handler.filterNotNull().first().connected()
    }

    suspend fun disconnected() {
        handler.filterNotNull().first().disconnected()
    }

    suspend fun ingest(observation: InformationObservation) {
        handler.filterNotNull().first().ingest(observation)
    }

    internal fun clearForTests() {
        handler.value = null
    }
}
