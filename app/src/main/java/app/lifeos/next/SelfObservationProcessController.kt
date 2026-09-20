package app.lifeos.next

import app.lifeos.core.runtime.evolution.WorldEquationPostActivationSafetyRuntimeRegistry
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthObservation
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthState
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_NODE_ID
import app.lifeos.core.runtime.self.SELF_OBSERVATION_HEALTH_SOURCE
import app.lifeos.core.runtime.self.SelfObservationCoordinator
import app.lifeos.core.runtime.self.SelfObservationCycle
import app.lifeos.core.runtime.self.SelfObservationDecisionTraceRecorder
import app.lifeos.core.runtime.self.SelfObservationTrigger
import app.lifeos.core.runtime.world.SelfStateWorldBand
import app.lifeos.core.runtime.world.SelfStateWorldFormulaRuntimeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SelfObservationProcessController(
    private val coordinator: SelfObservationCoordinator,
    private val healthGraph: HealthGraph,
    private val traceRecorder: SelfObservationDecisionTraceRecorder,
    private val onAnalysis: (SelfObservationAnalysisState) -> Unit,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var job: Job? = null
    private val analysisMutex = Mutex()

    @Volatile
    private var lastWorldBand: SelfStateWorldBand? = null

    @Volatile
    private var lastTraceIdentity: String? = null

    suspend fun start() {
        if (job?.isActive == true) return
        // Register before event-driven refreshes can emit a derived node with UNKNOWN scope.
        healthGraph.register(
            HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
            HealthScope.RUNTIME,
        )
        job = scope.launch {
            launch {
                healthGraph.observations.collect { observation ->
                    if (observation.source != SELF_OBSERVATION_HEALTH_SOURCE) {
                        request(
                            if (observation.state == HealthState.RECOVERING) {
                                SelfObservationTrigger.RECOVERY_TRANSITION
                            } else {
                                SelfObservationTrigger.HEALTH_TRANSITION
                            }
                        )
                    }
                }
            }

            var trigger = SelfObservationTrigger.STARTUP
            while (currentCoroutineContext().isActive) {
                val cycle = refreshAndAnalyze(trigger)
                trigger = SelfObservationTrigger.TIMER
                delay(coordinator.nextInterval(cycle.band).toMillis())
            }
        }
    }

    fun refresh() {
        request(SelfObservationTrigger.EXPLICIT_UI_REFRESH)
    }

    fun request(trigger: SelfObservationTrigger) {
        scope.launch {
            refreshAndAnalyze(trigger)
        }
    }

    private suspend fun refreshAndAnalyze(
        trigger: SelfObservationTrigger,
    ): SelfObservationCycle {
        val cycle = coordinator.refresh(trigger)
        if (cycle.emitted) {
            analyze(cycle)
        }
        return cycle
    }

    private suspend fun analyze(cycle: SelfObservationCycle) {
        analysisMutex.withLock {
            val assessment =
                SelfStateWorldFormulaRuntimeRegistry.requireCurrent().evaluate(cycle.result)
            onAnalysis(SelfObservationAnalysisState(cycle, assessment))

            val traceIdentity =
                cycle.snapshot.authorityFingerprint + ":" + assessment.band.name
            if (traceIdentity != lastTraceIdentity) {
                traceRecorder.record(
                    snapshot = cycle.snapshot,
                    assessment = assessment,
                )
                lastTraceIdentity = traceIdentity
            }

            WorldEquationPostActivationSafetyRuntimeRegistry.current()?.observe(
                assessmentId = assessment.analysisId,
                authorityFingerprint = assessment.authorityFingerprint,
                band = assessment.band,
                observedAt = cycle.snapshot.capturedAt,
            )

            val previousBand = lastWorldBand
            if (assessment.band != previousBand) {
                when (assessment.band) {
                    SelfStateWorldBand.CRITICAL -> healthGraph.record(
                        HealthObservation(
                            nodeId = HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                            state = HealthState.UNHEALTHY,
                            observedAt = cycle.snapshot.capturedAt,
                            source = SELF_OBSERVATION_HEALTH_SOURCE,
                            message =
                                "self-observation:critical:" +
                                    assessment.reasonCodes.joinToString("|"),
                            actionable = false,
                        )
                    )

                    SelfStateWorldBand.DEGRADED -> healthGraph.record(
                        HealthObservation(
                            nodeId = HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                            state = HealthState.DEGRADED,
                            observedAt = cycle.snapshot.capturedAt,
                            source = SELF_OBSERVATION_HEALTH_SOURCE,
                            message =
                                "self-observation:degraded:" +
                                    assessment.reasonCodes.joinToString("|"),
                            actionable = false,
                        )
                    )

                    SelfStateWorldBand.STABLE,
                    SelfStateWorldBand.OBSERVE -> {
                        if (
                            previousBand == SelfStateWorldBand.DEGRADED ||
                            previousBand == SelfStateWorldBand.CRITICAL
                        ) {
                            healthGraph.recordHealthy(
                                id = HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                                source = SELF_OBSERVATION_HEALTH_SOURCE,
                                message = "self-observation:recovered",
                                observedAt = cycle.snapshot.capturedAt,
                                actionable = false,
                            )
                        }
                    }
                }
                lastWorldBand = assessment.band
            }
        }
    }
}
