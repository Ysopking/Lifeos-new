package app.lifeos.next.kernel

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import app.lifeos.core.runtime.life.AppObservationBatch
import app.lifeos.core.runtime.life.AppSensorBudget
import app.lifeos.core.runtime.life.AppSensorCursor
import app.lifeos.core.runtime.life.AppUsageEvent
import app.lifeos.core.runtime.life.AppUsageEventType
import app.lifeos.core.runtime.life.AppUsageObservationFactory
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
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class PlatformAppUsageEventKind {
    FOREGROUND,
    BACKGROUND,
}

internal data class PlatformAppUsageEvent(
    val packageName: String,
    val timestampMillis: Long,
    val kind: PlatformAppUsageEventKind,
) {
    init {
        require(packageName.isNotBlank())
        require(timestampMillis >= 0L)
    }
}

internal interface AppUsageEventSource {
    fun isAccessGranted(): Boolean

    fun queryEvents(
        beginMillis: Long,
        endMillis: Long,
        maxEvents: Int,
    ): List<PlatformAppUsageEvent>
}

internal class AndroidUsageStatsEventSource(
    context: Context,
) : AppUsageEventSource {
    private val appContext = context.applicationContext
    private val usageStatsManager =
        requireNotNull(
            appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ) {
            "Android UsageStatsManager unavailable"
        }
    private val appOpsManager =
        requireNotNull(appContext.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager) {
            "Android AppOpsManager unavailable"
        }

    override fun isAccessGranted(): Boolean =
        try {
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: RuntimeException) {
            false
        }

    override fun queryEvents(
        beginMillis: Long,
        endMillis: Long,
        maxEvents: Int,
    ): List<PlatformAppUsageEvent> {
        require(beginMillis >= 0L)
        require(endMillis >= beginMillis)
        require(maxEvents > 0)

        val usageEvents = usageStatsManager.queryEvents(beginMillis, endMillis)
            ?: return emptyList()
        val event = UsageEvents.Event()
        val result = ArrayList<PlatformAppUsageEvent>(maxEvents)

        while (usageEvents.hasNextEvent() && result.size < maxEvents) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName?.takeIf { it.isNotBlank() } ?: continue
            val kind = when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> PlatformAppUsageEventKind.FOREGROUND
                UsageEvents.Event.MOVE_TO_BACKGROUND -> PlatformAppUsageEventKind.BACKGROUND
                else -> continue
            }
            if (event.timeStamp !in beginMillis..endMillis) continue
            result += PlatformAppUsageEvent(
                packageName = packageName,
                timestampMillis = event.timeStamp,
                kind = kind,
            )
        }

        return result.sortedWith(
            compareBy<PlatformAppUsageEvent> { it.timestampMillis }
                .thenBy { it.packageName }
                .thenBy { it.kind.name }
        )
    }
}

internal fun interface AppUsageBatchCommitter {
    suspend fun commit(
        descriptor: SensorDescriptor,
        cursor: AppSensorCursor,
        budget: AppSensorBudget,
        batch: AppObservationBatch,
    )
}

/**
 * B484 productive Android app-usage sensor.
 *
 * UsageStats is behavioral/context observation only: package foreground timing does not expose
 * screen content, does not establish owner intent and cannot authorize effects. Every productive
 * batch still flows through B467 Owner Observation Policy -> canonical ORIGIN Photon persistence.
 */
internal class AndroidAppUsageSensorBridge(
    private val source: AppUsageEventSource,
    private val commitBatch: AppUsageBatchCommitter,
    private val clock: Clock = Clock.systemUTC(),
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(
        context: Context,
        photonIngress: CanonicalPhotonIngress,
        clock: Clock = Clock.systemUTC(),
        scope: CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        source = AndroidUsageStatsEventSource(context),
        commitBatch = AppUsageBatchCommitter { descriptor, cursor, budget, batch ->
            photonIngress.ingestSensorBatch(
                descriptor = descriptor,
                cursor = cursor,
                budget = budget,
                batch = batch,
                scope = PrivateOwnerObservationPolicyBaseline.APP_USAGE_SCOPE,
                salience = APP_USAGE_SALIENCE,
            )
            Unit
        },
        clock = clock,
        scope = scope,
    )

    internal val descriptor = SensorDescriptor(
        sensorId = SensorId(PrivateOwnerObservationPolicyBaseline.APP_USAGE_SENSOR_ID),
        sensorClass = SensorClass.APP_USAGE,
        adapterVersion = ADAPTER_VERSION,
        observationType = OwnerObservationType.APP_USAGE,
        resourcePrefix = PrivateOwnerObservationPolicyBaseline.APP_USAGE_RESOURCE_PREFIX,
        supportedSurfaces = setOf(ObservationSurfaceKind.APP_USAGE),
        defaultMode = SensorAttentionMode.PERIODIC,
    )

    internal val attentionCoverage = SensorAttentionCoverageProfile(
        sensorId = descriptor.sensorId,
        stateDimensions = listOf(
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.usage.",
            ),
            SensorStateDimensionSelector(
                SensorStateDimensionSelectorType.PREFIX,
                "app.foreground.",
            ),
        ).sortedWith(compareBy({ it.type.name }, { it.value })),
        observationContracts = setOf(
            "app.usage.readback",
            "app.foreground.readback",
        ),
        informationGainMicros = 700_000L,
        goalRelevanceMicros = 650_000L,
        verificationValueMicros = 650_000L,
        energyCostMicros = 150_000L,
        privacyCostMicros = 500_000L,
        latencyCostMicros = 200_000L,
        resourceCostMicros = 150_000L,
    )

    private val observationFactory = AppUsageObservationFactory(descriptor.sensorId)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val openForegroundByPackage = linkedMapOf<String, Instant>()

    @Volatile
    private var attentionMode: SensorAttentionMode = descriptor.defaultMode

    @Volatile
    private var healthReporter:
        (suspend (SensorHealthState, String?) -> Unit)? = null

    @Volatile
    private var lastReportedHealth: SensorHealthState? = null

    @Volatile
    private var lastReportedFailure: String? = null

    private var cursor = AppSensorCursor(
        sensorId = descriptor.sensorId,
        revision = 0L,
        sourcePosition = null,
    )
    private var lastQueryEndMillis: Long? = null
    private var pollJob: Job? = null

    internal fun bindHealthReporter(
        reporter: suspend (SensorHealthState, String?) -> Unit,
    ) {
        require(healthReporter == null) {
            "App-usage sensor health reporter is already bound"
        }
        healthReporter = reporter
    }

    internal fun currentHealth(): SensorHealthState =
        if (source.isAccessGranted()) {
            SensorHealthState.HEALTHY
        } else {
            SensorHealthState.UNAVAILABLE
        }

    internal fun applyAttention(mode: SensorAttentionMode) {
        attentionMode = mode
    }

    internal fun start() {
        if (!started.compareAndSet(false, true)) return
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (_: RuntimeException) {
                    reportHealth(
                        SensorHealthState.DEGRADED,
                        "app-usage-query-failed",
                    )
                }
                delay(pollIntervalMillis(attentionMode))
            }
        }
    }

    internal fun stop() {
        started.set(false)
        pollJob?.cancel()
        pollJob = null
    }

    internal suspend fun refreshAvailability(): SensorHealthState {
        val health = currentHealth()
        reportHealth(
            health,
            if (health == SensorHealthState.HEALTHY) {
                null
            } else {
                "usage-access-not-granted"
            },
        )
        return health
    }

    internal suspend fun pollOnce(): Int {
        if (refreshAvailability() != SensorHealthState.HEALTHY) return 0
        if (attentionMode == SensorAttentionMode.SUSPENDED) return 0

        return mutex.withLock {
            if (attentionMode == SensorAttentionMode.SUSPENDED) return@withLock 0

            val endMillis = clock.millis().coerceAtLeast(0L)
            val beginMillis = lastQueryEndMillis
                ?.let { previous ->
                    if (previous < Long.MAX_VALUE) previous + 1L else previous
                }
                ?: (endMillis - INITIAL_LOOKBACK_MILLIS).coerceAtLeast(0L)

            if (beginMillis > endMillis) return@withLock 0

            val platformEvents = source.queryEvents(
                beginMillis = beginMillis,
                endMillis = endMillis,
                maxEvents = APP_USAGE_BUDGET.maxObservations,
            )
            val candidateForeground = LinkedHashMap(openForegroundByPackage)
            val pollAt = Instant.ofEpochMilli(endMillis)
            val observations = buildList {
                platformEvents.forEach { event ->
                    val eventAt = Instant.ofEpochMilli(event.timestampMillis)
                    when (event.kind) {
                        PlatformAppUsageEventKind.FOREGROUND -> {
                            candidateForeground[event.packageName] = eventAt
                            add(
                                observationFactory.create(
                                    AppUsageEvent(
                                        packageName = event.packageName,
                                        foregroundSince = eventAt,
                                        backgroundAt = null,
                                        observedAt = eventAt,
                                        eventType = AppUsageEventType.FOREGROUND_ENTER,
                                        sourceRevision = sourceRevision(event),
                                    )
                                )
                            )
                        }

                        PlatformAppUsageEventKind.BACKGROUND -> {
                            val foregroundSince =
                                candidateForeground.remove(event.packageName)
                                    ?: return@forEach
                            if (eventAt.isBefore(foregroundSince)) return@forEach
                            add(
                                observationFactory.create(
                                    AppUsageEvent(
                                        packageName = event.packageName,
                                        foregroundSince = foregroundSince,
                                        backgroundAt = eventAt,
                                        observedAt = eventAt,
                                        eventType = AppUsageEventType.FOREGROUND_INTERVAL,
                                        sourceRevision = intervalRevision(
                                            packageName = event.packageName,
                                            foregroundSince = foregroundSince,
                                            backgroundAt = eventAt,
                                        ),
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (observations.isNotEmpty()) {
                val current = cursor
                val next = AppSensorCursor(
                    sensorId = descriptor.sensorId,
                    revision = current.revision + 1L,
                    sourcePosition = observations.last().sourceRevision,
                )
                val batch = AppObservationBatch.create(
                    sensorId = descriptor.sensorId,
                    observations = observations,
                    nextCursor = next,
                    exhausted = platformEvents.size < APP_USAGE_BUDGET.maxObservations,
                )
                commitBatch.commit(
                    descriptor = descriptor,
                    cursor = current,
                    budget = APP_USAGE_BUDGET,
                    batch = batch,
                )
                cursor = next
            }

            openForegroundByPackage.clear()
            openForegroundByPackage.putAll(candidateForeground)
            pruneForegroundSessions(pollAt)
            lastQueryEndMillis = endMillis
            observations.size
        }
    }

    private suspend fun reportHealth(
        health: SensorHealthState,
        failure: String?,
    ) {
        if (lastReportedHealth == health && lastReportedFailure == failure) return
        lastReportedHealth = health
        lastReportedFailure = failure
        healthReporter?.invoke(health, failure)
    }

    private fun pruneForegroundSessions(now: Instant) {
        val cutoff = now.minusMillis(MAX_OPEN_SESSION_MILLIS)
        openForegroundByPackage.entries.removeAll { (_, since) ->
            since.isBefore(cutoff)
        }
    }

    private fun sourceRevision(event: PlatformAppUsageEvent): String =
        "usage:${event.timestampMillis}:${event.kind.name}:${event.packageName}"

    private fun intervalRevision(
        packageName: String,
        foregroundSince: Instant,
        backgroundAt: Instant,
    ): String =
        "usage-interval:${foregroundSince.toEpochMilli()}:${backgroundAt.toEpochMilli()}:$packageName"

    private fun pollIntervalMillis(mode: SensorAttentionMode): Long = when (mode) {
        SensorAttentionMode.FOCUSED -> 2_000L
        SensorAttentionMode.EVENT_DRIVEN -> 5_000L
        SensorAttentionMode.PERIODIC -> 15_000L
        SensorAttentionMode.SUSPENDED -> 30_000L
    }

    private companion object {
        const val ADAPTER_VERSION = "1"
        const val APP_USAGE_SALIENCE = 0.45
        const val INITIAL_LOOKBACK_MILLIS = 60_000L
        const val MAX_OPEN_SESSION_MILLIS = 24L * 60L * 60L * 1_000L

        val APP_USAGE_BUDGET = AppSensorBudget(
            maxObservations = 64,
            maxPayloadChars = 64 * 1024,
        )
    }
}
