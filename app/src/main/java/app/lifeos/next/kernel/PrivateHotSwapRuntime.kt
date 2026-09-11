package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.data.capability.EncryptedHotSwapRepository
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapRevertCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapRevertResult
import app.lifeos.core.runtime.capability.GeneratedToolHotSwapResult
import app.lifeos.core.runtime.capability.GeneratedToolPromotionEvidence
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.capability.HotSwapBootReconciler
import app.lifeos.core.runtime.capability.HotSwapBootRuntimeRegistry
import app.lifeos.core.runtime.capability.HotSwapLedger
import app.lifeos.core.runtime.capability.HotSwapLifecycleFactory
import app.lifeos.core.runtime.capability.HotSwapResourceProfile
import app.lifeos.core.runtime.capability.HotSwapTransactionId
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import app.lifeos.core.runtime.resource.ResourceBudgetQuota
import app.lifeos.core.runtime.resource.ResourceBudgetUsage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-level V10 composition for the private APK. */
class PrivateHotSwapRuntime private constructor(
    val ledger: HotSwapLedger,
    private val lifecycleFactory: HotSwapLifecycleFactory,
    private val ownerPolicy: OwnerPolicyLedger,
    private val budgets: ResourceBudgetCoordinator,
) {
    private val swapMutex = Mutex()

    suspend fun verifyLedgerIntegrity() {
        ledger.all()
    }

    suspend fun swap(
        previousToolId: String,
        candidateToolId: String,
        evidence: GeneratedToolPromotionEvidence,
        resources: HotSwapResourceProfile = DEFAULT_RESOURCE_PROFILE,
    ): GeneratedToolHotSwapResult = swapMutex.withLock {
        PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
        val capabilities = requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()) {
            "Generated-tool capability registry is not installed"
        }
        val tools = requireNotNull(GeneratedToolRuntimeProcessRegistry.tools()) {
            "Generated-tool registry is not installed"
        }
        val lifecycle = lifecycleFactory.create()
        GeneratedToolHotSwapCoordinator(
            ledger = ledger,
            tools = tools,
            lifecycle = lifecycle,
            capabilities = capabilities,
            ownerPolicy = ownerPolicy,
            budgets = budgets,
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            ownerScope = PrivateOwnerPolicyBaseline.HOT_SWAP_SCOPE,
        ).swap(
            previousToolId = previousToolId,
            candidateToolId = candidateToolId,
            evidence = evidence,
            resources = resources,
        )
    }

    suspend fun revert(
        transactionId: HotSwapTransactionId,
        resources: HotSwapResourceProfile = DEFAULT_RESOURCE_PROFILE,
    ): GeneratedToolHotSwapRevertResult = swapMutex.withLock {
        PrivateOwnerPolicyBaseline.ensure(ownerPolicy)
        val capabilities = requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()) {
            "Generated-tool capability registry is not installed"
        }
        val tools = requireNotNull(GeneratedToolRuntimeProcessRegistry.tools()) {
            "Generated-tool registry is not installed"
        }
        GeneratedToolHotSwapRevertCoordinator(
            ledger = ledger,
            tools = tools,
            capabilities = capabilities,
            ownerPolicy = ownerPolicy,
            budgets = budgets,
            actorId = PrivateOwnerPolicyBaseline.ownerActorId,
            ownerScope = PrivateOwnerPolicyBaseline.HOT_SWAP_SCOPE,
        ).revert(transactionId, resources)
    }

    companion object {
        fun create(
            context: Context,
            ownerPolicy: OwnerPolicyLedger,
            budgets: ResourceBudgetCoordinator,
        ): PrivateHotSwapRuntime {
            val capabilities = requireNotNull(GeneratedToolRuntimeProcessRegistry.capabilities()) {
                "Kernel did not install its CapabilityRegistry"
            }
            val tools = requireNotNull(GeneratedToolRuntimeProcessRegistry.tools()) {
                "Kernel did not install its GeneratedToolRegistry"
            }
            val appContext = context.applicationContext
            val stateRepository = EncryptedGeneratedToolStateRepository(appContext)
            val ledger = HotSwapLedger(EncryptedHotSwapRepository(appContext))
            val lifecycleFactory = HotSwapLifecycleFactory(
                repository = stateRepository,
                tools = tools,
                capabilities = capabilities,
            )
            HotSwapBootRuntimeRegistry.install(
                HotSwapBootReconciler(
                    ledger = ledger,
                    capabilities = capabilities,
                    budgets = budgets,
                    ownerPolicy = ownerPolicy,
                    tools = tools,
                    actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                    ownerScope = PrivateOwnerPolicyBaseline.HOT_SWAP_SCOPE,
                )
            )
            return PrivateHotSwapRuntime(
                ledger = ledger,
                lifecycleFactory = lifecycleFactory,
                ownerPolicy = ownerPolicy,
                budgets = budgets,
            )
        }

        private const val MIB = 1024L * 1024L

        private val DEFAULT_RESOURCE_PROFILE = HotSwapResourceProfile(
            hardQuota = ResourceBudgetQuota(
                elapsedMillis = 5_000,
                workUnits = 32,
                memoryBytes = 64 * MIB,
                ioBytes = 8 * MIB,
                networkBytes = 0,
                candidates = 2,
            ),
            requested = ResourceBudgetUsage(
                elapsedMillis = 3_000,
                workUnits = 16,
                memoryBytes = 32 * MIB,
                ioBytes = 2 * MIB,
                networkBytes = 0,
                candidates = 1,
            ),
            goalRelevance = 1.0,
            priority = 0.9,
            expectedUtility = 0.95,
            confidence = 1.0,
        )
    }
}
