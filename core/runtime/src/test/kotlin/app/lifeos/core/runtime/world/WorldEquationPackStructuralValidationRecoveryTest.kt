package app.lifeos.core.runtime.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WorldEquationPackStructuralValidationRecoveryTest {
    @Test
    fun emptyRepositoryIsCleanAndReviewableButNeverActivating() = runTest {
        val recovery = WorldEquationPackStructuralValidationRecovery(
            InMemoryWorldEquationPackStructuralValidationRepository(),
        )

        val report = recovery.inspect()

        assertEquals(WorldEquationPackStructuralValidationRecoveryState.EMPTY, report.state)
        assertTrue(report.structuralReviewAllowed)
        assertFalse(report.failClosed)
        assertFalse(report.productiveActivationAllowed)
        assertFalse(report.productiveWorldMutationAllowed)
        assertEquals(emptyList(), recovery.requireHealthyForReview())
    }

    @Test
    fun unreadableEntryFailsClosed() = runTest {
        val repository = object : WorldEquationPackStructuralValidationRepository {
            override suspend fun putIfAbsent(
                bundle: WorldEquationPackStructuralValidationBundle,
            ) = Unit

            override suspend fun load(
                candidatePackFingerprint: String,
            ): WorldEquationPackStructuralValidationBundle? = null

            override suspend fun loadReport(): WorldEquationPackStructuralValidationLoadReport =
                WorldEquationPackStructuralValidationLoadReport(
                    bundles = emptyList(),
                    unreadableEntries = listOf("corrupt.wepkv"),
                )
        }
        val recovery = WorldEquationPackStructuralValidationRecovery(repository)

        val report = recovery.inspect()

        assertEquals(WorldEquationPackStructuralValidationRecoveryState.CORRUPTED, report.state)
        assertTrue(report.failClosed)
        assertFalse(report.structuralReviewAllowed)
        assertFailsWith<IllegalArgumentException> {
            recovery.requireHealthyForReview()
        }
    }
}
