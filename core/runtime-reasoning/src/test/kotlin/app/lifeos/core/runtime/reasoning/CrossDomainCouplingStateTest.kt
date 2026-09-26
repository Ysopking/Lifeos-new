package app.lifeos.core.runtime.reasoning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CrossDomainCouplingStateTest {
    @Test
    fun `local domain ordering is canonical`() {
        val finance = local("finance", "predictive:finance", "law:finance")
        val sein = local("sein", "predictive:sein", "law:sein")
        val variable = CouplingInterfaceVariable.create(
            id = "stress-liquidity",
            participatingDomainIds = listOf("sein", "finance"),
            valueFingerprint = "value:1",
            evidenceFingerprint = "evidence:1",
        )

        val first = CrossDomainCouplingState.create(
            realizationProfileFingerprint = "profile:v2.2",
            localStates = listOf(sein, finance),
            interfaceVariables = listOf(variable),
        )
        val second = CrossDomainCouplingState.create(
            realizationProfileFingerprint = "profile:v2.2",
            localStates = listOf(finance, sein),
            interfaceVariables = listOf(variable),
        )

        assertEquals(first, second)
        assertEquals(listOf("finance", "sein"), first.localStates.map { it.domainId })
    }

    @Test
    fun `interface variables require evidence fingerprints`() {
        assertFailsWith<IllegalArgumentException> {
            CouplingInterfaceVariable.create(
                id = "stress-liquidity",
                participatingDomainIds = listOf("finance", "sein"),
                valueFingerprint = "value:1",
                evidenceFingerprint = "",
            )
        }
    }

    @Test
    fun `interface variables must couple at least two domains`() {
        assertFailsWith<IllegalArgumentException> {
            CouplingInterfaceVariable.create(
                id = "single-domain",
                participatingDomainIds = listOf("finance"),
                valueFingerprint = "value:1",
                evidenceFingerprint = "evidence:1",
            )
        }
    }

    @Test
    fun `interface cannot reference domain absent from local state`() {
        assertFailsWith<IllegalArgumentException> {
            CrossDomainCouplingState.create(
                realizationProfileFingerprint = "profile:v2.2",
                localStates = listOf(
                    local("finance", "p:finance", "l:finance"),
                    local("sein", "p:sein", "l:sein"),
                ),
                interfaceVariables = listOf(
                    CouplingInterfaceVariable.create(
                        id = "invalid",
                        participatingDomainIds = listOf("finance", "social"),
                        valueFingerprint = "value",
                        evidenceFingerprint = "evidence",
                    )
                ),
            )
        }
    }

    @Test
    fun `changing coupling evidence changes state identity`() {
        val states = listOf(
            local("finance", "p:finance", "l:finance"),
            local("sein", "p:sein", "l:sein"),
        )
        val first = CrossDomainCouplingState.create(
            "profile:v2.2",
            states,
            listOf(
                CouplingInterfaceVariable.create(
                    "stress-liquidity",
                    listOf("finance", "sein"),
                    "value",
                    "evidence:1",
                )
            ),
        )
        val second = CrossDomainCouplingState.create(
            "profile:v2.2",
            states,
            listOf(
                CouplingInterfaceVariable.create(
                    "stress-liquidity",
                    listOf("finance", "sein"),
                    "value",
                    "evidence:2",
                )
            ),
        )

        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `coupling state grants neither causal nor execution authority`() {
        val state = CrossDomainCouplingState.create(
            "profile:v2.2",
            listOf(
                local("finance", "p:finance", "l:finance"),
                local("sein", "p:sein", "l:sein"),
            ),
            emptyList(),
        )

        assertFalse(state.causalAuthority)
        assertFalse(state.executionAuthority)
    }

    private fun local(
        domainId: String,
        predictiveStateId: String,
        futureLawFingerprint: String,
    ) = DomainPredictiveStateRef(
        domainId = domainId,
        predictiveStateId = predictiveStateId,
        futureLawFingerprint = futureLawFingerprint,
    )
}
