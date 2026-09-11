package app.lifeos.next.kernel

import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.capability.AutonomousToolWorkshopRequestPhoton
import app.lifeos.core.runtime.capability.DurableToolWorkshopCoordinator
import app.lifeos.core.runtime.capability.ToolWorkshopAdmissionResult
import app.lifeos.core.runtime.capability.ToolWorkshopExecutionProfile
import app.lifeos.core.runtime.capability.ToolWorkshopJobLedger
import app.lifeos.core.runtime.capability.ToolWorkshopJobSnapshot
import app.lifeos.core.runtime.capability.ToolWorkshopJobState
import app.lifeos.core.runtime.capability.ToolWorkshopStageResult
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AutonomousToolWorkshopResult {
    data object NotNeeded : AutonomousToolWorkshopResult
    data class Progressed(val jobs: List<ToolWorkshopJobSnapshot>) : AutonomousToolWorkshopResult
    data class Blocked(val jobs: List<ToolWorkshopJobSnapshot>, val reason: String) : AutonomousToolWorkshopResult
}

/**
 * Productive V11 live loop. Blocking capability gaps become deterministic request Photons and then
 * durable workshop jobs. One invocation may advance at most the fixed stage count, so the loop is
 * bounded even when every stage succeeds immediately. TRIAL_READY is the hard stop: activation is
 * still owned by Controlled Evolution / V10 and is never implied by generation.
 */
class AutonomousToolWorkshopRuntime(
    private val workshop: DurableToolWorkshopCoordinator,
    private val jobs: ToolWorkshopJobLedger,
    private val persistPhoton: suspend (Photon) -> Photon,
    private val profile: ToolWorkshopExecutionProfile = defaultProfile(),
) {
    private val mutex = Mutex()

    suspend fun process(context: GoalActionContext): AutonomousToolWorkshopResult = mutex.withLock {
        val gaps = context.routing.blockingGaps
            .sortedWith(compareBy({ it.requirement.capabilityId.value }, { it.type.name }))
        if (gaps.isEmpty()) return@withLock AutonomousToolWorkshopResult.NotNeeded

        val snapshots = mutableListOf<ToolWorkshopJobSnapshot>()
        for (gap in gaps) {
            val (requestPhoton, _) = AutonomousToolWorkshopRequestPhoton.create(
                gap = gap,
                sourcePhoton = context.sourcePhoton,
            )
            val stored = persistPhoton(requestPhoton)
            require(stored == requestPhoton) {
                "Autonomous ToolWorkshop request changed during durable persistence"
            }
            val request = AutonomousToolWorkshopRequestPhoton.decode(stored, context.sourcePhoton)
            val admitted = workshop.admit(
                request = request,
                sourceRevision = context.sourcePhoton.revision,
                policyVersion = POLICY_VERSION,
                workshopVersion = WORKSHOP_VERSION,
            )
            when (admitted) {
                is ToolWorkshopAdmissionResult.Blocked -> {
                    return@withLock AutonomousToolWorkshopResult.Blocked(
                        jobs = snapshots,
                        reason = admitted.reason,
                    )
                }
                is ToolWorkshopAdmissionResult.Ready -> {
                    val progressed = progress(admitted.snapshot)
                    snapshots += progressed.first
                    progressed.second?.let { reason ->
                        return@withLock AutonomousToolWorkshopResult.Blocked(snapshots, reason)
                    }
                }
            }
        }
        AutonomousToolWorkshopResult.Progressed(snapshots)
    }

    suspend fun reconcileOpenJobs(): List<ToolWorkshopJobSnapshot> = mutex.withLock {
        jobs.active().map { snapshot -> progress(snapshot).first }
    }

    private suspend fun progress(initial: ToolWorkshopJobSnapshot): Pair<ToolWorkshopJobSnapshot, String?> {
        var snapshot = initial
        repeat(MAX_STAGE_ADVANCES) {
            if (snapshot.terminal) return snapshot to null
            when (val result = workshop.runNext(snapshot.definition.id, profile)) {
                is ToolWorkshopStageResult.Advanced -> snapshot = result.snapshot
                is ToolWorkshopStageResult.TrialReady -> return result.snapshot to null
                is ToolWorkshopStageResult.Rejected -> return result.snapshot to result.reason
                is ToolWorkshopStageResult.Blocked -> return result.snapshot to result.reason
                is ToolWorkshopStageResult.AlreadyTerminal -> return result.snapshot to null
            }
        }
        return snapshot to if (snapshot.terminal) null else "tool-workshop-stage-bound-exhausted"
    }

    companion object {
        const val POLICY_VERSION = "private-owner-policy-v14"
        const val WORKSHOP_VERSION = "autonomous-tool-workshop-v11"
        private const val MAX_STAGE_ADVANCES = 8
        private const val MIB = 1024L * 1024L

        fun defaultProfile(): ToolWorkshopExecutionProfile {
            val quota = ResourceBudgetQuota(
                elapsedMillis = 15_000,
                workUnits = 128,
                memoryBytes = 128 * MIB,
                ioBytes = 16 * MIB,
                networkBytes = 0,
                candidates = 8,
            )
            fun usage(
                elapsedMillis: Long,
                workUnits: Long,
                memoryMiB: Long,
                ioMiB: Long,
                candidates: Long,
            ) = ResourceBudgetUsage(
                elapsedMillis = elapsedMillis,
                workUnits = workUnits,
                memoryBytes = memoryMiB * MIB,
                ioBytes = ioMiB * MIB,
                networkBytes = 0,
                candidates = candidates,
            )
            return ToolWorkshopExecutionProfile(
                hardQuota = quota,
                stageRequests = mapOf(
                    ToolWorkshopJobState.SPECIFIED to usage(1_000, 4, 16, 1, 1),
                    ToolWorkshopJobState.DESIGNED to usage(1_000, 4, 16, 1, 1),
                    ToolWorkshopJobState.IMPLEMENTED to usage(2_000, 8, 32, 2, 1),
                    ToolWorkshopJobState.BUILT to usage(4_000, 24, 64, 6, 2),
                    ToolWorkshopJobState.TESTED to usage(4_000, 32, 64, 4, 2),
                    ToolWorkshopJobState.SECURITY_VALIDATED to usage(2_000, 12, 32, 2, 1),
                    ToolWorkshopJobState.VERIFIED to usage(2_000, 12, 32, 2, 1),
                    ToolWorkshopJobState.TRIAL_READY to usage(1_000, 4, 16, 1, 1),
                ),
                goalRelevance = 1.0,
                priority = 0.85,
                expectedUtility = 0.90,
                confidence = 0.95,
            )
        }
    }
}

object AutonomousToolWorkshopRuntimeRegistry {
    @Volatile private var runtime: AutonomousToolWorkshopRuntime? = null

    fun install(value: AutonomousToolWorkshopRuntime) {
        runtime = value
    }

    suspend fun processIfInstalled(context: GoalActionContext): AutonomousToolWorkshopResult? =
        runtime?.process(context)

    suspend fun reconcileOpenJobs(): List<ToolWorkshopJobSnapshot> =
        runtime?.reconcileOpenJobs().orEmpty()
}
