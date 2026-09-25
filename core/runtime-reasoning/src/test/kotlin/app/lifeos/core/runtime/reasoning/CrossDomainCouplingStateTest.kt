package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class CrossDomainCouplingStateTest {
    private val finance = StableFieldIds.domain("finance")
    private val goals = StableFieldIds.domain("goals")
    private val social = StableFieldIds.domain("social")

    @Test
    fun `local domain ordering is canonical`() {
        val first = CrossDomainCouplingState.create(
            realizationProfileFingerprint = "profile:v2.2",
            localStates = listOf(local(goals), local(finance)),
        )
        val second = CrossDomainCouplingState.create(
            realizationProfileFingerprint = "profile:v2.2",
            localStates = listOf(local(finance), local(goals)),
        )

        assertEquals(first, second)
        assertEquals(
            listOf(finance.value, goals.value).sorted(),
            first.localStates.map { it.domainId.value },
        )
    }

    @Test
    fun `evidenced interface variable changes coupling-state identity`() {
        val locals = listOf(local(finance), local(goals))
        val uncoupled = CrossDomainCouplingState.create(
            "profile:v2.2",
            locals,
        )
        val coupled = CrossDomainCouplingState.create(
            "profile:v2.2",
            locals,
            listOf(
                CouplingInterfaceVariable.create(
                    id = "cashflow-goal-pressure",
                    participatingDomains = listOf(goals, finance),
                    valueFingerprint = "value:pressure",
                    evidenceFingerprint = "evidence:episode-7",
                )
            ),
        )

        assertNotEquals(uncoupled.fingerprint, coupled.fingerprint)
    }

    @Test
    fun `interface variable cannot be created without evidence provenance`() {
        assertFailsWith<IllegalArgumentException> {
            CouplingInterfaceVariable.create(
                id = "unsupported-coupling",
                participatingDomains = listOf(finance, goals),
                valueFingerprint = "value",
                evidenceFingerprint = "",
            )
        }
    }

    @Test
    fun `interface variable cannot reference absent local domain`() {
        val interfaceVariable = CouplingInterfaceVariable.create(
            id = "three-domain-link",
            participatingDomains = listOf(finance, social),
            valueFingerprint = "value",
            evidenceFingerprint = "evidence",
        )

        assertFailsWith<IllegalArgumentException> {
            CrossDomainCouplingState.create(
                realizationProfileFingerprint = "profile:v2.2",
                localStates = listOf(local(finance), local(goals)),
                interfaceVariables = listOf(interfaceVariable),
            )
        }
    }

    @Test
    fun `duplicate local domain states are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CrossDomainCouplingState.create(
                realizationProfileFingerprint = "profile:v2.2",
                localStates = listOf(
                    local(finance, "predictive:a"),
                    local(finance, "predictive:b"),
                ),
            )
        }
    }

    @Test
    fun `interface domain ordering cannot change identity`() {
        val first = CouplingInterfaceVariable.create(
            id = "shared",
            participatingDomains = listOf(finance, goals),
            valueFingerprint = "value",
            evidenceFingerprint = "evidence",
        )
        val second = CouplingInterfaceVariable.create(
            id = "shared",
            participatingDomains = listOf(goals, finance),
            valueFingerprint = "value",
            evidenceFingerprint = "evidence",
        )

        assertEquals(first, second)
    }

    @Test
    fun `cross domain coupling state grants no truth causal or execution authority`() {
        val state = CrossDomainCouplingState.create(
            realizationProfileFingerprint = "profile:v2.2",
            localStates = listOf(local(finance), local(goals)),
        )

        assertFalse(state.truthAuthority)
        assertFalse(state.causalAuthority)
        assertFalse(state.executionAuthority)
    }

    private fun local(
        domain: app.lifeos.core.field.FieldDomainId,
        predictiveId: String = "predictive:${domain.value}",
    ) = DomainPredictiveStateRef(
        domainId = domain,
        predictiveStateId = predictiveId,
        futureLawFingerprint = "law:${domain.value}",
    )
}
