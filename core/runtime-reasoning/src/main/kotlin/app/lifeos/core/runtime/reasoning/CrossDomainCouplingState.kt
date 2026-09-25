package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.field.StableFieldIds

data class DomainPredictiveStateRef(
    val domainId: FieldDomainId,
    val predictiveStateId: String,
    val futureLawFingerprint: String,
) {
    init {
        require(predictiveStateId.isNotBlank())
        require(futureLawFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "domain-predictive-state-ref/v1",
        domainId.value,
        predictiveStateId,
        futureLawFingerprint,
    )
}

data class CouplingInterfaceVariable private constructor(
    val id: String,
    val participatingDomains: List<FieldDomainId>,
    val valueFingerprint: String,
    val evidenceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(participatingDomains.size >= 2)
        require(
            participatingDomains ==
                participatingDomains.distinct().sortedBy { it.value }
        )
        require(valueFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
        require(
            fingerprint == expectedFingerprint(
                id,
                participatingDomains,
                valueFingerprint,
                evidenceFingerprint,
            )
        )
    }

    companion object {
        fun create(
            id: String,
            participatingDomains: Collection<FieldDomainId>,
            valueFingerprint: String,
            evidenceFingerprint: String,
        ): CouplingInterfaceVariable {
            val domains = participatingDomains.distinct().sortedBy { it.value }
            require(id.isNotBlank())
            require(domains.size >= 2)
            require(valueFingerprint.isNotBlank())
            require(evidenceFingerprint.isNotBlank())
            return CouplingInterfaceVariable(
                id = id,
                participatingDomains = domains,
                valueFingerprint = valueFingerprint,
                evidenceFingerprint = evidenceFingerprint,
                fingerprint = expectedFingerprint(
                    id,
                    domains,
                    valueFingerprint,
                    evidenceFingerprint,
                ),
            )
        }

        private fun expectedFingerprint(
            id: String,
            domains: List<FieldDomainId>,
            valueFingerprint: String,
            evidenceFingerprint: String,
        ): String = StableFieldIds.fingerprint(
            "coupling-interface-variable/v1",
            id,
            valueFingerprint,
            evidenceFingerprint,
            *domains.map { "domain:${it.value}" }.toTypedArray(),
        )
    }
}

data class CrossDomainCouplingState private constructor(
    val realizationProfileFingerprint: String,
    val localStates: List<DomainPredictiveStateRef>,
    val interfaceVariables: List<CouplingInterfaceVariable>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(localStates.size >= 2) {
            "Cross-domain coupling requires at least two domain states"
        }
        require(localStates == localStates.sortedBy { it.domainId.value })
        require(localStates.map { it.domainId }.distinct().size == localStates.size)
        require(interfaceVariables == interfaceVariables.sortedBy { it.id })
        require(interfaceVariables.map { it.id }.distinct().size == interfaceVariables.size)

        val domains = localStates.mapTo(hashSetOf()) { it.domainId }
        require(
            interfaceVariables.all { variable ->
                variable.participatingDomains.all(domains::contains)
            }
        ) {
            "Coupling interface variables may reference only represented domains"
        }
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint,
                localStates,
                interfaceVariables,
            )
        )
    }

    val causalAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            localStates: Collection<DomainPredictiveStateRef>,
            interfaceVariables: Collection<CouplingInterfaceVariable>,
        ): CrossDomainCouplingState {
            val local = localStates.sortedBy { it.domainId.value }
            val interfaces = interfaceVariables.sortedBy { it.id }
            require(realizationProfileFingerprint.isNotBlank())
            require(local.size >= 2)
            require(local.map { it.domainId }.distinct().size == local.size)
            require(interfaces.map { it.id }.distinct().size == interfaces.size)
            return CrossDomainCouplingState(
                realizationProfileFingerprint = realizationProfileFingerprint,
                localStates = local,
                interfaceVariables = interfaces,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint,
                    local,
                    interfaces,
                ),
            )
        }

        private fun expectedFingerprint(
            profile: String,
            localStates: List<DomainPredictiveStateRef>,
            interfaces: List<CouplingInterfaceVariable>,
        ): String = StableFieldIds.fingerprint(
            "cross-domain-coupling-state/v1",
            profile,
            *localStates.map { "local:${it.fingerprint()}" }.toTypedArray(),
            *interfaces.map { "interface:${it.fingerprint}" }.toTypedArray(),
        )
    }
}
