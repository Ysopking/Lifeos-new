package app.lifeos.core.runtime.health

import app.lifeos.core.runtime.RuntimeFailure
import app.lifeos.core.runtime.RuntimeFailureCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FailureClassifierTest {
    private val classifier = FailureClassifier()

    @Test
    fun fieldFailureMapsToFieldScopeAndDegradedWhenRecoverable() {
        val result = classifier.classify(
            RuntimeFailure(
                category = RuntimeFailureCategory.FIELD,
                source = "thought-field",
                message = "field failed",
            )
        )

        assertEquals(HealthFailureCategory.FIELD, result.category)
        assertEquals(HealthScope.FIELD, result.scope)
        assertEquals(HealthState.DEGRADED, result.suggestedState)
        assertTrue(result.recoverable)
    }

    @Test
    fun invariantIsNeverClassifiedAsRecoverable() {
        val result = classifier.classify(
            RuntimeFailure(
                category = RuntimeFailureCategory.INVARIANT,
                source = "cognitive-worker",
                message = "state invariant violated",
                recoverable = true,
            )
        )

        assertEquals(HealthFailureCategory.STATE_INVARIANT, result.category)
        assertEquals(HealthScope.WORKER, result.scope)
        assertEquals(HealthState.UNHEALTHY, result.suggestedState)
        assertFalse(result.recoverable)
    }

    @Test
    fun storageRepositoryMapsToStorageEngine() {
        val result = classifier.classify(
            RuntimeFailure(
                category = RuntimeFailureCategory.STORAGE,
                source = "checkpoint-repository",
                message = "io failed",
            )
        )

        assertEquals(HealthFailureCategory.STORAGE_IO, result.category)
        assertEquals(HealthScope.STORAGE_ENGINE, result.scope)
    }
}
