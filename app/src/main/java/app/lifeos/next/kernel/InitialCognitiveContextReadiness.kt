package app.lifeos.next.kernel

import app.lifeos.core.runtime.life.DurableLifeMemorySnapshot
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.core.runtime.life.InitialDataBootstrapStatus
import app.lifeos.core.runtime.life.InitialDataSourceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process truth for whether user-facing language may rely on the personal cognitive context.
 *
 * Kernel boot readiness and cognitive-context readiness are deliberately separate. The kernel can
 * be operational while first-read permissions, source ingestion and the durable life-memory graph
 * are still being resolved. Chat/voice must not interpret or answer against that transient state.
 */
enum class InitialCognitiveContextPhase {
    PREPARING,
    WAITING_FOR_PERMISSIONS,
    BUILDING_MEMORY,
    READY,
    PARTIAL,
    FAILED,
}

data class InitialCognitiveContextReadiness(
    val phase: InitialCognitiveContextPhase,
    val contextReady: Boolean,
    val bootstrapFingerprint: String? = null,
    val bootstrapReportPhotonId: String? = null,
    val sourceStateFingerprint: String? = null,
    val memoryFingerprint: String? = null,
    val authoritativePhotonCount: Int = 0,
    val totalSources: Int = 0,
    val availableSources: Int = 0,
    val unauthorizedSources: Int = 0,
    val unavailableSources: Int = 0,
    val durableRecordCount: Int = 0,
    val failure: String? = null,
) {
    init {
        require(authoritativePhotonCount >= 0)
        require(totalSources >= 0)
        require(availableSources >= 0)
        require(unauthorizedSources >= 0)
        require(unavailableSources >= 0)
        require(durableRecordCount >= 0)
        require(availableSources + unauthorizedSources + unavailableSources <= totalSources)
        require(contextReady == (phase == InitialCognitiveContextPhase.READY || phase == InitialCognitiveContextPhase.PARTIAL))
        require((phase == InitialCognitiveContextPhase.FAILED) == (failure != null))
        if (contextReady) {
            require(!bootstrapFingerprint.isNullOrBlank())
            require(!sourceStateFingerprint.isNullOrBlank())
            require(!memoryFingerprint.isNullOrBlank())
            require(!bootstrapReportPhotonId.isNullOrBlank())
        }
    }

    companion object {
        fun preparing(): InitialCognitiveContextReadiness = InitialCognitiveContextReadiness(
            phase = InitialCognitiveContextPhase.PREPARING,
            contextReady = false,
        )

        fun waitingForPermissions(): InitialCognitiveContextReadiness = InitialCognitiveContextReadiness(
            phase = InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS,
            contextReady = false,
        )

        fun buildingMemory(): InitialCognitiveContextReadiness = InitialCognitiveContextReadiness(
            phase = InitialCognitiveContextPhase.BUILDING_MEMORY,
            contextReady = false,
        )

        fun failed(message: String): InitialCognitiveContextReadiness = InitialCognitiveContextReadiness(
            phase = InitialCognitiveContextPhase.FAILED,
            contextReady = false,
            failure = message.ifBlank { "initial-cognitive-context-failed" },
        )

        fun from(
            bootstrap: InitialDataBootstrapSnapshot,
            memory: DurableLifeMemorySnapshot,
        ): InitialCognitiveContextReadiness {
            val sources = bootstrap.sources
            return InitialCognitiveContextReadiness(
                phase = if (bootstrap.status == InitialDataBootstrapStatus.COMPLETE) {
                    InitialCognitiveContextPhase.READY
                } else {
                    InitialCognitiveContextPhase.PARTIAL
                },
                contextReady = true,
                bootstrapFingerprint = bootstrap.fingerprint,
                bootstrapReportPhotonId = bootstrap.reportPhotonId.value,
                sourceStateFingerprint = bootstrap.sourceStateFingerprint,
                memoryFingerprint = memory.fingerprint,
                authoritativePhotonCount = memory.authoritativePhotonCount,
                totalSources = sources.size,
                availableSources = sources.count { it.status == InitialDataSourceStatus.AVAILABLE },
                unauthorizedSources = sources.count { it.status == InitialDataSourceStatus.UNAUTHORIZED },
                unavailableSources = sources.count { it.status == InitialDataSourceStatus.UNAVAILABLE },
                durableRecordCount = sources.sumOf { it.durableRecordCount },
            )
        }
    }
}

/**
 * Process-local authority consumed by chat and voice. The Activity only publishes a READY/PARTIAL
 * state after observing a completed InitialDataBootstrapSnapshot together with the corresponding
 * durable memory snapshot. A permission refresh moves back to BUILDING_MEMORY first.
 */
object InitialCognitiveContextRuntimeRegistry {
    private val mutableState = MutableStateFlow(InitialCognitiveContextReadiness.preparing())

    val state: StateFlow<InitialCognitiveContextReadiness> = mutableState.asStateFlow()

    fun current(): InitialCognitiveContextReadiness = mutableState.value

    fun markWaitingForPermissions() {
        mutableState.value = InitialCognitiveContextReadiness.waitingForPermissions()
    }

    fun markBuildingMemory() {
        mutableState.value = InitialCognitiveContextReadiness.buildingMemory()
    }

    fun publish(
        bootstrap: InitialDataBootstrapSnapshot,
        memory: DurableLifeMemorySnapshot,
    ) {
        mutableState.value = InitialCognitiveContextReadiness.from(bootstrap, memory)
    }

    fun fail(message: String) {
        mutableState.value = InitialCognitiveContextReadiness.failed(message)
    }

    internal fun markReadyForTest() {
        mutableState.value = InitialCognitiveContextReadiness(
            phase = InitialCognitiveContextPhase.READY,
            contextReady = true,
            bootstrapFingerprint = "test-bootstrap",
            bootstrapReportPhotonId = "test-report",
            sourceStateFingerprint = "test-source-state",
            memoryFingerprint = "test-memory",
        )
    }

    internal fun resetForTest() {
        mutableState.value = InitialCognitiveContextReadiness.preparing()
    }
}
