package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.data.resource.EncryptedResourceBudgetRepository
import app.lifeos.core.runtime.RuntimeSupervisorProcessRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.health.QuarantineRegistryProcessRegistry
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import app.lifeos.next.kernel.DurableGoalPlanRuntime
import app.lifeos.next.kernel.DurableGoalPlanRuntimeRegistry
import app.lifeos.next.kernel.GoalExecutionRuntimeRegistry
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LifeOsKernelFactory
import app.lifeos.next.kernel.PrivateGoalActionExecutionGuard
import app.lifeos.next.kernel.PrivateSelfHealingRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** Process-level owner for the LIFEOS kernel instance and read-only private diagnostics. */
class LifeOsApplication : Application() {
    lateinit var kernel: LifeOsKernel
        private set

    lateinit var generatedToolStatusReader: GeneratedToolRuntimeStatusReader
        private set

    lateinit var hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime
        private set

    lateinit var ownerPolicy: OwnerPolicyLedger
        private set

    lateinit var resourceBudgets: ResourceBudgetCoordinator
        private set

    internal lateinit var selfHealingRuntime: PrivateSelfHealingRuntime
        private set

    private val selfHealingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        generatedToolStatusReader = GeneratedToolRuntimeStatusReader(
            EncryptedGeneratedToolStateRepository(this),
        )
        hardwareResourceIntelligence = HardwareResourceIntelligenceRuntime(this)
        SharedResourceBudgetRuntimeRegistry.install(hardwareResourceIntelligence)
        ownerPolicy = OwnerPolicyLedger(EncryptedOwnerPolicyRepository(this))
        resourceBudgets = ResourceBudgetCoordinator(EncryptedResourceBudgetRepository(this))
        GoalExecutionRuntimeRegistry.install(
            PrivateGoalActionExecutionGuard(
                ownerPolicy = ownerPolicy,
                budgets = resourceBudgets,
                hardware = hardwareResourceIntelligence,
                sharedBudgets = hardwareResourceIntelligence,
            )
        )

        kernel = LifeOsKernelFactory(this).create()
        selfHealingRuntime = PrivateSelfHealingRuntime.create(
            context = this,
            scope = selfHealingScope,
            graph = requireNotNull(HealthGraphProcessRegistry.current()) {
                "Kernel did not install its HealthGraph"
            },
            quarantineRegistry = requireNotNull(QuarantineRegistryProcessRegistry.current()) {
                "Kernel did not install its QuarantineRegistry"
            },
            budgets = resourceBudgets,
            runtime = kernel.runtime,
            supervisor = requireNotNull(RuntimeSupervisorProcessRegistry.current()) {
                "Kernel did not install its RuntimeSupervisor"
            },
        )
        runBlocking {
            selfHealingRuntime.verifyLedgerIntegrity()
        }
        selfHealingRuntime.orchestrator.start()

        val durableV5Decisions = DurableConvergenceDecisionCoordinator(
            EncryptedConvergenceDecisionCheckpointRepository(this),
        )
        DurableGoalPlanRuntimeRegistry.install(
            DurableGoalPlanRuntime(
                ledger = kernel.goalPlans,
                convergence = GoalConvergenceDecisionProvider(durableV5Decisions),
                persistDerivedOutcome = kernel::persistAndIngest,
                loadPersistedPhotons = kernel.photonStore::loadAll,
            )
        )
        kernel.start()
    }
}
