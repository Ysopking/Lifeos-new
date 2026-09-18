package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.escalation.EncryptedEscalationRepository
import app.lifeos.core.model.health.ProtectionReason
import app.lifeos.core.model.health.ProtectionReasonCode
import app.lifeos.core.model.health.ProtectionResumePolicy
import app.lifeos.core.runtime.escalation.AutomaticHealthEscalationOrchestrator
import app.lifeos.core.runtime.escalation.AutomaticSelfHealingEscalationPlanner
import app.lifeos.core.runtime.escalation.EscalationCoordinator
import app.lifeos.core.runtime.escalation.EscalationExecutionResult
import app.lifeos.core.runtime.escalation.EscalationExecutorRegistry
import app.lifeos.core.runtime.escalation.EscalationLedger
import app.lifeos.core.runtime.escalation.EscalationLevel
import app.lifeos.core.runtime.escalation.EscalationLevelExecutor
import app.lifeos.core.runtime.escalation.EscalationPolicy
import app.lifeos.core.runtime.escalation.EscalationRuntimeRegistry
import app.lifeos.core.runtime.escalation.ProtectionEscalationAuthority
import app.lifeos.core.runtime.escalation.ProtectionEscalationContext
import app.lifeos.core.runtime.escalation.ProtectionEscalationContextSource
import app.lifeos.core.runtime.escalation.ProtectionQuarantineEscalationExecutor
import app.lifeos.core.runtime.escalation.ProtectionSafeModeEscalationExecutor
import app.lifeos.core.runtime.escalation.SelfHealingEscalationAuthority
import app.lifeos.core.runtime.escalation.SelfHealingEscalationExecutor
import app.lifeos.core.runtime.health.HealthGraph
import app.lifeos.core.runtime.health.ProtectionCoordinator
import kotlinx.coroutines.CoroutineScope

/**
 * Private-process composition for the centralized R00-R04 escalation ladder.
 *
 * Health observations currently carry sufficient context for L2/L3/L6 only. Other levels remain
 * explicitly fail-closed until their owning producer supplies a typed execution context; they are
 * never guessed from a generic health failure.
 */
internal data class PrivateEscalationRuntime(
    val ledger: EscalationLedger,
    val coordinator: EscalationCoordinator,
    val orchestrator: AutomaticHealthEscalationOrchestrator,
) {
    suspend fun verifyLedgerIntegrity() {
        ledger.active()
    }

    companion object {
        fun create(
            context: Context,
            scope: CoroutineScope,
            graph: HealthGraph,
            protection: ProtectionCoordinator,
            selfHealing: PrivateSelfHealingRuntime,
        ): PrivateEscalationRuntime {
            val ledger = EscalationLedger(
                EncryptedEscalationRepository(context.applicationContext)
            )
            val planner = AutomaticSelfHealingEscalationPlanner(
                graph = graph,
                plans = selfHealing.plans,
                generations = selfHealing.generations,
            )
            val protectionAuthority = ProtectionEscalationAuthority.from(protection)

            val unavailable = EscalationLevelExecutor { request ->
                EscalationExecutionResult.Blocked(
                    "health-escalation-context-unavailable:" + request.level.name.lowercase()
                )
            }
            val executors = EscalationExecutorRegistry(
                mapOf(
                    EscalationLevel.L0_RETRY to unavailable,
                    EscalationLevel.L1_REEVALUATE to unavailable,
                    EscalationLevel.L2_RECOVER_COMPONENT to SelfHealingEscalationExecutor(
                        contexts = planner,
                        authority = SelfHealingEscalationAuthority.from(selfHealing.coordinator),
                    ),
                    EscalationLevel.L3_QUARANTINE to ProtectionQuarantineEscalationExecutor(
                        contexts = ProtectionEscalationContextSource { request ->
                            ProtectionEscalationContext(
                                reasons = listOf(
                                    ProtectionReason(
                                        code = ProtectionReasonCode.REPEATED_FAILURE,
                                        source = "central-escalation",
                                        message = "Health node " + request.nodeId.value +
                                            " requires quarantine after bounded recovery.",
                                    )
                                ),
                                provenanceTag = "health-quarantine",
                                resumePolicy = ProtectionResumePolicy.AUTO_AFTER_VERIFICATION,
                            )
                        },
                        authority = protectionAuthority,
                    ),
                    EscalationLevel.L4_FALLBACK to unavailable,
                    EscalationLevel.L5_ROLLBACK to unavailable,
                    EscalationLevel.L6_SAFE_MODE to ProtectionSafeModeEscalationExecutor(
                        contexts = ProtectionEscalationContextSource { request ->
                            ProtectionEscalationContext(
                                reasons = listOf(
                                    ProtectionReason(
                                        code = ProtectionReasonCode.INTEGRITY_FAILURE,
                                        source = "central-escalation",
                                        message = "Protection-critical failure at " +
                                            request.nodeId.value + " entered safe mode.",
                                    )
                                ),
                                provenanceTag = "health-safe-mode",
                                resumePolicy = ProtectionResumePolicy.USER_AFTER_VERIFICATION,
                                affectedNodes = setOf(request.nodeId),
                            )
                        },
                        authority = protectionAuthority,
                    ),
                    EscalationLevel.L7_REPAIR_PROPOSAL to unavailable,
                )
            )
            val coordinator = EscalationCoordinator(
                policy = EscalationPolicy(),
                ledger = ledger,
                executors = executors,
            )
            EscalationRuntimeRegistry.install(coordinator)
            return PrivateEscalationRuntime(
                ledger = ledger,
                coordinator = coordinator,
                orchestrator = AutomaticHealthEscalationOrchestrator(
                    scope = scope,
                    graph = graph,
                    recoveryPlanner = planner,
                    coordinator = coordinator,
                ),
            )
        }
    }
}
