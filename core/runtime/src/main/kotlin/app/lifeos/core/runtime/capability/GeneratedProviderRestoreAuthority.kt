package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.trace.DecisionTraceRuntimeRegistry
import java.time.Instant

/**
 * V14 boot-time authority for making an already-durable generated provider routable again.
 *
 * Durable ACTIVE lifecycle state and promotion receipts are evidence, not evergreen authority.
 * Every provider registration after process restart therefore re-reads the current owner policy
 * immediately around the CapabilityRegistry mutation. The request is bound to the exact capability,
 * tool identity and build/source fingerprint. A missing authority object is handled by callers as
 * fail-closed: durable state may still be restored for diagnostics, but no provider is made routable.
 */
class GeneratedProviderRestoreAuthority(
    private val ownerPolicy: OwnerPolicyLedger,
    private val actorId: OwnerActorId,
    private val scope: String,
) {
    init { require(scope.isNotBlank()) }

    suspend fun <T> expose(
        record: GeneratedToolRecord,
        effect: suspend () -> T,
    ): Boolean {
        val exposure = OwnerPolicyEffectGate(ownerPolicy).expose(
            request = request(record),
            effect = effect,
        )
        val assessment = when (exposure) {
            is OwnerEffectExposureResult.Exposed -> exposure.assessment
            is OwnerEffectExposureResult.Blocked -> exposure.assessment
        }
        val restored = exposure is OwnerEffectExposureResult.Exposed<*>
        DecisionTraceRuntimeRegistry.currentOrNull()?.recordGeneratedProviderRestore(
            record = record,
            assessment = assessment,
            restored = restored,
            recordedAt = Instant.now(),
        )
        return restored
    }

    suspend fun allowedNow(record: GeneratedToolRecord): Boolean =
        OwnerPolicyEffectGate(ownerPolicy).simulate(request(record)).allowed

    fun request(record: GeneratedToolRecord): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.PROVIDER_ACTIVATION,
        resource = "$RESOURCE_PREFIX${record.manifest.sourceCapability.value}:${record.manifest.toolId}",
        scope = scope,
        capabilityId = record.manifest.sourceCapability,
        providerVersion = record.manifest.buildHash ?: record.manifest.sourceHash,
    )

    companion object {
        const val RESOURCE_PREFIX = "generated-provider-restore:"
    }
}

/** Process-local pointer installed before kernel construction; it owns no policy state itself. */
object GeneratedProviderRestoreAuthorityRuntimeRegistry {
    @Volatile
    private var authority: GeneratedProviderRestoreAuthority? = null

    fun install(value: GeneratedProviderRestoreAuthority) {
        authority = value
    }

    fun current(): GeneratedProviderRestoreAuthority? = authority

    internal fun clearForTests() {
        authority = null
    }
}
