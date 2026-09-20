package app.lifeos.next

import app.lifeos.core.runtime.self.SelfObservationCycle
import app.lifeos.core.runtime.world.SelfStateWorldFormulaAssessment

data class SelfObservationAnalysisState(
    val cycle: SelfObservationCycle,
    val assessment: SelfStateWorldFormulaAssessment,
)
