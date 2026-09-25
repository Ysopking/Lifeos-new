package app.lifeos.next.kernel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.HardwareSensorObservationFactory
import app.lifeos.core.runtime.life.HardwareSensorSample
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.SensorClass
import app.lifeos.core.runtime.life.SensorDescriptor
import app.lifeos.core.runtime.life.SensorId
import app.lifeos.core.runtime.life.SensorAttentionMode
import app.lifeos.core.runtime.policy.OwnerObservationType
import app.lifeos.core.runtime.world.SensorAttentionCoverageProfile
import app.lifeos.core.runtime.world.SensorStateDimensionSelector
import app.lifeos.core.runtime.world.SensorStateDimensionSelectorType
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * B468 productive Android physical-sensor bridge.
 *
 * This bridge observes only a bounded set of ordinary device/environment sensors. It does not
 * request platform permissions, does not observe BODY_SENSORS/health channels, and cannot authorize
 * itself. Every emitted batch still passes CanonicalPhotonIngress.ingestSensorBatch, which applies
 * Owner Observation Policy before any Photon is persisted.
 */
internal class AndroidHardwareSensorBridge(
    context: Context,
    private val photonIngress: CanonicalPhotonIngress,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SensorEventListener {
    private val sensorManager =
        requireNotNull(
            context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        ) {
            "Android SensorManager unavailable"
        }

    internal val descriptor = SensorDescriptor(
        sensorId = SensorId(SENSOR_ID),
        sensorClass = SensorClass.DEVICE,
        adapterVersion = ADAPTER_VERSION,
        observationType = OwnerObservationType.EXTERNAL_SENSOR,
        resourcePrefix = RESOURCE_PREFIX,
        supportedSurfaces = setOf(ObservationSurfaceKind.SENSOR),
        defaultMode = SensorAttentionMode.EVENT_DRIVEN,
    )
    internal val attentionCoverage = SensorAttentionCoverageProfile(
        sensorId = descriptor.sensorId,
        stateDimensions = listOf(
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "device.motion.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "device.orientation.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "device.proximity.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "device.environment.",
            ),
        ).sortedWith(compareBy({ it.type.name }, { it.value })),
        observationContracts = setOf(
            "device.motion.readback",
            "device.orientation.readback",
            "device.proximity.readback",
            "device.environment.readback",
        ),
        informationGainMicros = 650_000L,
        goalRelevanceMicros = 500_000L,
        verificationValueMicros = 700_000L,
        energyCostMicros = 350_000L,
        privacyCostMicros = 250_000L,
        latencyCostMicros = 150_000L,
        resourceCostMicros = 250_000L,
    )

    private val observationFactory =
        HardwareSensorObservationFactory(descriptor.sensorId)
    private val started = AtomicBoolean(false)

    @Volatile
    private var attentionMode: SensorAttentionMode = SensorAttentionMode.EVENT_DRIVEN

    private val ingestMutex = Mutex()
    private val lastAcceptedEventNanos = ConcurrentHashMap<Int, Long>()
    private var cursor = AppSensorCursor(
        sensorId = descriptor.sensorId,
        revision = 0L,
        sourcePosition = null,
    )

    fun start(): Int = applyAttention(SensorAttentionMode.EVENT_DRIVEN)

    /**
     * Physical continuous sensors are active acquisition and therefore follow B459 attention.
     * EVENT_DRIVEN subscribes only to Android on-change sensors. PERIODIC/FOCUSED may additionally
     * activate continuous sensors; SUSPENDED turns the bridge off.
     */
    fun applyAttention(mode: SensorAttentionMode): Int {
        attentionMode = mode
        sensorManager.unregisterListener(this)
        lastAcceptedEventNanos.clear()

        if (mode == SensorAttentionMode.SUSPENDED) {
            started.set(false)
            return 0
        }

        started.set(true)
        var registered = 0
        supportedSensorTypes.forEach { sensorType ->
            sensorManager.getDefaultSensor(sensorType)?.let { sensor ->
                if (!eligibleForMode(sensor, mode)) return@let
                if (
                    sensorManager.registerListener(
                        this,
                        sensor,
                        samplingPeriodUs(mode),
                        maxReportLatencyUs(mode),
                    )
                ) {
                    registered += 1
                }
            }
        }
        if (registered == 0) started.set(false)
        return registered
    }

    fun stop() {
        attentionMode = SensorAttentionMode.SUSPENDED
        started.set(false)
        sensorManager.unregisterListener(this)
        lastAcceptedEventNanos.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!started.get()) return
        val sampleEvent = event ?: return
        if (sampleEvent.sensor.type !in supportedSensorTypes) return
        if (!acceptByRateLimit(sampleEvent)) return

        val values = sampleEvent.values.map { it.toDouble() }
        if (values.isEmpty() || values.any { !it.isFinite() }) return

        val sample = HardwareSensorSample(
            sensorType = sampleEvent.sensor.type,
            sensorName = sampleEvent.sensor.name.ifBlank {
                sampleEvent.sensor.stringType.ifBlank {
                    "android-sensor-${sampleEvent.sensor.type}"
                }
            },
            sensorVendor = sampleEvent.sensor.vendor?.takeIf { it.isNotBlank() },
            sensorVersion = sampleEvent.sensor.version.takeIf { it >= 0 },
            accuracy = sampleEvent.accuracy,
            eventTimestampNanos = sampleEvent.timestamp.coerceAtLeast(0L),
            observedAt = Instant.now(clock),
            values = values,
        )
        val observation = observationFactory.create(sample)

        scope.launch {
            ingestMutex.withLock {
                val current = cursor
                val next = AppSensorCursor(
                    sensorId = descriptor.sensorId,
                    revision = current.revision + 1L,
                    sourcePosition = sample.sourceRevision,
                )
                val batch = AppObservationBatch.create(
                    sensorId = descriptor.sensorId,
                    observations = listOf(observation),
                    nextCursor = next,
                    exhausted = true,
                )

                photonIngress.ingestSensorBatch(
                    descriptor = descriptor,
                    cursor = current,
                    budget = SENSOR_BUDGET,
                    batch = batch,
                    scope = PrivateOwnerObservationPolicyBaseline.DEVICE_SENSOR_SCOPE,
                    salience = SENSOR_SALIENCE,
                )
                cursor = next
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun acceptByRateLimit(event: SensorEvent): Boolean {
        val minimumIntervalNanos = when (attentionMode) {
            SensorAttentionMode.FOCUSED -> FOCUSED_MIN_EVENT_INTERVAL_NANOS
            SensorAttentionMode.PERIODIC -> PERIODIC_MIN_EVENT_INTERVAL_NANOS
            SensorAttentionMode.EVENT_DRIVEN -> EVENT_MIN_EVENT_INTERVAL_NANOS
            SensorAttentionMode.SUSPENDED -> Long.MAX_VALUE
        }
        val previous = lastAcceptedEventNanos[event.sensor.type]
        if (
            previous != null &&
            event.timestamp >= previous &&
            event.timestamp - previous < minimumIntervalNanos
        ) {
            return false
        }
        lastAcceptedEventNanos[event.sensor.type] = event.timestamp
        return true
    }

    private fun eligibleForMode(
        sensor: Sensor,
        mode: SensorAttentionMode,
    ): Boolean = when (mode) {
        SensorAttentionMode.SUSPENDED -> false
        SensorAttentionMode.EVENT_DRIVEN ->
            sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE
        SensorAttentionMode.PERIODIC,
        SensorAttentionMode.FOCUSED,
        -> sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE ||
            sensor.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS
    }

    private fun samplingPeriodUs(mode: SensorAttentionMode): Int = when (mode) {
        SensorAttentionMode.FOCUSED -> FOCUSED_SAMPLING_PERIOD_US
        SensorAttentionMode.PERIODIC -> PERIODIC_SAMPLING_PERIOD_US
        SensorAttentionMode.EVENT_DRIVEN -> EVENT_SAMPLING_PERIOD_US
        SensorAttentionMode.SUSPENDED -> PERIODIC_SAMPLING_PERIOD_US
    }

    private fun maxReportLatencyUs(mode: SensorAttentionMode): Int = when (mode) {
        SensorAttentionMode.FOCUSED -> FOCUSED_MAX_REPORT_LATENCY_US
        SensorAttentionMode.PERIODIC -> PERIODIC_MAX_REPORT_LATENCY_US
        SensorAttentionMode.EVENT_DRIVEN -> EVENT_MAX_REPORT_LATENCY_US
        SensorAttentionMode.SUSPENDED -> PERIODIC_MAX_REPORT_LATENCY_US
    }

    private companion object {
        const val SENSOR_ID = "android-hardware-sensor-manager"
        const val ADAPTER_VERSION = "1"
        const val RESOURCE_PREFIX = "android-sensor:"
        const val FOCUSED_SAMPLING_PERIOD_US = 1_000_000
        const val FOCUSED_MAX_REPORT_LATENCY_US = 5_000_000
        const val PERIODIC_SAMPLING_PERIOD_US = 5_000_000
        const val PERIODIC_MAX_REPORT_LATENCY_US = 30_000_000
        const val EVENT_SAMPLING_PERIOD_US = 5_000_000
        const val EVENT_MAX_REPORT_LATENCY_US = 30_000_000
        const val FOCUSED_MIN_EVENT_INTERVAL_NANOS = 5_000_000_000L
        const val PERIODIC_MIN_EVENT_INTERVAL_NANOS = 60_000_000_000L
        const val EVENT_MIN_EVENT_INTERVAL_NANOS = 5_000_000_000L
        const val SENSOR_SALIENCE = 0.35

        val SENSOR_BUDGET = AppSensorBudget(
            maxObservations = 1,
            maxPayloadChars = 16 * 1024,
        )

        val supportedSensorTypes = setOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_RELATIVE_HUMIDITY,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
        )
    }
}
