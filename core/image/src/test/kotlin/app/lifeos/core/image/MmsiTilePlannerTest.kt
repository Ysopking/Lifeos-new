package app.lifeos.core.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MmsiTilePlannerTest {
    @Test
    fun fourKPlanStaysInsideWorkingSetBudgetAndCoversImage() {
        val budget = 96L * 1024L * 1024L
        val plan = MmsiTilePlanner(budgetBytes = budget).plan(3840, 2160)

        assertTrue(plan.tiles.isNotEmpty())
        assertTrue(plan.estimatedWorkingSetBytes <= budget)
        assertEquals(3840L * 2160L, plan.tiles.sumOf { it.width.toLong() * it.height.toLong() })
        assertTrue(plan.tiles.all { it.width > 0 && it.height > 0 })
    }

    @Test
    fun tightBudgetProducesMultipleTilesWithoutExceedingBudget() {
        val budget = 12L * 1024L * 1024L
        val planner = MmsiTilePlanner(
            budgetBytes = budget,
            overheadBytes = 2L * 1024L * 1024L,
        )
        val plan = planner.plan(1920, 1080)

        assertTrue(plan.tiles.size > 1)
        assertTrue(plan.estimatedWorkingSetBytes <= budget)
        assertEquals(1920L * 1080L, plan.tiles.sumOf { it.width.toLong() * it.height.toLong() })
    }

    @Test
    fun smallImagesRemainSingleTile() {
        val plan = MmsiTilePlanner().plan(320, 240)
        assertEquals(listOf(MmsiTile(0, 0, 320, 240)), plan.tiles)
        assertEquals(320 * 240, plan.maxTilePixels)
    }
}
