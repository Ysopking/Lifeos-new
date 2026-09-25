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
        require(participatingDomains.size >= 2) {
            "Cross-domain interface variable requires at least two domains"
        }
        require(
            participatingDomains ==
                participatingDomains.distinct().sortedBy { it.value }
        ) {
            "Cross-domain interface domains must be unique and canonical"
        }
        require(valueFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank()) {
            "Cross-domain coupling cannot exist without evidence provenance"
        }
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
            participatingDomains: List<FieldDomainId>,
            valueFingerprint: String,
            evidenceFingerprint: String,
        ): String = StableFieldIds.fingerprint(
            "coupling-interface-variable/v1",
            id,
            valueFingerprint,
            evidenceFingerprint,
            *participatingDomains.map { "domain:${it.value}" }.toTypedArray(),
        )
    }
}

/**
 * B523 M7 state: local predictive states plus explicitly evidenced cross-domain interface
 * variables. It records coupling hypotheses/evidence but cannot infer causation or authorize
 * effects.
 */
data class CrossDomainCouplingState private constructor(
    val realizationProfileFingerprint: String,
    val localStates: List<DomainPredictiveStateRef>,
    val interfaceVariables: List<CouplingInterfaceVariable>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(localStates.size >= 2) {
            "Cross-domain coupling state requires at least two local domains"
        }
        require(localStates == localStates.sortedBy { it.domainId.value })
        require(localStates.map { it.domainId }.distinct().size == localStates.size) {
            "Cross-domain coupling state allows one predictive state per domain"
        }
        require(interfaceVariables == interfaceVariables.sortedBy { it.id })
        require(interfaceVariables.map { it.id }.distinct().size == interfaceVariables.size) {
            "Cross-domain interface variable ids must be unique"
        }

        val localDomains = localStates.mapTo(hashSetOf()) { it.domainId }
        require(
            interfaceVariables.all { variable ->
                variable.participatingDomains.all(localDomains::contains)
            }
        ) {
            "Cross-domain interface variable references a domain without local predictive state"
        }
        require(
            fingerprint == expectedFingerprint(
                realizationProfileFingerprint,
                localStates,
                interfaceVariables,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val causalAuthority: Boolean
        get() = false

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            realizationProfileFingerprint: String,
            localStates: Collection<DomainPredictiveStateRef>,
            interfaceVariables: Collection<CouplingInterfaceVariable> = emptyList(),
        ): CrossDomainCouplingState {
            val locals = localStates.sortedBy { it.domainId.value }
            val interfaces = interfaceVariables.sortedBy { it.id }
            require(realizationProfileFingerprint.isNotBlank())
            require(locals.size >= 2)
            require(locals.map { it.domainId }.distinct().size == locals.size)
            require(interfaces.map { it.id }.distinct().size == interfaces.size)
            val localDomains = locals.mapTo(hashSetOf()) { it.domainId }
            require(
                interfaces.all { variable ->
                    variable.participatingDomains.all(localDomains::contains)
                }
            )
            return CrossDomainCouplingState(
                realizationProfileFingerprint = realizationProfileFingerprint,
                localStates = locals,
                interfaceVariables = interfaces,
                fingerprint = expectedFingerprint(
                    realizationProfileFingerprint,
                    locals,
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
