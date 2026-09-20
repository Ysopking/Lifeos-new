package app.lifeos.core.runtime.buildstudio

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingRegistry
import app.lifeos.core.runtime.topology.LifeOsRuntimeBindingState

/** Runtime-owned bridge for BuildStudio host capability and topology state. */
object BuildStudioHostProcessRegistry {
    private data class Installed(
        val host: BuildStudioHostAdapter,
        val capabilities: CapabilityRegistry,
        val providerId: String,
    )

    private val lock = Any()
    @Volatile private var installed: Installed? = null

    suspend fun install(
        host: BuildStudioHostAdapter,
        capabilities: CapabilityRegistry,
    ): BuildStudioHostStatus {
        require(host.id.isNotBlank()) { "BuildStudio host id must not be blank" }
        val status = host.status()
        val providerId = providerId(host.id)
        val previous = synchronized(lock) { installed }
        if (previous != null && (previous.capabilities !== capabilities || previous.providerId != providerId)) {
            previous.capabilities.unregister(CAPABILITY_ID, previous.providerId)
        }
        capabilities.register(descriptor(providerId, status.state))
        synchronized(lock) {
            installed = Installed(host, capabilities, providerId)
        }
        LifeOsRuntimeBindingRegistry.install(
            subsystemId = SUBSYSTEM_ID,
            state = status.state.toBindingState(),
            source = "buildstudio-host:${host.id}",
            detail = status.detail,
        )
        return status
    }

    suspend fun refresh(): BuildStudioHostStatus? {
        val current = synchronized(lock) { installed } ?: return null
        val status = current.host.status()
        current.capabilities.register(descriptor(current.providerId, status.state))
        val binding = LifeOsRuntimeBindingRegistry.current(SUBSYSTEM_ID)
        if (binding == null) {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = SUBSYSTEM_ID,
                state = status.state.toBindingState(),
                source = "buildstudio-host:${current.host.id}",
                detail = status.detail,
            )
        } else {
            LifeOsRuntimeBindingRegistry.update(
                subsystemId = SUBSYSTEM_ID,
                state = status.state.toBindingState(),
                detail = status.detail,
            )
        }
        return status
    }

    suspend fun run(spec: BuildSpec): BuildStudioResult {
        val current = synchronized(lock) { installed }
            ?: return BuildStudioResult.Failed("host", "buildstudio-host-not-installed")
        return when (val status = refresh()) {
            null -> BuildStudioResult.Failed("host", "buildstudio-host-not-installed")
            is BuildStudioHostStatus -> when (status.state) {
                BuildStudioHostState.READY,
                BuildStudioHostState.DEGRADED -> current.host.run(spec)
                BuildStudioHostState.QUARANTINED -> BuildStudioResult.Failed(
                    "host",
                    "buildstudio-host-quarantined:${status.detail.orEmpty()}",
                )
                BuildStudioHostState.STOPPED -> BuildStudioResult.Failed(
                    "host",
                    "buildstudio-host-stopped:${status.detail.orEmpty()}",
                )
            }
        }
    }

    suspend fun expand(request: BuildStudioExpansionRequest): BuildStudioResult {
        require(!request.activationAllowed) { "BuildStudio expansion request cannot authorize activation" }
        val current = synchronized(lock) { installed }
            ?: return BuildStudioResult.Failed("host", "buildstudio-host-not-installed")
        return when (val status = refresh()) {
            null -> BuildStudioResult.Failed("host", "buildstudio-host-not-installed")
            is BuildStudioHostStatus -> when (status.state) {
                BuildStudioHostState.READY,
                BuildStudioHostState.DEGRADED -> current.host.expand(request)
                BuildStudioHostState.QUARANTINED -> BuildStudioResult.Failed(
                    "host",
                    "buildstudio-host-quarantined:${status.detail.orEmpty()}",
                )
                BuildStudioHostState.STOPPED -> BuildStudioResult.Failed(
                    "host",
                    "buildstudio-host-stopped:${status.detail.orEmpty()}",
                )
            }
        }
    }

    suspend fun uninstall() {
        val previous = synchronized(lock) {
            installed.also { installed = null }
        } ?: return
        previous.capabilities.unregister(CAPABILITY_ID, previous.providerId)
        val binding = LifeOsRuntimeBindingRegistry.current(SUBSYSTEM_ID)
        if (binding == null) {
            LifeOsRuntimeBindingRegistry.install(
                subsystemId = SUBSYSTEM_ID,
                state = LifeOsRuntimeBindingState.STOPPED,
                source = "buildstudio-host:${previous.host.id}",
                detail = "host-uninstalled",
            )
        } else {
            LifeOsRuntimeBindingRegistry.update(
                subsystemId = SUBSYSTEM_ID,
                state = LifeOsRuntimeBindingState.STOPPED,
                detail = "host-uninstalled",
            )
        }
    }

    fun current(): BuildStudioHostAdapter? = synchronized(lock) { installed?.host }

    private fun descriptor(providerId: String, state: BuildStudioHostState) = CapabilityDescriptor(
        capabilityId = CAPABILITY_ID,
        providerId = providerId,
        providerType = ProviderType.CONNECTOR,
        contract = CapabilityContract(
            requiredInputs = setOf("goal-photon"),
            outputs = setOf("build-artifact"),
        ),
        state = state.toProviderState(),
        trustLevel = TrustLevel.HIGH,
        reliability = if (state == BuildStudioHostState.READY) 1.0 else 0.75,
        cost = 1.0,
    )

    private fun providerId(hostId: String): String = "buildstudio-host:$hostId"

    private fun BuildStudioHostState.toProviderState(): ProviderState = when (this) {
        BuildStudioHostState.READY -> ProviderState.ACTIVE
        BuildStudioHostState.DEGRADED -> ProviderState.DEGRADED
        BuildStudioHostState.QUARANTINED -> ProviderState.QUARANTINED
        BuildStudioHostState.STOPPED -> ProviderState.DISABLED
    }

    private fun BuildStudioHostState.toBindingState(): LifeOsRuntimeBindingState = when (this) {
        BuildStudioHostState.READY -> LifeOsRuntimeBindingState.ACTIVE
        BuildStudioHostState.DEGRADED -> LifeOsRuntimeBindingState.DEGRADED
        BuildStudioHostState.QUARANTINED -> LifeOsRuntimeBindingState.QUARANTINED
        BuildStudioHostState.STOPPED -> LifeOsRuntimeBindingState.STOPPED
    }

    val CAPABILITY_ID = CapabilityId("buildstudio.run")
    const val SUBSYSTEM_ID = "build-studio"
}
