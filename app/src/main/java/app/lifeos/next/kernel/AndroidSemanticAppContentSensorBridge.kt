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

internal interface SemanticAppContentSensorHandler {
    suspend fun connected()
    suspend fun disconnected()
    suspend fun ingest(observation: InformationObservation)
}

internal fun interface AppContentBatchCommitter {
    suspend fun commit(
        descriptor: SensorDescriptor,
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
        batch: AppObservationBatch,
    )
}

/**
 * B485 productive semantic app-content sensor.
 *
 * Accessibility supplies a structured PROJECTED observation only. The bridge cannot attach an
 * observation grant and cannot create effect authority. Every accepted batch still passes through
 * CanonicalPhotonIngress.ingestSensorBatch -> Owner Observation Policy -> canonical ORIGIN Photon.
 */
internal class AndroidSemanticAppContentSensorBridge(
    private val commitBatch: AppContentBatchCommitter,
) : SemanticAppContentSensorHandler {
    constructor(photonIngress: CanonicalPhotonIngress) : this(
        AppContentBatchCommitter { descriptor, cursor, budget, batch ->
            photonIngress.ingestSensorBatch(
                descriptor = descriptor,
                cursor = cursor,
                budget = budget,
                batch = batch,
                scope = PrivateOwnerObservationPolicyBaseline.APP_CONTENT_SCOPE,
                salience = APP_CONTENT_SALIENCE,
            )
            Unit
        }
    )

    internal val descriptor = SensorDescriptor(
        sensorId = SensorId(PrivateOwnerObservationPolicyBaseline.APP_CONTENT_SENSOR_ID),
        sensorClass = SensorClass.APP_CONTENT,
        adapterVersion = ADAPTER_VERSION,
        observationType = OwnerObservationType.APP_CONTENT,
        resourcePrefix = PrivateOwnerObservationPolicyBaseline.APP_CONTENT_RESOURCE_PREFIX,
        supportedSurfaces = setOf(ObservationSurfaceKind.APP_UI),
        defaultMode = SensorAttentionMode.SUSPENDED,
    )

    internal val attentionCoverage = SensorAttentionCoverageProfile(
        sensorId = descriptor.sensorId,
        stateDimensions = listOf(
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.content.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.ui.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "communication.ui.",
            ),
        ).sortedWith(compareBy({ it.type.name }, { it.value })),
        observationContracts = setOf(
            "app.content.semantic.readback",
            "app.ui.semantic.readback",
        ),
        informationGainMicros = 850_000L,
        goalRelevanceMicros = 750_000L,
        verificationValueMicros = 850_000L,
        energyCostMicros = 300_000L,
        privacyCostMicros = 800_000L,
        latencyCostMicros = 100_000L,
        resourceCostMicros = 300_000L,
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
            "Semantic app-content sensor health reporter is already bound"
        }
        healthReporter = reporter
    }

    internal fun applyAttention(mode: SensorAttentionMode) {
        attentionMode = mode
    }

    internal fun isActive(): Boolean =
        attentionMode != SensorAttentionMode.SUSPENDED

    override suspend fun connected() {
        requireNotNull(healthReporter) {
            "Semantic app-content sensor must be attached before Accessibility connection"
        }.invoke(SensorHealthState.HEALTHY, null)
    }

    override suspend fun disconnected() {
        requireNotNull(healthReporter) {
            "Semantic app-content sensor must be attached before Accessibility disconnection"
        }.invoke(
            SensorHealthState.UNAVAILABLE,
            "accessibility-service-disconnected",
        )
    }

    override suspend fun ingest(observation: InformationObservation) {
        if (!isActive()) return

        mutex.withLock {
            if (!isActive()) return@withLock

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
                budget = APP_CONTENT_BUDGET,
                batch = batch,
            )
            cursor = next
        }
    }

    private companion object {
        const val ADAPTER_VERSION = "1"
        const val APP_CONTENT_SALIENCE = 0.65

        val APP_CONTENT_BUDGET = AppSensorBudget(
            maxObservations = 1,
            maxPayloadChars = 64 * 1024,
        )
    }
}

/**
 * Process-local bridge from Android AccessibilityService to the B485 sensor. The service can query
 * [active] before traversing a UI tree, avoiding collection while the canonical attention mode is
 * SUSPENDED.
 */
object ProductiveSemanticAppContentIngress {
    private val handler =
        MutableStateFlow<AndroidSemanticAppContentSensorBridge?>(null)

    internal fun install(value: AndroidSemanticAppContentSensorBridge) {
        handler.value = value
    }

    fun active(): Boolean = handler.value?.isActive() == true

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
