package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.convergence.EncryptedConvergenceDecisionCheckpointRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchCheckpointRepository
import app.lifeos.core.data.deepsearch.EncryptedDeepSearchMissionRepository
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.data.resource.EncryptedResourceBudgetRepository
import app.lifeos.core.data.trace.EncryptedDecisionTraceRepository
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.RuntimeSupervisorProcessRegistry
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthority
import app.lifeos.core.runtime.capability.GeneratedProviderRestoreAuthorityRuntimeRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.convergence.DurableConvergenceDecisionCoordinator
import app.lifeos.core.runtime.deepsearch.DeepSearchCheckpointStore
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionCoordinator
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionId
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionLedger
import app.lifeos.core.runtime.deepsearch.DeepSearchMissionRuntimeRegistry
import app.lifeos.core.runtime.deepsearch.DeepSearchResultPhotonPersistence
import app.lifeos.core.runtime.goal.GoalConvergenceDecisionProvider
import app.lifeos.core.runtime.health.HealthGraphProcessRegistry
import app.lifeos.core.runtime.health.QuarantineRegistryProcessRegistry
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.SharedResourceBudgetRuntimeRegistry
import app.lifeos.core.runtime.trace.DecisionTraceLedger
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import app.lifeos.core.runtime.trace.GoalDecisionTraceRecorder
import app.lifeos.core.runtime.trace.SubsystemDecisionTraceRecorder
import app.lifeos.next.kernel.DurableGoalPlanRuntime
import app.lifeos.next.kernel.DurableGoalPlanRuntimeRegistry
import app.lifeos.next.kernel.GoalExecutionRuntimeRegistry
import app.lifeos.next.kernel.HardwareResourceIntelligenceRuntime
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LifeOsKernelFactory
import app.lifeos.next.kernel.PrivateGoalActionExecutionGuard
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
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

    lateinit var decisionTraces: DecisionTraceLedger
        private set

    internal lateinit var selfHealingRuntime: PrivateSelfHealingRuntime
        private set

    private lateinit var goalDecisionTraceRecorder: GoalDecisionTraceRecorder
    private val selfHealingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        generatedToolStatusReader = GeneratedToolRuntimeStatusReader(
            EncryptedGeneratedToolStateRepository(this),
        )
        hardwareResourceIntelligence = HardwareResourceIntelligenceRuntime(this)

        LifeOsStartupComposition.start(
            LifeOsStartupHooks(
                installSharedResourceRuntime = {
                    SharedResourceBudgetRuntimeRegistry.install(hardwareResourceIntelligence)
                    ownerPolicy = OwnerPolicyLedger(EncryptedOwnerPolicyRepository(this))
                    resourceBudgets = ResourceBudgetCoordinator(EncryptedResourceBudgetRepository(this))
                    decisionTraces = DecisionTraceLedger(EncryptedDecisionTraceRepository(this))
                    goalDecisionTraceRecorder = GoalDecisionTraceRecorder(decisionTraces)
                    DecisionTraceRuntimeRegistry.install(SubsystemDecisionTraceRecorder(decisionTraces))
                    runBlocking {
                        PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
                    }
                    GeneratedProviderRestoreAuthorityRuntimeRegistry.install(
                        GeneratedProviderRestoreAuthority(
                            ownerPolicy = ownerPolicy,
                            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                            scope = PrivateOwnerPolicyBaseline.GENERATED_PROVIDER_RESTORE_SCOPE,
                        )
                    )
                },
                installGoalExecutionRuntime = {
                    GoalExecutionRuntimeRegistry.install(
                        PrivateGoalActionExecutionGuard(
                            ownerPolicy = ownerPolicy,
                            budgets = resourceBudgets,
                            hardware = hardwareResourceIntelligence,
                            sharedBudgets = hardwareResourceIntelligence,
                            traces = goalDecisionTraceRecorder,
                        )
                    )
                },
                createKernel = {
                    kernel = LifeOsKernelFactory(this).create()
                },
                installDeepSearchRuntime = {
                    DeepSearchMissionRuntimeRegistry.install(
                        DeepSearchMissionCoordinator(
                            ledger = DeepSearchMissionLedger(EncryptedDeepSearchMissionRepository(this)),
                            checkpoints = DeepSearchCheckpointStore(EncryptedDeepSearchCheckpointRepository(this)),
                            resultPhotons = object : DeepSearchResultPhotonPersistence {
                                override suspend fun save(photon: Photon) {
                                    kernel.photonStore.save(photon)
                                }

                                override suspend fun load(id: PhotonId): Photon? = kernel.photonStore.load(id)

                                override suspend fun findForMission(missionId: DeepSearchMissionId): Photon? {
                                    val tag = "deepsearch-mission:${missionId.value}"
                                    val matches = kernel.photonStore.loadAll().filter { tag in it.tags }
                                    check(matches.size <= 1) {
                                        "DeepSearch mission resolved to multiple result Photons"
                                    }
                                    return matches.singleOrNull()
                                }
                            },
                        )
                    )
                },
                startSelfHealingRuntime = {
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
                },
                installDurableGoalPlanRuntime = {
                    val durableV5Decisions = DurableConvergenceDecisionCoordinator(
                        EncryptedConvergenceDecisionCheckpointRepository(this),
                    )
                    DurableGoalPlanRuntimeRegistry.install(
                        DurableGoalPlanRuntime(
                            ledger = kernel.goalPlans,
                            convergence = GoalConvergenceDecisionProvider(durableV5Decisions),
                            persistDerivedOutcome = kernel::persistAndIngest,
                            loadPersistedPhotons = kernel.photonStore::loadAll,
                            traces = goalDecisionTraceRecorder,
                        )
                    )
                },
                startKernel = { kernel.start() },
            )
        )
    }
}
