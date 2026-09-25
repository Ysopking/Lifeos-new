package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.AuthorizedObservationPhotonCommitReceipt
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.world.SensorAttentionCoverageProfile
import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.SensorStateDimensionSelectorType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal typealias ProductiveSensorBatchIngress = suspend (
    SensorDescriptor,
    AppSensorCursor,
    AppSensorBudget,
    AppObservationBatch,
    String,
    Double,
) -> AuthorizedObservationPhotonCommitReceipt

/**
 * B482 registered event-driven Android notification sensor.
 *
 * NotificationListenerService remains only a platform producer. This bridge owns the process cursor,
 * B459 attention gate and the B467 batch hand-off. It cannot mint Owner Observation Policy grants.
 */
internal class AndroidNotificationSensorBridge(
    private val ingestSensorBatch: ProductiveSensorBatchIngress,
) : ProductiveSensorAttentionTarget {
    override val descriptor = SensorDescriptor(
        sensorId = SensorId(PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SENSOR_ID),
        sensorClass = SensorClass.NOTIFICATION,
        adapterVersion = ADAPTER_VERSION,
        observationType = OwnerObservationType.NOTIFICATION,
        resourcePrefix = PrivateOwnerObservationPolicyBaseline.NOTIFICATION_RESOURCE_PREFIX,
        supportedSurfaces = setOf(ObservationSurfaceKind.NOTIFICATION),
        defaultMode = SensorAttentionMode.EVENT_DRIVEN,
    )

    override val attentionCoverage = SensorAttentionCoverageProfile(
        sensorId = descriptor.sensorId,
        stateDimensions = listOf(
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.communication.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.notification.",
            ),
        ).sortedWith(compareBy({ it.type.name }, { it.value })),
        observationContracts = setOf(
            "app.communication.readback",
            "app.notification.readback",
        ),
        informationGainMicros = 800_000L,
        goalRelevanceMicros = 650_000L,
        verificationValueMicros = 750_000L,
        energyCostMicros = 100_000L,
        privacyCostMicros = 550_000L,
        latencyCostMicros = 50_000L,
        resourceCostMicros = 100_000L,
    )

    private val ingestMutex = Mutex()

    @Volatile
    private var attentionMode: SensorAttentionMode = SensorAttentionMode.EVENT_DRIVEN

    private var cursor = AppSensorCursor(
        sensorId = descriptor.sensorId,
        revision = 0L,
        sourcePosition = null,
    )

    fun install() {
        LiveNotificationPhotonIngress.install(::ingest)
    }

    override fun applyAttention(mode: SensorAttentionMode): Int {
        attentionMode = mode
        return if (mode == SensorAttentionMode.SUSPENDED) 0 else 1
    }

    internal suspend fun ingest(observation: InformationObservation) {
        if (attentionMode == SensorAttentionMode.SUSPENDED) return

        ingestMutex.withLock {
            if (attentionMode == SensorAttentionMode.SUSPENDED) return@withLock

            val current = cursor
            val next = AppSensorCursor(
                sensorId = descriptor.sensorId,
                revision = current.revision + 1L,
                sourcePosition =
                    observation.sourceRevision ?: observation.id.value,
            )
            val batch = AppObservationBatch.create(
                sensorId = descriptor.sensorId,
                observations = listOf(observation),
                nextCursor = next,
                exhausted = true,
            )

            ingestSensorBatch(
                descriptor,
                current,
                SENSOR_BUDGET,
                batch,
                PrivateOwnerObservationPolicyBaseline.NOTIFICATION_SCOPE,
                salience(attentionMode),
            )
            cursor = next
        }
    }

    private fun salience(mode: SensorAttentionMode): Double = when (mode) {
        SensorAttentionMode.FOCUSED -> 0.80
        SensorAttentionMode.PERIODIC -> 0.65
        SensorAttentionMode.EVENT_DRIVEN -> 0.60
        SensorAttentionMode.SUSPENDED -> 0.0
    }

    private companion object {
        const val ADAPTER_VERSION = "1"

        val SENSOR_BUDGET = AppSensorBudget(
            maxObservations = 1,
            maxPayloadChars = 32 * 1024,
        )
    }
}
