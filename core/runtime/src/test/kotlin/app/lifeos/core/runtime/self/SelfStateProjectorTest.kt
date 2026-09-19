package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonIndexReport
import app.lifeos.core.model.PhotonRevisionRef
import app.lifeos.core.runtime.CognitiveSnapshot
import app.lifeos.core.runtime.health.HealthNode
import app.lifeos.core.runtime.health.HealthNodeId
import app.lifeos.core.runtime.health.HealthScope
import app.lifeos.core.runtime.health.HealthSnapshot
import app.lifeos.core.runtime.health.HealthState
import app.lifeos.core.runtime.resource.HardwareStateSnapshot
import app.lifeos.core.runtime.resource.HardwareThermalState
import app.lifeos.core.runtime.topology.LifeOsRuntimeTopologySnapshot
import app.lifeos.core.runtime.topology.LifeOsSubsystemDescriptor
import app.lifeos.core.runtime.topology.LifeOsSubsystemState
import app.lifeos.core.runtime.topology.LifeOsSubsystemStatus
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.WorldEquationHead
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRef
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelfStateProjectorTest {
    private val projector = SelfStateProjector()
    private val now = Instant.parse("2026-09-19T12:00:00Z")

    @Test
    fun projectorReadsExactWorldAndEquationHeads() {
        val cycleId = CognitiveCycleId("cycle:self-test")
        val ref = WorldFormulaSnapshotRef(
            namespace = WorldFormulaSnapshotNamespace.PRODUCTIVE,
            snapshotId = "world-snapshot-3",
            equationVersion = "eq-v2",
            cycleId = cycleId,
        )
        val contextFingerprint = "cycle-context"
        val headFingerprint = StableFieldIds.fingerprint(
            "productive-world-head/v1",
            "3",
            ref.fingerprint(),
            "",
            "eq-v2",
            cycleId.value,
            contextFingerprint,
        )
        val worldHead = ProductiveWorldHead.restore(
            revision = 3,
            activeSnapshot = ref,
            predecessorSnapshotId = null,
            equationVersion = "eq-v2",
            cycleId = cycleId,
            cycleContextFingerprint = contextFingerprint,
            fingerprint = headFingerprint,
        )
        val equationHead = WorldEquationHead.create(
            revision = 4,
            activeEquationVersion = "eq-v2",
            predecessorEquationVersion = "eq-v1",
        )
        val cognitive = CognitiveSnapshot(
            schemaVersion = 1,
            worldRevision = 3,
            eventSequence = 7,
            worldRoot = "world-snapshot-3",
            payload = byteArrayOf(1, 2, 3),
        )

        val result = projector.project(
            baseInputs(
                authorities = SelfObservationSource.Available(
                    SelfObservationAuthoritySnapshot(worldHead, equationHead, null, cognitive)
                )
            )
        )

        assertEquals(3L, result.snapshot.world.worldHeadRevision)
        assertEquals(headFingerprint, result.snapshot.world.worldHeadFingerprint)
        assertEquals(4L, result.snapshot.world.worldEquationRevision)
        assertEquals("eq-v2", result.snapshot.world.worldEquationVersion)
        assertEquals(equationHead.fingerprint, result.snapshot.world.worldEquationFingerprint)
        assertTrue(result.snapshot.world.cognitiveSnapshotFingerprint?.isNotBlank() == true)
    }

    @Test
    fun missingSourceRemainsUnavailableInsteadOfZero() {
        val unavailable = SelfObservationSource.Unavailable("not-ready")
        val result = projector.project(
            SelfStateProjectionInputs(
                capturedAt = now,
                photonIndex = unavailable,
                memory = unavailable,
                authorities = unavailable,
                runtimeTopology = unavailable,
                health = unavailable,
                resources = unavailable,
                recovery = unavailable,
                tools = unavailable,
                liveSources = unavailable,
            )
        )

        assertNull(result.snapshot.photon.livePhotonCount)
        assertNull(result.snapshot.memory.authoritativePhotonCount)
        assertNull(result.snapshot.health.healthy)
        assertNull(result.snapshot.tools.totalTools)
        assertNull(result.snapshot.liveSources.sourceCount)
        assertNull(result.snapshot.recovery.activeRepairs)
        assertTrue(result.issues.all { it.kind == SelfObservationIssueKind.UNAVAILABLE })
    }

    @Test
    fun selfObservationHealthOutputIsExcludedFromItsOwnInputProjection() {
        val health = HealthSnapshot(
            nodes = listOf(
                HealthNode(
                    id = HealthNodeId(SELF_OBSERVATION_HEALTH_NODE_ID),
                    scope = HealthScope.RUNTIME,
                    state = HealthState.UNHEALTHY,
                ),
                HealthNode(
                    id = HealthNodeId("runtime"),
                    scope = HealthScope.RUNTIME,
                    state = HealthState.HEALTHY,
                ),
            ),
            capturedAt = now,
        )
        val result = projector.project(
            baseInputs().copy(health = SelfObservationSource.Available(health))
        )

        assertEquals(1, result.snapshot.health.healthy)
        assertEquals(0, result.snapshot.health.unhealthy)
        assertEquals(0, result.snapshot.health.unknown)
    }

    @Test
    fun unknownHealthNodesRemainExplicitInsteadOfDisappearingFromSelfState() {
        val health = HealthSnapshot(
            nodes = listOf(
                HealthNode(
                    id = HealthNodeId("unobserved-component"),
                    scope = HealthScope.RUNTIME,
                    state = HealthState.UNKNOWN,
                ),
            ),
            capturedAt = now,
        )

        val result = projector.project(
            baseInputs().copy(health = SelfObservationSource.Available(health))
        )

        assertEquals(1, result.snapshot.health.unknown)
        assertEquals(0, result.snapshot.health.healthy)
    }

    @Test
    fun photonIndexCorruptionIsSurfacedAndNotFingerprintedAsHealthy() {
        val report = PhotonIndexReport(
            formatVersion = 3,
            entryCount = 2,
            livePhotonCount = 1,
            tombstonedPhotonCount = 1,
            latestRefs = emptyMap(),
            unreadableRevisionFiles = listOf("bad/revision.photon"),
        )
        val result = projector.project(
            baseInputs(photonIndex = SelfObservationSource.Available(report))
        )

        assertEquals(2L, result.snapshot.photon.latestRevisionCount)
        assertNull(result.snapshot.photon.indexFingerprint)
        assertNull(result.snapshot.photon.headFingerprint)
        assertTrue(
            result.issues.any {
                it.domain == SelfObservationDomain.PHOTON &&
                    it.kind == SelfObservationIssueKind.CORRUPT
            }
        )
    }

    @Test
    fun photonIndexIdentityIgnoresRevisionChurnButHeadFingerprintDoesNot() {
        val id = PhotonId("stable-photon")
        val first = PhotonIndexReport(
            formatVersion = 3,
            entryCount = 1,
            livePhotonCount = 1,
            tombstonedPhotonCount = 0,
            latestRefs = mapOf(id to PhotonRevisionRef(id, 1)),
        )
        val advanced = PhotonIndexReport(
            formatVersion = 3,
            entryCount = 2,
            livePhotonCount = 1,
            tombstonedPhotonCount = 0,
            latestRefs = mapOf(id to PhotonRevisionRef(id, 2)),
        )

        val firstState = projector.project(
            baseInputs(photonIndex = SelfObservationSource.Available(first))
        ).snapshot.photon
        val advancedState = projector.project(
            baseInputs(photonIndex = SelfObservationSource.Available(advanced))
        ).snapshot.photon

        assertEquals(firstState.indexFingerprint, advancedState.indexFingerprint)
        kotlin.test.assertNotEquals(firstState.headFingerprint, advancedState.headFingerprint)
    }

    @Test
    fun photonIndexIdentityChangesWhenCanonicalPhotonIdentityChanges() {
        val firstId = PhotonId("stable-photon")
        val secondId = PhotonId("new-photon")
        val first = PhotonIndexReport(
            formatVersion = 3,
            entryCount = 1,
            livePhotonCount = 1,
            tombstonedPhotonCount = 0,
            latestRefs = mapOf(firstId to PhotonRevisionRef(firstId, 1)),
        )
        val changed = PhotonIndexReport(
            formatVersion = 3,
            entryCount = 1,
            livePhotonCount = 1,
            tombstonedPhotonCount = 0,
            latestRefs = mapOf(secondId to PhotonRevisionRef(secondId, 1)),
        )

        val firstState = projector.project(
            baseInputs(photonIndex = SelfObservationSource.Available(first))
        ).snapshot.photon
        val changedState = projector.project(
            baseInputs(photonIndex = SelfObservationSource.Available(changed))
        ).snapshot.photon

        kotlin.test.assertNotEquals(firstState.indexFingerprint, changedState.indexFingerprint)
    }

    private fun baseInputs(
        photonIndex: SelfObservationSource<PhotonIndexReport> = SelfObservationSource.Available(
            PhotonIndexReport(3, 0, 0, 0, emptyMap())
        ),
        authorities: SelfObservationSource<SelfObservationAuthoritySnapshot> =
            SelfObservationSource.Available(SelfObservationAuthoritySnapshot(null, null, null, null)),
    ): SelfStateProjectionInputs = SelfStateProjectionInputs(
        capturedAt = now,
        photonIndex = photonIndex,
        memory = SelfObservationSource.Unavailable("memory-not-ready"),
        authorities = authorities,
        runtimeTopology = SelfObservationSource.Available(
            LifeOsRuntimeTopologySnapshot(
                subsystems = listOf(
                    LifeOsSubsystemStatus(
                        descriptor = LifeOsSubsystemDescriptor("world"),
                        state = LifeOsSubsystemState.ACTIVE,
                    )
                ),
                capabilityProviderCount = 0,
                generatedProviderCount = 0,
                manifestFingerprint = "topology-a",
            )
        ),
        health = SelfObservationSource.Available(HealthSnapshot(emptyList(), now)),
        resources = SelfObservationSource.Available(
            HardwareStateSnapshot(
                observedAt = now,
                availableProcessors = 4,
                thermalState = HardwareThermalState.NOMINAL,
            )
        ),
        recovery = SelfObservationSource.Available(emptyList()),
        tools = SelfObservationSource.Available(
            app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus(emptyList())
        ),
        liveSources = SelfObservationSource.Available(
            SelfLiveSourceProjectionInput(emptySet(), emptySet(), emptySet(), emptySet(), "sources-a")
        ),
    )
}
