package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.runtime.field.AuthoritativeFieldProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Thin process composition root. Domain ownership is split across Foundation, Evolution, World,
 * Cognition and Boot compositions; this class only orders those graphs and assembles LifeOsKernel.
 */
class LifeOsKernelFactory(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val hardwareResourceIntelligence: HardwareResourceIntelligenceRuntime? = null,
    private val bootReadyMaintenanceTrigger: () -> Unit = {},
    private val authoritativeFieldProcessors: List<AuthoritativeFieldProcessor> = emptyList(),
) {
    fun create(): LifeOsKernel {
        val foundation = KernelFoundationComposition(
            context = context,
            dispatcher = dispatcher,
            hardwareResourceIntelligence = hardwareResourceIntelligence,
        ).compose()

        val evolution = KernelEvolutionComposition(
            appContext = foundation.appContext,
            capabilityRegistry = foundation.capabilityRegistry,
            learnedProviderReliability = foundation.learnedProviderReliability,
        ).compose()

        val world = KernelWorldComposition(
            foundation = foundation,
            evolution = evolution,
        ).compose()

        val cognition = KernelCognitionComposition(
            foundation = foundation,
            world = world,
            authoritativeFieldProcessors = authoritativeFieldProcessors,
        ).compose()

        val boot = KernelBootComposition(
            foundation = foundation,
            evolution = evolution,
            world = world,
            cognition = cognition,
        ).compose()

        return LifeOsKernel(
            runtime = cognition.durableRuntime,
            matrix = foundation.matrix,
            photonStore = foundation.store,
            supervisor = cognition.supervisor,
            scope = foundation.scope,
            bootCoordinator = boot.bootCoordinator,
            bootEngineRuntime = world.bootEngineRuntime,
            continuousCognition = cognition.continuousCognition,
            cognitiveModuleSnapshotRepository = foundation.cognitiveModuleSnapshotRepository,
            activeExtensionSnapshotId = {
                world.extensionRegistryHeadRepository.load()?.activeSnapshotId
            },
            bootReadyMaintenanceTrigger = bootReadyMaintenanceTrigger,
            photonTransactions = cognition.photonTransactions,
            cognitiveOutcomes = cognition.cognitiveOutcomes,
            cognitiveTriggers = cognition.cognitiveTriggers,
            mmsiRuntime = foundation.mmsiRuntime,
            languageUnderstanding = foundation.languageUnderstanding,
            languageRuntime = foundation.languageRuntime,
            personalLanguageLearning = foundation.personalLanguageLearning,
            goalPhotonFactory = foundation.goalPhotonFactory,
            goalPlans = foundation.goalPlans,
            productiveGoalConvergence = world.productiveGoalConvergence,
            goalOutcomeLearning = cognition.goalOutcomeLearning,
            languageContextBuilder = foundation.languageContextBuilder,
            goalCapabilityRouter = evolution.goalCapabilityRouter,
            privateGeneratedToolRuntime = evolution.privateGeneratedToolRuntime,
            evolutionRuntime = evolution.evolutionResources,
            worldEquationAutoEvolution = world.worldEquationAutoEvolution,
            localReminderScheduler = AndroidLocalReminderScheduler(foundation.appContext),
            sceneCompiler = foundation.sceneCompiler,
            sceneRasterizer = foundation.sceneRasterizer,
            imageAssets = foundation.assetStore,
            proceduralImageGenerator = foundation.proceduralImageGenerator,
        )
    }
}
