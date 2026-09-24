package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityProviderCatalog
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.policy.OwnerEffectType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class AndroidCapabilityRiskClass {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class AndroidCapabilityReversibility {
    REVERSIBLE,
    COMPENSATABLE,
    IRREVERSIBLE,
}

enum class AndroidPermissionKind {
    RUNTIME_PERMISSION,
    SPECIAL_ACCESS,
    MANIFEST_DECLARATION,
}

data class AndroidPermissionRequirement(
    val kind: AndroidPermissionKind,
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "Android permission requirement must not be blank" }
        require('\r' !in name && '\n' !in name) {
            "Android permission requirement must be single-line"
        }
    }

    val key: String = kind.name + ":" + name
}

enum class AndroidRecoverySemantics {
    NONE,
    RETRY_SAFE,
    IDEMPOTENT_REPLAY,
    COMPENSATING_ACTION,
    MANUAL_REVIEW,
}

data class AndroidCapabilityBinding(
    val descriptor: CapabilityDescriptor,
    val providerVersion: String,
    val requiredOwnerEffect: OwnerEffectType?,
    val ownerScope: String,
    val permissions: List<AndroidPermissionRequirement>,
    val riskClass: AndroidCapabilityRiskClass,
    val reversibility: AndroidCapabilityReversibility,
    val recoverySemantics: AndroidRecoverySemantics,
    val expectedOutcomeContract: String,
) {
    init {
        require(providerVersion.isNotBlank())
        require(ownerScope.isNotBlank())
        require(expectedOutcomeContract.isNotBlank())
        require(
            permissions == permissions
                .distinctBy { it.key }
                .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
        ) {
            "Android capability permissions must be distinct and canonical"
        }
        if (reversibility == AndroidCapabilityReversibility.IRREVERSIBLE) {
            require(riskClass != AndroidCapabilityRiskClass.LOW) {
                "Irreversible Android capability cannot be LOW risk"
            }
        }
    }

    val capabilityId: CapabilityId
        get() = descriptor.capabilityId

    val providerId: String
        get() = descriptor.providerId

    fun matches(descriptor: CapabilityDescriptor): Boolean =
        capabilityId == descriptor.capabilityId &&
            providerId == descriptor.providerId &&
            this.descriptor.providerType == descriptor.providerType &&
            this.descriptor.contract == descriptor.contract

    val executionAuthority: Boolean
        get() = false

    val permissionGrantAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false

    fun fingerprint(): String = androidCapabilityFingerprint(
        "android-capability-binding/v1",
        descriptor.capabilityId.value,
        descriptor.providerId,
        providerVersion,
        descriptor.providerType.name,
        descriptor.contract.requiredInputs.sorted().joinToString("\u001f"),
        descriptor.contract.outputs.sorted().joinToString("\u001f"),
        requiredOwnerEffect?.name.orEmpty(),
        ownerScope,
        permissions.joinToString("\u001f") { it.key },
        riskClass.name,
        reversibility.name,
        recoverySemantics.name,
        expectedOutcomeContract,
    )
}

data class AndroidCapabilityRequest(
    val capabilityId: CapabilityId,
    val availableInputs: Set<String>,
    val requiredOutputs: Set<String>,
    val resource: String,
    val scope: String,
    val providerId: String? = null,
) {
    init {
        require(availableInputs.none { it.isBlank() })
        require(requiredOutputs.none { it.isBlank() })
        require(resource.isNotBlank())
        require(scope.isNotBlank())
        require(providerId == null || providerId.isNotBlank())
    }

    fun fingerprint(): String = androidCapabilityFingerprint(
        "android-capability-request/v1",
        capabilityId.value,
        availableInputs.sorted().joinToString("\u001f"),
        requiredOutputs.sorted().joinToString("\u001f"),
        resource,
        scope,
        providerId.orEmpty(),
    )
}

data class AndroidPermissionSnapshot(
    val granted: Set<AndroidPermissionRequirement>,
) {
    init {
        require(granted.map { it.key }.distinct().size == granted.size)
    }

    fun missing(
        required: Collection<AndroidPermissionRequirement>,
    ): List<AndroidPermissionRequirement> =
        required.filterNot(granted::contains)
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
}

data class AndroidCapabilityDispatchPlan(
    val requestFingerprint: String,
    val binding: AndroidCapabilityBinding,
) {
    init {
        require(requestFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    val capabilityId: CapabilityId
        get() = binding.capabilityId

    val providerId: String
        get() = binding.providerId

    val executionAuthority: Boolean
        get() = false

    val permissionGrantAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false

    fun fingerprint(): String = androidCapabilityFingerprint(
        "android-capability-dispatch-plan/v1",
        requestFingerprint,
        binding.fingerprint(),
    )
}

sealed interface AndroidCapabilityResolution {
    data class Ready(
        val plan: AndroidCapabilityDispatchPlan,
    ) : AndroidCapabilityResolution

    data class CapabilityUnavailable(
        val capabilityId: CapabilityId,
        val candidateProviderIds: List<String>,
    ) : AndroidCapabilityResolution {
        init {
            require(candidateProviderIds == candidateProviderIds.distinct().sorted())
        }
    }

    data class ContractMismatch(
        val capabilityId: CapabilityId,
        val candidateProviderIds: List<String>,
    ) : AndroidCapabilityResolution {
        init {
            require(candidateProviderIds == candidateProviderIds.distinct().sorted())
            require(candidateProviderIds.isNotEmpty())
        }
    }

    data class PermissionsMissing(
        val binding: AndroidCapabilityBinding,
        val missing: List<AndroidPermissionRequirement>,
    ) : AndroidCapabilityResolution {
        init {
            require(missing.isNotEmpty())
            require(
                missing == missing.distinctBy { it.key }
                    .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
            )
        }
    }
}

/**
 * B405 deterministic Android capability routing.
 *
 * The bus resolves already-registered capability providers into a typed dispatch plan. It performs
 * no Android API call, no permission request, no Owner Policy decision and no side effect. B406+
 * action runtimes must compose the returned plan with the existing JIT OwnerPolicyEffectGate and
 * platform permission checks at the exact productive exposure boundary.
 */
class AndroidCapabilityBus(
    private val providerCatalog: CapabilityProviderCatalog,
    bindings: Collection<AndroidCapabilityBinding>,
) {
    private val bindingsByProvider = bindings
        .associateBy { it.capabilityId to it.providerId }
        .also { indexed ->
            require(indexed.size == bindings.size) {
                "Android capability bindings must be unique per capability/provider"
            }
        }

    suspend fun resolve(
        request: AndroidCapabilityRequest,
        permissions: AndroidPermissionSnapshot,
    ): AndroidCapabilityResolution {
        val providers = providerCatalog.providersFor(
            capabilityId = request.capabilityId,
            includeUnavailable = false,
        )
        val requestedProviders = providers
            .filter { request.providerId == null || it.providerId == request.providerId }

        if (requestedProviders.isEmpty()) {
            return AndroidCapabilityResolution.CapabilityUnavailable(
                capabilityId = request.capabilityId,
                candidateProviderIds = providers.map { it.providerId }.distinct().sorted(),
            )
        }

        val contractCompatible = requestedProviders.filter { descriptor ->
            request.availableInputs.containsAll(descriptor.contract.requiredInputs) &&
                descriptor.contract.outputs.containsAll(request.requiredOutputs)
        }
        if (contractCompatible.isEmpty()) {
            return AndroidCapabilityResolution.ContractMismatch(
                capabilityId = request.capabilityId,
                candidateProviderIds = requestedProviders.map { it.providerId }.distinct().sorted(),
            )
        }

        val binding = contractCompatible
            .asSequence()
            .mapNotNull { descriptor ->
                bindingsByProvider[descriptor.capabilityId to descriptor.providerId]
                    ?.takeIf { it.matches(descriptor) }
            }
            .firstOrNull()
            ?: return AndroidCapabilityResolution.CapabilityUnavailable(
                capabilityId = request.capabilityId,
                candidateProviderIds = contractCompatible.map { it.providerId }.distinct().sorted(),
            )

        val missing = permissions.missing(binding.permissions)
        if (missing.isNotEmpty()) {
            return AndroidCapabilityResolution.PermissionsMissing(
                binding = binding,
                missing = missing,
            )
        }

        return AndroidCapabilityResolution.Ready(
            AndroidCapabilityDispatchPlan(
                requestFingerprint = request.fingerprint(),
                binding = binding,
            )
        )
    }
}

private fun androidCapabilityFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}


// ---- B464A Explicit App Capability Discovery ----

enum class AppCapabilityInterfaceKind(val stabilityRank: Int) {
    OFFICIAL_API(8),
    CONTENT_PROVIDER(7),
    INTENT(6),
    DEEP_LINK(5),
    SHARE_TARGET(4),
    NOTIFICATION_ACTION(3),
    ACCESSIBILITY_SEMANTIC(2),
    VISUAL_INTERACTION(1),
    COORDINATE_AUTOMATION(0),
}

data class AppSurfaceEvidence(
    val providerId: String,
    val providerVersion: String,
    val interfaceKind: AppCapabilityInterfaceKind,
    val surfaceKey: String,
    val semanticContracts: Set<String>,
    val sourceFingerprint: String,
) {
    init {
        require(providerId.isNotBlank())
        require(providerVersion.isNotBlank())
        require(surfaceKey.isNotBlank())
        require(semanticContracts.isNotEmpty())
        require(semanticContracts.none { it.isBlank() })
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "app-surface-evidence/v1",
        providerId,
        providerVersion,
        interfaceKind.name,
        surfaceKey,
        semanticContracts.sorted().joinToString("\u001f"),
        sourceFingerprint,
    )

    val platformInspectionAuthority: Boolean
        get() = false
}

data class AppCapabilityDiscoveryRule(
    val ruleId: String,
    val capabilityId: CapabilityId,
    val acceptedInterfaces: Set<AppCapabilityInterfaceKind>,
    val requiredSemanticContracts: Set<String>,
    val capabilityContract: CapabilityContract,
    val reliability: Double,
    val cost: Double,
) {
    init {
        require(ruleId.isNotBlank())
        require(acceptedInterfaces.isNotEmpty())
        require(requiredSemanticContracts.isNotEmpty())
        require(requiredSemanticContracts.none { it.isBlank() })
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(cost.isFinite() && cost >= 0.0)
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "app-capability-discovery-rule/v1",
        ruleId,
        capabilityId.value,
        acceptedInterfaces.map { it.name }.sorted().joinToString("\u001f"),
        requiredSemanticContracts.sorted().joinToString("\u001f"),
        capabilityContract.requiredInputs.sorted().joinToString("\u001f"),
        capabilityContract.outputs.sorted().joinToString("\u001f"),
        java.lang.Double.toHexString(reliability),
        java.lang.Double.toHexString(cost),
    )
}

data class UniversalAppCapabilityCandidate(
    val capabilityId: CapabilityId,
    val providerId: String,
    val providerVersion: String,
    val interfaceKind: AppCapabilityInterfaceKind,
    val contract: CapabilityContract,
    val sourceFingerprint: String,
    val discoveryRuleFingerprint: String,
    val reliability: Double,
    val cost: Double,
) {
    init {
        require(providerId.isNotBlank())
        require(providerVersion.isNotBlank())
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(discoveryRuleFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(reliability.isFinite() && reliability in 0.0..1.0)
        require(cost.isFinite() && cost >= 0.0)
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "universal-app-capability-candidate/v1",
        capabilityId.value,
        providerId,
        providerVersion,
        interfaceKind.name,
        contract.requiredInputs.sorted().joinToString("\u001f"),
        contract.outputs.sorted().joinToString("\u001f"),
        sourceFingerprint,
        discoveryRuleFingerprint,
        java.lang.Double.toHexString(reliability),
        java.lang.Double.toHexString(cost),
    )

    val activationAuthority: Boolean
        get() = false
    val executionAuthority: Boolean
        get() = false
    val ownerPolicyAuthority: Boolean
        get() = false
}

/**
 * Pure classifier over already-authorized surface evidence. It does not enumerate apps, request
 * permissions or activate providers. A rule match creates a SHADOW candidate only.
 */
class AppCapabilityDiscoveryClassifier(
    rules: Collection<AppCapabilityDiscoveryRule>,
) {
    private val rules = rules.sortedBy { it.ruleId }.also { canonical ->
        require(canonical.map { it.ruleId }.distinct().size == canonical.size) {
            "App capability discovery rule ids must be unique"
        }
    }

    fun classify(
        evidence: Collection<AppSurfaceEvidence>,
    ): List<UniversalAppCapabilityCandidate> =
        evidence
            .sortedWith(
                compareBy<AppSurfaceEvidence> { it.providerId }
                    .thenByDescending { it.interfaceKind.stabilityRank }
                    .thenBy { it.surfaceKey }
                    .thenBy { it.fingerprint }
            )
            .flatMap { surface ->
                rules.asSequence()
                    .filter { surface.interfaceKind in it.acceptedInterfaces }
                    .filter {
                        surface.semanticContracts.containsAll(
                            it.requiredSemanticContracts
                        )
                    }
                    .map { rule ->
                        UniversalAppCapabilityCandidate(
                            capabilityId = rule.capabilityId,
                            providerId = surface.providerId,
                            providerVersion = surface.providerVersion,
                            interfaceKind = surface.interfaceKind,
                            contract = rule.capabilityContract,
                            sourceFingerprint = androidCapabilityFingerprint(
                                "app-capability-discovery-source/v1",
                                surface.fingerprint,
                                rule.fingerprint,
                            ),
                            discoveryRuleFingerprint = rule.fingerprint,
                            reliability = rule.reliability,
                            cost = rule.cost,
                        )
                    }
                    .toList()
            }
            .distinctBy { it.fingerprint }
            .sortedWith(
                compareBy<UniversalAppCapabilityCandidate> {
                    it.capabilityId.value
                }.thenByDescending { it.interfaceKind.stabilityRank }
                    .thenBy { it.providerId }
                    .thenBy { it.fingerprint }
            )
}


// ---- B464B Canonical App Capability Registry Adapter ----

data class AppCapabilityValidationEvidence(
    val candidateFingerprint: String,
    val shadowTestFingerprint: String,
    val contractTestFingerprint: String,
    val validatedProviderVersion: String,
) {
    init {
        listOf(
            candidateFingerprint,
            shadowTestFingerprint,
            contractTestFingerprint,
        ).forEach {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "App capability validation fingerprints must be SHA-256"
            }
        }
        require(validatedProviderVersion.isNotBlank())
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "app-capability-validation-evidence/v1",
        candidateFingerprint,
        shadowTestFingerprint,
        contractTestFingerprint,
        validatedProviderVersion,
    )

    val effectAuthority: Boolean
        get() = false
}

/**
 * Uses the existing canonical CapabilityRegistry. It owns no second registry and cannot grant an
 * effect. Productive use still passes platform permission and OwnerPolicy effect gates.
 */
class UniversalAppCapabilityRegistryAdapter(
    private val registry: CapabilityRegistry,
) {
    suspend fun promoteValidated(
        candidate: UniversalAppCapabilityCandidate,
        evidence: AppCapabilityValidationEvidence,
    ): CapabilityDescriptor {
        require(evidence.candidateFingerprint == candidate.fingerprint) {
            "App capability validation evidence is bound to another candidate"
        }
        require(evidence.validatedProviderVersion == candidate.providerVersion) {
            "App capability provider version changed after validation"
        }

        val descriptor = CapabilityDescriptor(
            capabilityId = candidate.capabilityId,
            providerId = candidate.providerId,
            providerType = ProviderType.CONNECTOR,
            contract = candidate.contract,
            state = ProviderState.ACTIVE,
            trustLevel = when (candidate.interfaceKind) {
                AppCapabilityInterfaceKind.OFFICIAL_API,
                AppCapabilityInterfaceKind.CONTENT_PROVIDER,
                -> TrustLevel.MEDIUM

                AppCapabilityInterfaceKind.INTENT,
                AppCapabilityInterfaceKind.DEEP_LINK,
                AppCapabilityInterfaceKind.SHARE_TARGET,
                AppCapabilityInterfaceKind.NOTIFICATION_ACTION,
                AppCapabilityInterfaceKind.ACCESSIBILITY_SEMANTIC,
                AppCapabilityInterfaceKind.VISUAL_INTERACTION,
                AppCapabilityInterfaceKind.COORDINATE_AUTOMATION,
                -> TrustLevel.LOW
            },
            reliability = candidate.reliability,
            cost = candidate.cost,
        )
        return registry.register(descriptor)
    }

    suspend fun current(
        capabilityId: CapabilityId,
    ): List<CapabilityDescriptor> =
        registry.providersFor(
            capabilityId = capabilityId,
            includeUnavailable = true,
        )
}
