package app.lifeos.core.runtime.contracts

import app.lifeos.core.runtime.capability.ToolPermission
import app.lifeos.core.runtime.genesis.GenesisHandoff
import app.lifeos.core.runtime.genesis.GenesisHandoffTarget
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeBuildContractsTest {
    @Test
    fun `build handoff remains non activating`() {
        val handoff = GenesisHandoff(
            proposalId = "proposal-1",
            target = GenesisHandoffTarget.BUILD_STUDIO,
            referenceId = "module-proposal:1",
            payloadFingerprint = "fingerprint-1",
            requiresExplicitApproval = true,
        )
        assertFalse(handoff.activationAllowed)
        assertTrue(handoff.requiresExplicitApproval)
    }

    @Test
    fun `repository mutation permission remains explicit`() {
        assertTrue(ToolPermission.MODIFY_REPOSITORY in ToolPermission.entries)
    }
}
