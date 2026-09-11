package app.lifeos.core.runtime.capability

import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedProviderRestoreAuthorityTest {
    @Test
    fun `revocation survives reconstructed ledger and blocks provider restore exposure`() = runTest {
        val now = Instant.parse("2026-09-11T20:30:00Z")
        val policy = v14AllowProviderRestore(now)
        val record = activeRecord()
        var exposures = 0

        val beforeRestart = GeneratedProviderRestoreAuthority(
            ownerPolicy = policy.ledger,
            actorId = V14_TEST_OWNER,
            scope = V14_TEST_RESTORE_SCOPE,
        )
        assertTrue(beforeRestart.expose(record) { exposures += 1 })
        assertTrue(beforeRestart.allowedNow(record))
        assertTrue(exposures == 1)

        policy.ledger.revoke(policy.grant.id)

        // New ledger + new authority over the exact same durable repository models process restart.
        val reconstructedLedger = OwnerPolicyLedger(policy.repository) { now.plusSeconds(1) }
        val afterRestart = GeneratedProviderRestoreAuthority(
            ownerPolicy = reconstructedLedger,
            actorId = V14_TEST_OWNER,
            scope = V14_TEST_RESTORE_SCOPE,
        )

        assertFalse(afterRestart.allowedNow(record))
        assertFalse(afterRestart.expose(record) { exposures += 1 })
        assertTrue(exposures == 1)
    }

    private fun activeRecord(): GeneratedToolRecord = GeneratedToolRecord(
        manifest = GeneratedToolManifest(
            toolId = "provider-restore-v14",
            sourceCapability = CapabilityId("text.normalize"),
            sourceHash = "source-provider-restore-v14",
            buildHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            permissions = emptySet(),
            generatedAt = Instant.parse("2026-09-11T20:00:00Z"),
            requiredInputs = setOf("text"),
            requiredOutputs = setOf("normalized-text"),
        ),
        state = GeneratedToolState.ACTIVE,
        verificationConfidence = 1.0,
        promotionEvidenceId = "promotion-provider-restore-v14",
    )
}
