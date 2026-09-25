package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CrossDomainCouplingStateTest {
    private val finance = StableFieldIds.domain("finance")
    private val social = StableFieldIds.domain("social")
    private val time = StableFieldIds.domain("time")

    @Test
    fun `state canonicalizes domain and interface ordering`() {
        val interfaceVariable = CouplingInterfaceVariable.create(
            id = "cashflow-time-pressure",
            participatingDomains = listOf(time, finance),
            valueFingerprint = "value:1",
            evidenceFingerprint = "evidence:1",
        )

        val state = CrossDomainCouplingState.create(
            realizationProfileFingerprint = "profile:v2.2",
            localStates = listOf(
                local(social, "social:state"),
                local(finance, "finance:state"),
                local(time, "time:state"),
            ),
            interfaceVariables = listOf(interfaceVariable),
        )

        assertEquals(
            listOf(finance, social, time).sortedBy { it.value },
            state.localStates.map { it.domainId },
        )
        assertEquals(listOf(finance, time).sortedBy { it.value }, interfaceVariable.participatingDomains)
    }

    @Test
    fun `interface variable requires evidence binding`() {
        assertFailsWith<IllegalArgumentException> {
            CouplingInterfaceVariable.create(
                id = "unsupported",
                participatingDomains = listOf(finance, social),
                valueFingerprint = "value",
                evidenceFingerprint = "",
            )
        }
    }

    @Test
    fun `single-domain pseudo coupling is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CouplingInterfaceVariable.create(
                id = "invalid",
                participatingDomains = listOf(finance),
                valueFingerprint = "value",
                evidenceFingerprint = "evidence",
            )
        }
    }

    @Test
    fun `duplicate domain states are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CrossDomainCouplingState.create(
                realizationProfileFingerprint = "profile:v2.2",
                localStates = listOf(
                    local(finance, "a"),
                    local(finance, "b"),
                ),
                interfaceVariables = emptyList(),
            )
        }
    }

    @Test
    fun `interface cannot reference domain absent from local state set`() {
        assertFailsWith<IllegalArgumentException> {
            CrossDomainCouplingState.create(
                realizationProfileFingerprint = "profile:v2.2",
                localStates = listOf(local(finance, "f"), local(social, "s")),
                interfaceVariables = listOf(
                    CouplingInterfaceVariable.create(
                        id = "invalid",
                        participatingDomains = listOf(finance, time),
                        valueFingerprint = "value",
                        evidenceFingerprint = "evidence",
                    )
                ),
            )
        }
    }

    @Test
    fun `coupling evidence changes state identity`() {
        val local = listOf(local(finance, "f"), local(social, "s"))
        val first = CrossDomainCouplingState.create(
            "profile:v2.2",
            local,
            listOf(
                CouplingInterfaceVariable.create(
                    "relation",
                    listOf(finance, social),
                    "value",
                    "evidence:a",
                )
            ),
        )
        val second = CrossDomainCouplingState.create(
            "profile:v2.2",
            local,
            listOf(
                CouplingInterfaceVariable.create(
                    "relation",
                    listOf(finance, social),
                    "value",
                    "evidence:b",
                )
            ),
        )

        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `cross-domain coupling state grants no causal or execution authority`() {
        val state = CrossDomainCouplingState.create(
            "profile:v2.2",
            listOf(local(finance, "f"), local(social, "s")),
            emptyList(),
        )

        assertFalse(state.causalAuthority)
        assertFalse(state.executionAuthority)
    }

    private fun local(
        domain: app.lifeos.core.field.FieldDomainId,
        predictiveStateId: String,
    ) = DomainPredictiveStateRef(
        domainId = domain,
        predictiveStateId = predictiveStateId,
        futureLawFingerprint = "law:$predictiveStateId",
    )
}
