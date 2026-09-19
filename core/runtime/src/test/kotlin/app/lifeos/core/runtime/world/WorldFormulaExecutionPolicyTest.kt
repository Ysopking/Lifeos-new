package app.lifeos.core.runtime.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorldFormulaExecutionPolicyTest {
    @Test
    fun nonProductiveScopeCannotGrantProductiveCommitAuthority() {
        assertFailsWith<IllegalArgumentException> {
            WorldFormulaExecutionPolicy(
                scope = WorldFormulaExecutionScope.RESOURCE,
                captureCognitiveSnapshots = false,
                emitCognitiveTriggers = false,
                productiveCommitAllowed = true,
            )
        }
    }


    @Test
    fun productiveScopeWithoutCommitAuthorityCannotCreateProductiveCandidateAuthority() {
        val policy = WorldFormulaExecutionPolicy(
            scope = WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE,
            captureCognitiveSnapshots = false,
            emitCognitiveTriggers = false,
            productiveCommitAllowed = false,
        )
        assertEquals(false, policy.productiveCommitAllowed)
    }

    @Test
    fun predefinedPoliciesKeepAuthoritySeparated() {
        assertEquals(
            WorldFormulaExecutionScope.PRODUCTIVE_COGNITIVE,
            WorldFormulaExecutionPolicy.PRODUCTIVE.scope,
        )
        assertEquals(false, WorldFormulaExecutionPolicy.RESOURCE.productiveCommitAllowed)
        assertEquals(false, WorldFormulaExecutionPolicy.SELF_OBSERVATION.emitCognitiveTriggers)
        assertEquals(false, WorldFormulaExecutionPolicy.SHADOW.captureCognitiveSnapshots)
        assertEquals(false, WorldFormulaExecutionPolicy.COUNTERFACTUAL.productiveCommitAllowed)
    }
}
