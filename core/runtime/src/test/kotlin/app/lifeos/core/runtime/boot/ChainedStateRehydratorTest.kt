package app.lifeos.core.runtime.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class ChainedStateRehydratorTest {
    @Test
    fun `runs primary and additional restore steps in exact order`() = runTest {
        val calls = mutableListOf<String>()
        val expected = RehydratedRuntimeState(previousEpoch = 7)
        val rehydrator = ChainedStateRehydrator(
            primary = object : StateRehydrator {
                override suspend fun rehydrate(): RehydratedRuntimeState {
                    calls += "primary"
                    return expected
                }
            },
            additionalSteps = listOf(
                RuntimeStateRehydrationStep { calls += "generated-tools" },
                RuntimeStateRehydrationStep { calls += "future-step" },
            ),
        )

        assertEquals(expected, rehydrator.rehydrate())
        assertEquals(listOf("primary", "generated-tools", "future-step"), calls)
    }

    @Test
    fun `additional restore failure is fail fast`() = runTest {
        val calls = mutableListOf<String>()
        val rehydrator = ChainedStateRehydrator(
            primary = object : StateRehydrator {
                override suspend fun rehydrate(): RehydratedRuntimeState {
                    calls += "primary"
                    return RehydratedRuntimeState()
                }
            },
            additionalSteps = listOf(
                RuntimeStateRehydrationStep {
                    calls += "generated-tools"
                    error("corrupt generated-tool vault")
                },
                RuntimeStateRehydrationStep { calls += "must-not-run" },
            ),
        )

        val failure = assertFailsWith<IllegalStateException> { rehydrator.rehydrate() }
        assertEquals("corrupt generated-tool vault", failure.message)
        assertEquals(listOf("primary", "generated-tools"), calls)
    }

    @Test
    fun `primary restore failure prevents additional state mutation`() = runTest {
        val calls = mutableListOf<String>()
        val rehydrator = ChainedStateRehydrator(
            primary = object : StateRehydrator {
                override suspend fun rehydrate(): RehydratedRuntimeState {
                    calls += "primary"
                    error("runtime restore failed")
                }
            },
            additionalSteps = listOf(RuntimeStateRehydrationStep { calls += "generated-tools" }),
        )

        assertFailsWith<IllegalStateException> { rehydrator.rehydrate() }
        assertEquals(listOf("primary"), calls)
    }
}
