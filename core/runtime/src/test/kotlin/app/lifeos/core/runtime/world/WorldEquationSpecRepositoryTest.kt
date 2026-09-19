package app.lifeos.core.runtime.world

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class WorldEquationSpecRepositoryTest {
    @Test
    fun immutableVersionCannotBeReboundToDifferentPhysics() = runTest {
        val baseline = CognitiveWorldEquationProfile().spec
        val first = baseline.stableCoefficients().first()
        val conflicting = baseline.copy(
            coefficients = baseline.coefficients.map {
                if (it.id == first.id) {
                    it.copy(multiplier = if (it.multiplier < 0.9) it.multiplier + 0.05 else it.multiplier - 0.05)
                } else {
                    it
                }
            },
        )
        val repository = InMemoryWorldEquationSpecRepository()
        repository.putIfAbsent(baseline)

        assertFailsWith<IllegalArgumentException> {
            repository.putIfAbsent(conflicting)
        }
    }
}
