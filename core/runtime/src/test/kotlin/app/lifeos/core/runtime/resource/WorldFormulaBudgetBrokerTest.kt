package app.lifeos.core.runtime.resource

import app.lifeos.core.runtime.world.InMemoryWorldEquationRegistry
import app.lifeos.core.runtime.world.ResourceAllocationWorldEquationProfile
import app.lifeos.core.runtime.world.WorldFormulaCoordinator
import app.lifeos.core.runtime.world.WorldFormulaSnapshot
import app.lifeos.core.runtime.world.WorldFormulaSnapshotLoadReport
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRepository
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorldFormulaBudgetBrokerTest {
    @Test
    fun `higher goal priority and utility receive more shared work capacity`() = runTest {
        val profile = ResourceAllocationWorldEquationProfile()
        val snapshots = TestSnapshotRepository()
        val broker = WorldFormulaBudgetBroker(
            worldFormula = WorldFormulaCoordinator(
                equations = InMemoryWorldEquationRegistry(listOf(profile.spec)),
                snapshots = snapshots,
            ),
            profile = profile,
        )
        val pool = ResourceBudgetQuota(
            elapsedMillis = 1_000,
            workUnits = 100,
            memoryBytes = 10_000,
            ioBytes = 1_000,
            networkBytes = 0,
            candidates = 10,
        )
        val decision = broker.allocate(
            pool = pool,
            hardware = healthyHardware(),
            demands = listOf(
                ResourceBudgetDemand(
                    domain = ResourceBudgetDomain.GOAL_EXECUTION,
                    requested = ResourceBudgetUsage(
                        elapsedMillis = 1_000,
                        workUnits = 100,
                        memoryBytes = 10_000,
                        candidates = 10,
                    ),
                    goalRelevance = 1.0,
                    priority = 1.0,
                    expectedUtility = 0.95,
                ),
                ResourceBudgetDemand(
                    domain = ResourceBudgetDomain.BACKGROUND,
                    requested = ResourceBudgetUsage(
                        elapsedMillis = 1_000,
                        workUnits = 100,
                        memoryBytes = 10_000,
                        candidates = 10,
                    ),
                    goalRelevance = 0.15,
                    priority = 0.10,
                    expectedUtility = 0.20,
                ),
            ),
        )

        val plan = assertIs<WorldFormulaBudgetBrokerDecision.Ready>(decision).plan
        val goal = requireNotNull(plan.allocation(ResourceBudgetDomain.GOAL_EXECUTION))
        val background = requireNotNull(plan.allocation(ResourceBudgetDomain.BACKGROUND))
        assertTrue(goal.worldWeight > background.worldWeight)
        assertTrue(goal.allocated.workUnits > background.allocated.workUnits)
        assertTrue(goal.allocated.memoryBytes > background.allocated.memoryBytes)
        assertEquals(pool.workUnits, goal.allocated.workUnits + background.allocated.workUnits + plan.unallocated.workUnits)
        assertEquals(pool.memoryBytes, goal.allocated.memoryBytes + background.allocated.memoryBytes + plan.unallocated.memoryBytes)
        assertTrue(plan.worldSnapshotId.startsWith("world-snapshot:"))
        assertEquals(1, snapshots.values.size)
    }


    @Test
    fun `resource weight applies demand confidence once`() = runTest {
        val profile = ResourceAllocationWorldEquationProfile()
        val broker = WorldFormulaBudgetBroker(
            WorldFormulaCoordinator(
                InMemoryWorldEquationRegistry(listOf(profile.spec)),
                TestSnapshotRepository(),
            ),
            profile,
        )
        val pool = ResourceBudgetQuota(
            elapsedMillis = 1_000,
            workUnits = 100,
            memoryBytes = 1_000,
            ioBytes = 500,
            networkBytes = 0,
            candidates = 4,
        )
        val plan = assertIs<WorldFormulaBudgetBrokerDecision.Ready>(
            broker.allocate(
                pool = pool,
                hardware = healthyHardware(),
                demands = listOf(
                    ResourceBudgetDemand(
                        domain = ResourceBudgetDomain.GOAL_EXECUTION,
                        requested = ResourceBudgetUsage(workUnits = 10),
                        goalRelevance = 1.0,
                        priority = 1.0,
                        expectedUtility = 1.0,
                        confidence = 1.0,
                    ),
                    ResourceBudgetDemand(
                        domain = ResourceBudgetDomain.BACKGROUND,
                        requested = ResourceBudgetUsage(workUnits = 10),
                        goalRelevance = 1.0,
                        priority = 1.0,
                        expectedUtility = 1.0,
                        confidence = 0.5,
                    ),
                ),
            )
        ).plan

        val full = requireNotNull(plan.allocation(ResourceBudgetDomain.GOAL_EXECUTION)).worldWeight
        val half = requireNotNull(plan.allocation(ResourceBudgetDomain.BACKGROUND)).worldWeight
        val ratio = half / full
        assertTrue(ratio in 0.45..0.55, "confidence must attenuate once, ratio=$ratio")
    }

    @Test
    fun `unused demand is returned as unallocated capacity rather than over assigned`() = runTest {
        val profile = ResourceAllocationWorldEquationProfile()
        val broker = WorldFormulaBudgetBroker(
            WorldFormulaCoordinator(
                InMemoryWorldEquationRegistry(listOf(profile.spec)),
                TestSnapshotRepository(),
            ),
            profile,
        )
        val pool = ResourceBudgetQuota(
            elapsedMillis = 1_000,
            workUnits = 100,
            memoryBytes = 1_000,
            ioBytes = 500,
            networkBytes = 0,
            candidates = 5,
        )
        val plan = assertIs<WorldFormulaBudgetBrokerDecision.Ready>(
            broker.allocate(
                pool,
                healthyHardware(),
                listOf(
                    ResourceBudgetDemand(
                        domain = ResourceBudgetDomain.COGNITION,
                        requested = ResourceBudgetUsage(workUnits = 7, memoryBytes = 100),
                        goalRelevance = 0.7,
                        priority = 0.8,
                        expectedUtility = 0.8,
                    )
                ),
            )
        ).plan

        val cognition = requireNotNull(plan.allocation(ResourceBudgetDomain.COGNITION))
        assertEquals(7L, cognition.allocated.workUnits)
        assertEquals(100L, cognition.allocated.memoryBytes)
        assertEquals(93L, plan.unallocated.workUnits)
        assertEquals(900L, plan.unallocated.memoryBytes)
    }

    @Test
    fun `thermal emergency blocks world-formula allocation`() = runTest {
        val profile = ResourceAllocationWorldEquationProfile()
        val broker = WorldFormulaBudgetBroker(
            WorldFormulaCoordinator(
                InMemoryWorldEquationRegistry(listOf(profile.spec)),
                TestSnapshotRepository(),
            ),
            profile,
        )
        val decision = broker.allocate(
            pool = ResourceBudgetQuota(1_000, 100, 1_000, 1_000, 0, 2),
            hardware = healthyHardware().copy(thermalState = HardwareThermalState.EMERGENCY),
            demands = listOf(
                ResourceBudgetDemand(
                    ResourceBudgetDomain.DEEP_SEARCH,
                    ResourceBudgetUsage(workUnits = 10),
                    goalRelevance = 1.0,
                    priority = 1.0,
                    expectedUtility = 1.0,
                )
            ),
        )

        assertEquals(
            "hardware-state-requires-suspension",
            assertIs<WorldFormulaBudgetBrokerDecision.Blocked>(decision).reason,
        )
    }

    private fun healthyHardware() = HardwareStateSnapshot(
        observedAt = Instant.parse("2026-09-11T12:00:00Z"),
        availableProcessors = 8,
        batteryFraction = 0.90,
        charging = true,
        thermalState = HardwareThermalState.NOMINAL,
        cpuLoadFraction = 0.10,
        availableMemoryBytes = 7_000,
        totalMemoryBytes = 8_000,
        availableStorageBytes = 80_000,
        totalStorageBytes = 100_000,
    )

    private class TestSnapshotRepository : WorldFormulaSnapshotRepository {
        val values = linkedMapOf<String, WorldFormulaSnapshot>()

        override suspend fun save(snapshot: WorldFormulaSnapshot) {
            values[snapshot.id] = snapshot
        }

        override suspend fun load(id: String): WorldFormulaSnapshot? = values[id]

        override suspend fun loadLatest(): WorldFormulaSnapshot? = values.values.lastOrNull()

        override suspend fun loadReport(): WorldFormulaSnapshotLoadReport =
            WorldFormulaSnapshotLoadReport(values.values.toList(), emptyList())

        override suspend fun delete(id: String) {
            values.remove(id)
        }
    }
}
