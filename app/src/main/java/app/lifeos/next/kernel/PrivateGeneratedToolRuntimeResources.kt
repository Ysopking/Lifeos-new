package app.lifeos.next.kernel

import android.content.Context
import app.lifeos.core.data.capability.EncryptedGeneratedToolArtifactRepository
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
import app.lifeos.core.runtime.capability.ToolWorkshopCoordinator

/**
 * Process-owned private-v1 generated-tool composition. Every bounded tool stage shares the exact
 * same lifecycle registry and encrypted executable-artifact repository.
 */
internal data class PrivateGeneratedToolRuntimeResources(
    val artifactRepository: GeneratedToolArtifactRepository,
    val artifactBootVerifier: GeneratedToolArtifactBootVerifier,
    val workshop: ToolWorkshopCoordinator,
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
            val artifacts = EncryptedGeneratedToolArtifactRepository(context.applicationContext)
            val catalog = PrivateToolBuildCatalog()
            val workshop = ToolWorkshopCoordinator(
                specificationBuilder = PrivateToolSpecificationBuilder(),
                designer = PrivateToolDesigner(),
                implementationEngine = PrivateToolImplementationEngine(),
                buildRunner = PrivateToolBuildRunner(catalog),
                testRunner = PrivateToolTestRunner(catalog),
                securityValidator = PrivateToolSecurityValidator(),
                capabilityVerifier = PrivateGeneratedCapabilityVerifier(catalog),
                registry = tools,
                artifactRepository = artifacts,
            )
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
