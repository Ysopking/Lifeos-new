package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

data class DomainPredictiveStateRef(
    val domainId: String,
    val predictiveStateId: String,
    val futureLawFingerprint: String,
) {
    init {
        require(domainId.isNotBlank())
        require(predictiveStateId.isNotBlank())
        require(futureLawFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "domain-predictive-state-ref/v1",
        domainId,
        predictiveStateId,
        futureLawFingerprint,
    )
}

data class CouplingInterfaceVariable private constructor(
    val id: String,
    val participatingDomainIds: List<String>,
    val valueFingerprint: String,
    val evidenceFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(id.isNotBlank())
        require(participatingDomainIds.size >= 2)
        require(participatingDomainIds.none { it.isBlank() })
        require(participatingDomainIds == participatingDomainIds.distinct().sorted())
        require(valueFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
        require(
            fingerprint == expectedFingerprint(
                id,
                participatingDomainIds,
                valueFingerprint,
                evidenceFingerprint,
            )
        )
    }

    companion object {
        fun create(
            id: String,
            participatingDomainIds: Collection<String>,
            valueFingerprint: String,
            evidenceFingerprint: String,
        ): CouplingInterfaceVariable {
            val domains = participatingDomainIds.distinct().sorted()
            require(id.isNotBlank())
            require(domains.size >= 2)
            require(domains.none { it.isBlank() })
            require(valueFingerprint.isNotBlank())
            require(evidenceFingerprint.isNotBlank())
            return CouplingInterfaceVariable(
                id = id,
                participatingDomainIds = domains,
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
            participatingDomainIds: List<String>,
            valueFingerprint: String,
            evidenceFingerprint: String,
        ): String = StableFieldIds.fingerprint(
            "coupling-interface-variable/v1",
            id,
            valueFingerprint,
            evidenceFingerprint,
            *participatingDomainIds.map { "domain:$it" }.toTypedArray(),
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
        require(localStates.size >= 2)
        require(localStates == localStates.sortedBy { it.domainId })
        require(localStates.map { it.domainId }.distinct().size == localStates.size)
        require(interfaceVariables == interfaceVariables.sortedBy { it.id })
        require(interfaceVariables.map { it.id }.distinct().size == interfaceVariables.size)

        val localDomainIds = localStates.mapTo(sortedSetOf()) { it.domainId }
        require(
            interfaceVariables.all { variable ->
                variable.participatingDomainIds.all { it in localDomainIds }
            }
        ) {
            "Cross-domain interface variables may reference only local-state domains"
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
            require(realizationProfileFingerprint.isNotBlank())
            val states = localStates.sortedBy { it.domainId }
            val interfaces = interfaceVariables.sortedBy { it.id }
            require(states.size >= 2)
            require(states.map { it.domainId }.distinct().size == states.size)
            require(interfaces.map { it.id }.distinct().size == interfaces.size)

            return CrossDomainCouplingState(
                realizationProfileFingerprint = realizationProfileFingerprint,
                localStates = states,
                interfaceVariables = interfaces,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint,
                    states,
                    interfaces,
                ),
            )
        }

        private fun expectedFingerprint(
            realizationProfileFingerprint: String,
            localStates: List<DomainPredictiveStateRef>,
            interfaceVariables: List<CouplingInterfaceVariable>,
        ): String = StableFieldIds.fingerprint(
            "cross-domain-coupling-state/v1",
            realizationProfileFingerprint,
            *localStates.map { "local:${it.fingerprint()}" }.toTypedArray(),
            *interfaceVariables.map { "interface:${it.fingerprint}" }.toTypedArray(),
        )
    }
}
