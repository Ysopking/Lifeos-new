package app.lifeos.core.runtime.field

import app.lifeos.core.field.ConvergenceStatus
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.model.task.TaskState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldShadowDefaultCorpusGateTest {
    @Test
    fun `default gate requires thirty two deterministic replay cases`() {
        val domain = StableFieldIds.domain("test.default-replay-corpus")
        val validator = FieldShadowValidator(
            policy = FieldShadowValidationPolicy(selectedDomains = setOf(domain)),
            now = { Instant.parse("2026-09-10T10:00:00Z") },
        )
        val cases = (1..32).map { index ->
            validator.validate(
                FieldShadowValidationInput(
                    taskId = TaskId("replay-$index"),
                    domainId = domain,
                    legacy = LegacyFieldObservation(
                        finalState = TaskState.COMPLETED,
                        semanticState = ShadowSemanticState.RESOLVED,
                        influenceCount = 1,
                        influenceTypes = setOf("INDEX"),
                        averageConfidence = 0.9,
                        totalEnergyDelta = 0.8,
                    ),
                    universal = UniversalFieldObservation(
                        shadowState = FieldShadowState.COMPLETED,
                        semanticState = ShadowSemanticState.RESOLVED,
                        convergenceStatus = ConvergenceStatus.CONVERGED,
                        snapshotPresent = true,
                        winnerCount = 1,
                        topConfidence = 0.9,
                    ),
                    sourceStatus = ShadowSourceStatus.PRESERVED,
                    taskOwnershipStatus = ShadowTaskOwnershipStatus.PRESERVED,
                    origin = FieldShadowValidationOrigin.DETERMINISTIC_REPLAY,
                    replayCaseId = "case-$index",
                )
            )
        }

        assertEquals(
            FieldCutoverRecommendation.INSUFFICIENT_REPLAY_CORPUS,
            validator.report(domain, cases.take(31)).recommendation,
        )
        assertEquals(
            FieldCutoverRecommendation.READY_FOR_SELECTED_DOMAIN,
            validator.report(domain, cases).recommendation,
        )
    }
}
