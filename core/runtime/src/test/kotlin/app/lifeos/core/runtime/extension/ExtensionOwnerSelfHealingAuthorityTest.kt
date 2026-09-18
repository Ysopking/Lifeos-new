package app.lifeos.core.runtime.extension

import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectKind
import app.lifeos.core.runtime.evolution.ControlledEvolutionSubjectRef
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.SelfHealingIncidentId
import app.lifeos.core.runtime.health.SelfHealingIncidentSnapshot
import app.lifeos.core.runtime.health.SelfHealingIncidentState
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertEquals

class ExtensionOwnerSelfHealingAuthorityTest {
    @Test
    fun hotSwapRequiresPromotionProofAndLiveOwnerPolicy() = runTest {
        val target = snapshot("candidate")
        val currentSnapshot = snapshot("current")
        val currentHead = ExtensionRegistryHead.create(1, currentSnapshot, null)
        val subject = ControlledEvolutionSubjectRef.create(
            kind = ControlledEvolutionSubjectKind.SEMANTIC_EXTENSION,
            candidateId = "candidate-1",
            sourceArtifactId = "artifact-1",
            validationBundleId = "validation-1",
            candidateFingerprint = "candidate-fingerprint",
        )
        val actor = OwnerActorId("extension-evolution")
        val now = Instant.parse("2026-09-18T12:00:00Z")
        val repository = InMemoryOwnerPolicyRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val grant = OwnerPolicyGrant.create(
            actorId = actor,
            effect = OwnerEffectType.PROVIDER_ACTIVATION,
            resource = OwnerResourceSelector(
                OwnerResourceSelectorType.EXACT,
                "extension-registry:${target.id}",
            ),
            scope = "lifeos-extension",
            validFrom = now.minusSeconds(60),
        )
        ledger.grant(grant)

        val authority = OwnerPolicyExtensionHotSwapAuthority(
            ownerPolicy = ledger,
            promotionProofs = ExtensionPromotionProofSource {
                ExtensionPromotionProof.create(
                    subjectId = it.id,
                    validationBundleId = it.validationBundleId,
                    holdoutEvidenceId = "holdout-1",
                    shadowEvidenceId = "shadow-1",
                    trialEvidenceId = "trial-1",
                )
            },
            actorId = actor,
            ownerScope = "lifeos-extension",
        )

        assertNotNull(authority.authorize(subject, currentHead, target))
        ledger.revoke(grant.id)
        assertNull(authority.authorize(subject, currentHead, target))
    }

    @Test
    fun rollbackProofMustComeFromInFlightDurableSelfHealingAction() {
        val incident = SelfHealingIncidentSnapshot(
            incidentId = SelfHealingIncidentId(
                SelfHealingIncidentId.PREFIX + "a".repeat(64)
            ),
            nodeId = HealthNodeId("extension-registry"),
            planFingerprint = "plan-v1",
            state = SelfHealingIncidentState.ACTION_IN_FLIGHT,
            nextActionIndex = 0,
            inFlightActionIndex = 0,
            inFlightActionId = "restore-extension-head",
            attemptedActionIds = listOf("restore-extension-head"),
            ledgerRevision = 2,
            lastRecordedAt = Instant.parse("2026-09-18T12:00:00Z"),
        )

        val proof = SelfHealingExtensionRollbackProof.fromIncident(
            incident = incident,
            restoreSnapshotId = "extension-registry:restore",
        )

        assertEquals(incident.incidentId.value, proof.incidentId)
        assertEquals("restore-extension-head", proof.actionId)
        assertEquals("extension-registry:restore", proof.restoreSnapshotId)
    }

    private fun snapshot(name: String): ExtensionRegistrySnapshot =
        ExtensionRegistrySnapshot.create(
            listOf(
                ExtensionRegistryEntry(
                    manifest = ExtensionManifest(
                        extensionId = ExtensionId("extension.$name"),
                        version = ExtensionVersion("1.0.0"),
                        kind = ExtensionKind.WORLD_SIGNAL_PACK,
                        providerId = "provider.$name",
                        entrypoints = setOf(
                            ExtensionEntrypoint("contract.$name", "implementation.$name")
                        ),
                    ),
                    worldContract = ExtensionWorldContract(
                        worldSignalSchemaVersion = WorldSignalSchemaVersion(1, 0),
                        worldNodeSchemaVersion = WorldNodeSchemaVersion(1, 0),
                        worldEquationVersion = WorldEquationVersion(
                            "lifeos-world-informational-v1",
                            1,
                            0,
                        ),
                        coefficientSchemaFingerprint = CoefficientSchemaFingerprint("coeff-v1"),
                        projectionContractFingerprint = ProjectionContractFingerprint("projection-v1"),
                    ),
                )
            )
        )

    private class InMemoryOwnerPolicyRepository : OwnerPolicyRepository {
        private val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport(): OwnerPolicyRepositoryLoadReport =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerPolicyEvent,
        ): Boolean {
            if (events.size.toLong() != expectedRevision) return false
            events += event
            return true
        }
    }
}
