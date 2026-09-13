package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.data.capability.EncryptedGeneratedToolArtifactRepository
import app.lifeos.core.data.capability.EncryptedToolWorkshopJobRepository
import app.lifeos.core.data.capability.EncryptedToolWorkshopStageArtifactRepository
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.data.resource.EncryptedResourceBudgetRepository
import app.lifeos.core.runtime.capability.DurableToolWorkshopCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolArtifactBootVerifier
import app.lifeos.core.runtime.capability.GeneratedToolArtifactRepository
import app.lifeos.core.runtime.capability.GeneratedToolGenesisCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolLifecycleCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolRegistry
import app.lifeos.core.runtime.capability.GeneratedToolRequestCoordinator
import app.lifeos.core.runtime.capability.GeneratedToolStateRepository
import app.lifeos.core.runtime.capability.GeneratedToolTrialRunner
import app.lifeos.core.runtime.capability.PrivateGeneratedCapabilityVerifier
import app.lifeos.core.runtime.capability.PrivateToolBuildCatalog
import app.lifeos.core.runtime.capability.PrivateToolBuildRunner
import app.lifeos.core.runtime.capability.PrivateToolDesigner
import app.lifeos.core.runtime.capability.PrivateToolImplementationEngine
import app.lifeos.core.runtime.capability.PrivateToolSecurityValidator
import app.lifeos.core.runtime.capability.PrivateToolSpecificationBuilder
import app.lifeos.core.runtime.capability.PrivateToolTestRunner
import app.lifeos.core.runtime.capability.PrivateToolWorkshopBuildStateRehydrator
import app.lifeos.core.runtime.capability.ToolWorkshopBootRuntimeRegistry
import app.lifeos.core.runtime.capability.ToolWorkshopCoordinator
import app.lifeos.core.runtime.capability.ToolWorkshopJobLedger
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator
import kotlinx.coroutines.runBlocking

/**
 * Process-owned private-v1 generated-tool composition. Explicit Genesis and autonomous V11 both use
 * the exact same productive registry, lifecycle, bounded adapters, build catalog and encrypted
 * generated-tool artifacts; V11 adds only durable job/stage orchestration around those surfaces.
 */
internal data class PrivateGeneratedToolRuntimeResources(
    val artifactRepository: GeneratedToolArtifactRepository,
    val artifactBootVerifier: GeneratedToolArtifactBootVerifier,
    val workshop: ToolWorkshopCoordinator,
    val durableWorkshop: DurableToolWorkshopCoordinator,
    val workshopJobs: ToolWorkshopJobLedger,
    val genesis: GeneratedToolGenesisCoordinator,
    val requests: GeneratedToolRequestCoordinator,
    val trialRunner: GeneratedToolTrialRunner,
) {
    companion object {
        fun create(
            context: Context,
            stateRepository: GeneratedToolStateRepository,
            tools: GeneratedToolRegistry,
            lifecycle: GeneratedToolLifecycleCoordinator,
        ): PrivateGeneratedToolRuntimeResources {
            val appContext = context.applicationContext
            val artifacts = EncryptedGeneratedToolArtifactRepository(appContext)
            GeneratedToolOwnerReviewRuntime.configure(
                artifactRepository = artifacts,
                toolRegistry = tools,
                lifecycleCoordinator = lifecycle,
            )
            val catalog = PrivateToolBuildCatalog()
            val specificationBuilder = PrivateToolSpecificationBuilder()
            val designer = PrivateToolDesigner()
            val implementationEngine = PrivateToolImplementationEngine()
            val buildRunner = PrivateToolBuildRunner(catalog)
            val testRunner = PrivateToolTestRunner(catalog)
            val securityValidator = PrivateToolSecurityValidator()
            val capabilityVerifier = PrivateGeneratedCapabilityVerifier(catalog)

            val workshop = ToolWorkshopCoordinator(
                specificationBuilder = specificationBuilder,
                designer = designer,
                implementationEngine = implementationEngine,
                buildRunner = buildRunner,
                testRunner = testRunner,
                securityValidator = securityValidator,
                capabilityVerifier = capabilityVerifier,
                registry = tools,
                artifactRepository = artifacts,
            )

            val ownerPolicy = OwnerPolicyLedger(EncryptedOwnerPolicyRepository(appContext))
            runBlocking { PrivateOwnerPolicyBaseline.ensure(ownerPolicy) }
            val budgets = ResourceBudgetCoordinator(EncryptedResourceBudgetRepository(appContext))
            val workshopJobs = ToolWorkshopJobLedger(
                EncryptedToolWorkshopJobRepository(appContext)
            )
            val durableWorkshop = DurableToolWorkshopCoordinator(
                jobs = workshopJobs,
                stageArtifacts = EncryptedToolWorkshopStageArtifactRepository(appContext),
                specificationBuilder = specificationBuilder,
                designer = designer,
                implementationEngine = implementationEngine,
                buildRunner = buildRunner,
                testRunner = testRunner,
                securityValidator = securityValidator,
                capabilityVerifier = capabilityVerifier,
                tools = tools,
                lifecycle = lifecycle,
                ownerPolicy = ownerPolicy,
                budgets = budgets,
                actorId = PrivateOwnerPolicyBaseline.ownerActorId,
                ownerScope = PrivateOwnerPolicyBaseline.TOOL_WORKSHOP_SCOPE,
                generatedArtifacts = artifacts,
                buildStateRehydrator = PrivateToolWorkshopBuildStateRehydrator(catalog),
            )

            val photonStore = EncryptedPhotonStore(appContext)
            val autonomousRuntime = AutonomousToolWorkshopRuntime(
                workshop = durableWorkshop,
                jobs = workshopJobs,
                persistPhoton = { photon ->
                    val durable = photonStore.load(photon.id)?.also { existing ->
                        require(existing == photon) {
                            "Autonomous ToolWorkshop Photon identity was reused with different content"
                        }
                    } ?: run {
                        photonStore.save(photon)
                        requireNotNull(photonStore.load(photon.id)) {
                            "Autonomous ToolWorkshop Photon was not durable after persistence"
                        }
                    }
                    LifeOsAutomationPhotonBridge.ingestIfInstalled(durable)?.also { ingested ->
                        require(ingested == durable) {
                            "Kernel ingestion changed autonomous ToolWorkshop Photon identity"
                        }
                    } ?: durable
                },
            )
            AutonomousToolWorkshopRuntimeRegistry.install(autonomousRuntime)
            ToolWorkshopBootRuntimeRegistry.install {
                autonomousRuntime.reconcileOpenJobs()
                Unit
            }

            val genesis = GeneratedToolGenesisCoordinator(
                workshop = workshop,
                lifecycle = lifecycle,
            )
            return PrivateGeneratedToolRuntimeResources(
                artifactRepository = artifacts,
                artifactBootVerifier = GeneratedToolArtifactBootVerifier(
                    states = stateRepository,
                    artifacts = artifacts,
                ),
                workshop = workshop,
                durableWorkshop = durableWorkshop,
                workshopJobs = workshopJobs,
                genesis = genesis,
                requests = GeneratedToolRequestCoordinator(genesis),
                trialRunner = GeneratedToolTrialRunner(
                    tools = tools,
                    lifecycle = lifecycle,
                    artifacts = artifacts,
                ),
            )
        }
    }
}
